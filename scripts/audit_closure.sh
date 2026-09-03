#!/usr/bin/env bash
# ════════════════════════════════════════════════════════════════
#  audit_closure.sh — ELF linker-closure checker (multi-dir aware)
#
#  প্রতিটা ELF-এর DT_NEEDED লিস্ট নেয়, আর দেখে সেটা [যেকোনো] দেওয়া ডিরে
#  (অথবা Android system lib হিসেবে) মেলানো যায় কি না। runtime module split-এর
#  পর এটা জরুরি: module binary-র libs core ডির থেকে resolve হয়, তাই
#  single-dir check ভুল "MISSING" দেখাবে।
#
#  Usage: bash scripts/audit_closure.sh DIR [DIR...]
#  Exit : 0 = closure complete · 1 = unresolved libs (release আটকাও)
# ════════════════════════════════════════════════════════════════
set -uo pipefail

DIRS=()
for d in "$@"; do
  [ -d "$d" ] && DIRS+=("$(cd "$d" && pwd)") || { echo "❌ dir নেই: $d" >&2; exit 2; }
done
[ ${#DIRS[@]} -gt 0 ] || { echo "usage: $0 DIR [DIR...]" >&2; exit 2; }

# Android-এর নিজস্ব (system) libs — APK-তে থাকার দরকার নেই
SYSTEM='^(libc\.so|libm\.so|libdl\.so|liblog\.so|libz\.so|libcrypt\.so|libandroid\.so|libmediandk\.so|libjnigraphics\.so|libnativewindow\.so|libEGL\.so|libGLES.*\.so|libOpenSLES\.so|libaaudio\.so|libcamera2ndk\.so|libneuralnetworks\.so|libvulkan\.so|ld\.so|linker64)$'

declare -A present=()
for d in "${DIRS[@]}"; do
  for f in "$d"/*; do
    [ -f "$f" ] || continue
    case "$f" in *.so|*.so.*) present["$(basename "$f")"]=1 ;; esac
  done
done

total=0; missing_lines=""
for d in "${DIRS[@]}"; do
  for f in "$d"/*; do
    [ -f "$f" ] || continue
    case "$f" in *.so|*.so.*) ;; *) continue ;; esac
    head -c4 "$f" | grep -q 'ELF' 2>/dev/null || \
      [ "$(head -c4 "$f" | tr -d '\0' | cut -c2-4)" = "ELF" ] || continue
    total=$((total+1))
    while IFS= read -r lib; do
      [ -n "$lib" ] || continue
      echo "$lib" | grep -qE "$SYSTEM" && continue
      [ -n "${present[$lib]:-}" ] && continue
      missing_lines+="  ❌ $lib ← needed by $(basename "$f") ($d)"$'\n'
    done < <(readelf -d "$f" 2>/dev/null | sed -nE 's/.*NEEDED.*\[(.*)\].*/\1/p')
  done
done

echo "──────────────── 🔬 Linker closure audit ────────────────"
echo "  dirs: ${DIRS[*]#$PWD/}"
echo "  libs available: ${#present[@]} · ELFs audited: $total"
if [ -n "$missing_lines" ]; then
  printf '%s' "$missing_lines"
  echo "  → LIB_PACKAGES-এ provider যোগ করো (বা module-এর ডির অডিটে ধরো), নইলে APK ইনস্টলে CANNOT LINK EXECUTABLE খাবে।"
  echo "────────────────────────────────────────────────────────"
  exit 1
fi
echo "  ✅ All NEEDED libraries resolve — closure complete!"
echo "────────────────────────────────────────────────────────"
