#!/usr/bin/env bash
# ════════════════════════════════════════════════════════════════
#  split_modules.sh — core jniLibs → downloadable runtime modules
#
#  INWEB-এর APK 324 MB-এর ~93% ছিল bundled .so (মাপা)। ভারী কিন্তু optional
#  বাইনারি (node / caddy / cloudflared) আলাদা **runtime module APK**-তে পাঠালে
#  core APK ছোট থাকে, আবার exec-ও legal থাকে (module-ও ইনস্টলড APK-র
#  nativeLibraryDir থেকেই চলে — Android 10+ W^X ফাঁকি না দিয়ে)।
#
#  Usage:
#    bash scripts/split_modules.sh            # আসল move
#    bash scripts/split_modules.sh --dry-run  # কী যেত শুধু দেখায়
#    bash scripts/split_modules.sh --core-dir app/src/main/jniLibs/arm64-v8a
#
#  নীতি:
#   1) module binary (libexec_*.so) সবসময় module-এ যায়।
#   2) shared lib module-এর লাগলেও core-র কোনো exec-এর লাগলে **core-তেই থাকে**
#      (runtime-এ LD_LIBRARY_PATH-তে দুটো ডিরই দেওয়া হয়, তাই শেয়ার করা চলে)।
#   3) শুধু module-এর জন্য দরকারি lib গুলো module-এ কপি হয় (ডুপ্লিকেট হলেও নিরাপদ)।
# ════════════════════════════════════════════════════════════════
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
CORE="$ROOT/app/src/main/jniLibs/arm64-v8a"
DRY=0

while [ $# -gt 0 ]; do
  case "$1" in
    --dry-run) DRY=1; shift ;;
    --core-dir) CORE="$2"; shift 2 ;;
    -h|--help) sed -n '2,22p' "$0"; exit 0 ;;
    *) echo "unknown arg: $1" >&2; exit 2 ;;
  esac
done

[ -d "$CORE" ] || { echo "❌ core dir নেই: $CORE (আগে scripts/fetch_binaries.sh চালাও)"; exit 1; }

# module id → binary filename → destination dir
MODULE_IDS=(node caddy tunnel)
declare -A MODULE_BIN=(
  [node]="libexec_node.so"
  [caddy]="libexec_caddy.so"
  [tunnel]="libexec_cloudflared.so"
)
MODULE_BINS=("${MODULE_BIN[@]}")   # শুধু নামের লিস্ট (case pattern-এ array subscript চলে না)

mod_dir() { echo "$ROOT/runtime-modules/$1/src/main/jniLibs/arm64-v8a"; }

needed_of() {  # $1 = elf path → NEEDED lib names, এক লাইনে
  readelf -d "$1" 2>/dev/null | sed -nE 's/.*NEEDED.*\[(.*)\].*/\1/p'
}

echo "═══════════════════════════════════════════════════════════════"
echo "  📦 core → runtime module split  $([ $DRY = 1 ] && echo '(DRY-RUN)')"
echo "     core: ${CORE#$ROOT/}"
echo "═══════════════════════════════════════════════════════════════"

# ── 1) core-এ থাকলে যা যা NEEDED হয় (ওই সেটটা core-তেই রাখতে হবে) ────────
declare -A CORE_NEEDED=()
for f in "$CORE"/*; do
  [ -f "$f" ] || continue
  _skip=0; for _mb in "${MODULE_BINS[@]}"; do [ "$f" = "$CORE/$_mb" ] && _skip=1; done
  [ $_skip = 1 ] && continue   # module-এ চলে যাবে → core-র NEEDED সেটে গন্য না
  while IFS= read -r lib; do [ -n "$lib" ] && CORE_NEEDED["$lib"]=1; done < <(needed_of "$f")
done
echo "  core NEEDED সেট: ${#CORE_NEEDED[@]}টি লাইব্রেরি"

moved_bin=0; moved_lib=0; kept_shared=0
for m in "${MODULE_IDS[@]}"; do
  bin="${MODULE_BIN[$m]}"
  src="$CORE/$bin"
  [ -f "$src" ] || { echo "  · $m: $bin নেই → skip"; continue; }
  dst="$(mod_dir "$m")"

  # ⚠️ mv-এর আগেই সাইজ + dependency closure ধরে রাখি (নইলে src হারিয়ে যায়)
  mb=$(du -h "$src" | cut -f1)
  declare -A WANT=()
  while IFS= read -r lib; do [ -n "$lib" ] && WANT["$lib"]=1; done < <(needed_of "$src")
  for _ in 1 2 3; do   # ৩ লেভেল transitive closure (module libs-এর নিজস্ব NEEDED)
    for lib in "${!WANT[@]}"; do
      [ -f "$CORE/$lib" ] || continue
      while IFS= read -r n2; do [ -n "$n2" ] && [ -z "${WANT[$n2]:-}" ] && WANT["$n2"]=1; done < <(needed_of "$CORE/$lib")
    done
  done

  if [ $DRY = 0 ]; then
    mkdir -p "$dst"
    mv "$src" "$dst/$bin"
  fi
  echo "  ✅ $m ← $bin ($mb)"
  moved_bin=$((moved_bin+1))

  for lib in "${!WANT[@]}"; do
    [ -f "$CORE/$lib" ] || continue
    if [ -n "${CORE_NEEDED[$lib]:-}" ]; then
      kept_shared=$((kept_shared+1)); continue     # core-ও চায় → core-তেই থাকে
    fi
    if [ $DRY = 0 ]; then
      cp -L "$CORE/$lib" "$dst/$lib"
      # symlink/নকল দুটোই থাকলে core-টা রেখে দিলাম — ফাঁকা ডির না ভেঙে
      :
    fi
    echo "     ↳ module-only lib: $lib"
    moved_lib=$((moved_lib+1))
  done
  unset WANT
done

echo "───────────────────────────────────────────────────────────────"
echo "  binaries moved: $moved_bin · module-only libs: $moved_lib · shared (kept in core): $kept_shared"
if [ $DRY = 1 ]; then
  echo "  🔎 DRY-RUN — কিছু নড়ানো হয়নি।"
else
  echo "  core এখন: $(ls "$CORE" | wc -l) ফাইল · $(du -sh "$CORE" | cut -f1)"
  for m in "${MODULE_IDS[@]}"; do
    d="$(mod_dir "$m")"; [ -d "$d" ] && echo "  $m: $(ls "$d" | wc -l) ফাইল · $(du -sh "$d" | cut -f1)"
  done
fi
exit 0
