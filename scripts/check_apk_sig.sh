#!/usr/bin/env bash
# ════════════════════════════════════════════════════════════════
#  check_apk_sig.sh — APK সাইন আছে কিনা **সঠিকভাবে** বলে
#
#  কেন দরকার: আগের CI চেক `unzip -p APK META-INF/MANIFEST.MF | grep SHA` শুধু
# v1 (JAR) সাইনিং দেখে। minSdk ≥ 24 হলে AGP v1 বসায় না — v2/v3 বসে
# APK Signing Block-এ → তাই সাইন্ডেড beta.9 APK-ও "NOT SIGNED" বলেছিল
# (false negative, মাপা: v2 pair 0x7109871a উপস্থিত, MANIFEST.MF অনুপস্থিত)।
#
#  Usage:   bash scripts/check_apk_sig.sh app.apk
#  Exit:    0 = signed (v1 অথবা v2/v3/v3.1) · 1 = unsigned
#  apksigner পাওয়া গেলে সেটাই প্রাথমিকতা (সবচেয়ে নির্ভরযোগ্য)।
# ════════════════════════════════════════════════════════════════
set -uo pipefail
APK="${1:-}"
[ -f "$APK" ] || { echo "usage: $0 <apk>"; exit 2; }

# ── 1. apksigner (build-tools PATH-এ থাকলে) (build-tools PATH-এ থাকলে) ─────────────────────────
for c in apksigner "${ANDROID_HOME:-/nonexistent}/build-tools"/*/apksigner; do
  if command -v "$c" >/dev/null 2>&1 || [ -x "$c" ]; then
    if out=$("$c" verify --print-certs "$APK" 2>&1); then
      echo "  🔐 apksigner verify: ✅ SIGNED"
      echo "$out" | grep -E "Signer #1 certificate (DN|SHA-256)" | sed 's/^/     /'
      exit 0
    else
      echo "  🔐 apksigner verify: ❌ $out" ; exit 1
    fi
  fi
done

# ── 2. Fallback: zip + APK Signing Block parse (কোনো Android টুল লাগে না) ──
python3 - "$APK" <<'PY'
import sys, os, struct, zipfile
p = sys.argv[1]
sz = os.path.getsize(p)
schemes = []

# v1 (JAR signing) — META-INF/MANIFEST.MF + .SF/.RSA/.DSA
try:
    names = zipfile.ZipFile(p).namelist()
    if any(n == 'META-INF/MANIFEST.MF' for n in names):
        schemes.append('v1 (JAR)')
except Exception as e:
    print(f"  ⚠️ zip parse: {e}")

# v2/v3/v3.1 — APK Signing Block
MAGIC = b'APK Sig Block 42'
IDS = {0x7109871a: 'v2', 0xf05368c0: 'v3', 0x1b93ad61: 'v3.1'}
with open(p, 'rb') as f:
    # CD এ বড় হতে পারে (আমাদের APK-তে 5425 entries → ~594 KB) → 8 MiB উইন্ডো
    f.seek(max(0, sz - 8 * 1024 * 1024)); tail = f.read()
    m = tail.rfind(MAGIC)
    if m >= 0:
        abs_m = (sz - len(tail)) + m
        f.seek(abs_m - 8); blk = struct.unpack('<Q', f.read(8))[0]
        start = abs_m + 16 - blk
        if 0 <= start < abs_m - 8:
            f.seek(start); block = f.read((abs_m - 8) - start)
            off = 0
            while off + 8 <= len(block):
                pid = struct.unpack('<I', block[off:off+4])[0]
                plen = struct.unpack('<I', block[off+4:off+8])[0]
                if pid in IDS: schemes.append('APK Signature Scheme ' + IDS[pid])
                if plen > len(block) - off - 8: break   # ছাড়িয়ে গেছে → থামো
                off += 8 + plen                          # plen==0 ও বৈধ (padding pair)

if schemes:
    print("  🔐 signature: ✅ SIGNED — " + ", ".join(dict.fromkeys(schemes)))
    sys.exit(0)
print("  🔐 signature: ❌ NOT SIGNED (no v1, no APK Signing Block pairs) → ইনস্টল হবে না")
sys.exit(1)
PY
