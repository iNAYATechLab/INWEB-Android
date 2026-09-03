#!/usr/bin/env bash
# ════════════════════════════════════════════════════════════════
#  test_module_split.sh — split_modules.sh + audit_closure.sh এর সেলফ-টেস্ট
#
#  INWEB-এর আসল ARM64 বাইনারি ছাড়াই টেস্টটা চলে: হোস্টের x86 ELF ফাইল
#  (ls/curl/git) দিয়ে একটা নকল core jniLibs ট্রি বানাই, যেগুলোর আসল
#  DT_NEEDED গ্রাফ আছে — তাই split + closure audit লজিক হুবহু যাচাই হয়।
#
#  Usage: bash scripts/test_module_split.sh
#  Exit : 0 = সব এক্সপেক্টেশন পাস · 1 = ফেল (CI-তে থামানোর যোগ্য)
# ════════════════════════════════════════════════════════════════
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TMP="$(mktemp -d)"
PROJ="$TMP/proj"
CORE="$PROJ/app/src/main/jniLibs/arm64-v8a"
mkdir -p "$CORE" "$PROJ/scripts"
cp "$ROOT/scripts/split_modules.sh" "$ROOT/scripts/audit_closure.sh" "$PROJ/scripts/"

BIN_MAP=("ls:node:libexec_node.so" "curl:caddy:libexec_caddy.so")

python3 - "$CORE" <<'PY'
import subprocess, os, sys, shutil, glob
C = sys.argv[1]
def needed(f):
    out = subprocess.run(['readelf','-d',f],capture_output=True,text=True).stdout
    return [l.split('[')[1].rstrip(']') for l in out.splitlines() if 'NEEDED' in l]
def locate(name):
    for d in ['/lib/x86_64-linux-gnu','/usr/lib/x86_64-linux-gnu','/lib64','/usr/lib']:
        p = os.path.join(d, name)
        if os.path.exists(p): return p
    g = glob.glob('/usr/lib/**/'+name, recursive=True) + glob.glob('/lib/**/'+name, recursive=True)
    return g[0] if g else None
seeds = []
for exe, name in [('/bin/ls','libexec_nginx.so'), ('/usr/bin/curl','libexec_node.so'), ('/usr/bin/git','libexec_caddy.so')]:
    if os.path.exists(exe):
        shutil.copy2(exe, os.path.join(C, name)); seeds.append(os.path.join(C, name))
seen, stack = set(), list(seeds)
while stack:
    f = stack.pop()
    for n in needed(f):
        if n in seen: continue
        seen.add(n)
        src = locate(n)
        if src:
            shutil.copy2(src, os.path.join(C, n)); stack.append(os.path.join(C, n))
print(f"fixture: {len(seeds)} binaries + {len(seen)} libs · {sum(os.path.getsize(os.path.join(C,x)) for x in os.listdir(C))/1048576:.1f} MB")
PY

pass_cnt=0; fail_cnt=0
check() { # check <desc> <expected_exit> <cmd...>
  local desc="$1" exp="$2"; shift 2
  "$@" >/dev/null 2>&1; local rc=$?
  if [ "$rc" = "$exp" ]; then echo "  ✅ $desc (exit=$rc)"; pass_cnt=$((pass_cnt+1));
  else echo "  ❌ $desc — expected exit $exp, got $rc"; fail_cnt=$((fail_cnt+1)); fi
}

cd "$PROJ" || exit 1

echo "════════ 1. split (core → modules) ════════"
bash scripts/split_modules.sh; SPLIT_RC=$?
check "split_modules.sh exit 0" 0 test "$SPLIT_RC" -eq 0
[ -f "$PROJ/runtime-modules/node/src/main/jniLibs/arm64-v8a/libexec_node.so" ] \
  && { echo "  ✅ libexec_node.so module-এ গেছে"; pass_cnt=$((pass_cnt+1)); } \
  || { echo "  ❌ libexec_node.so module-এ যায়নি"; fail_cnt=$((fail_cnt+1)); }
[ -f "$CORE/libexec_node.so" ] \
  && { echo "  ❌ core-তে এখনো libexec_node.so পড়ে আছে"; fail_cnt=$((fail_cnt+1)); } \
  || { echo "  ✅ core থেকে binary সরানো হয়েছে"; pass_cnt=$((pass_cnt+1)); }
[ -f "$CORE/libc.so.6" ] \
  && { echo "  ✅ shared lib (libc.so.6) core-তেই আছে"; pass_cnt=$((pass_cnt+1)); } \
  || { echo "  ❌ shared lib core থেকে সরে গেছে (ভুল)"; fail_cnt=$((fail_cnt+1)); }

echo "════════ 2. closure audit (union dirs) ════════"
check "audit পাস করে (union of core + modules)" 0 \
  bash scripts/audit_closure.sh "$CORE" "$PROJ"/runtime-modules/*/src/main/jniLibs/arm64-v8a

echo "════════ 3. negative: module-only lib হারালে ════════"
# libcurl.so.4 (node-এর module-only lib) দুই ডির থেকেই তুলে নিই → audit MUST fail
# নোট: split module-only lib গুলো core থেকে **কপি** করে (move করে না), কারণ
# PHP/Apache রানটাইমে dlopen() হওয়া লাইব্রেরির কোনো DT_NEEDED রেফারেন্স থাকে না —
# core থেকে সরিয়ে দিলে সেগুলো ভাঙত। তাই নেগেটিভ টেস্টে দুই ডির থেকেই সরাতে হয়।
MODLIB="$(find "$PROJ/runtime-modules" -name 'libcurl.so.4' | head -1)"
[ -f "$CORE/libcurl.so.4" ] \
  && { echo "  ✅ module-only lib core-তে ডুপ্লিকেট হিসেবে রাখা হয়েছে (dlopen-safe)"; pass_cnt=$((pass_cnt+1)); } \
  || { echo "  ❌ core থেকে module-only lib সরে গেছে (dlopen ভাঙার ঝুঁকি)"; fail_cnt=$((fail_cnt+1)); }
if [ -n "$MODLIB" ]; then
  mv "$MODLIB" "$TMP/hidden-lib-1"; mv "$CORE/libcurl.so.4" "$TMP/hidden-lib-2"
  check "দুই ডির থেকেই lib ফেললে audit exit 1 দেয়" 1 \
    bash scripts/audit_closure.sh "$CORE" "$PROJ"/runtime-modules/*/src/main/jniLibs/arm64-v8a
  mv "$TMP/hidden-lib-1" "$MODLIB"; mv "$TMP/hidden-lib-2" "$CORE/libcurl.so.4"
else
  echo "  ⚠️ fixture-এ libcurl.so.4 module-only lib নেই → নেগেটিভ টেস্ট স্কিপ"
fi

echo "════════ 4. idempotency (দুবার চালানো নিরাপদ) ════════"
check "দ্বিতীয় split run-ও exit 0" 0 bash scripts/split_modules.sh

rm -rf "$TMP"
echo "═══════════════════════════════════════════════════════════════"
echo "  PASS: $pass_cnt · FAIL: $fail_cnt"
echo "═══════════════════════════════════════════════════════════════"
[ "$fail_cnt" -eq 0 ] || exit 1
echo "  ✅ module-split pipeline verified"
