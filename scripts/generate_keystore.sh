#!/usr/bin/env bash
#
# INWEB · Release keystore generator
# ═══════════════════════════════════
# Creates a 10-year RSA-2048 keystore and a matching keystore.properties
# file that Gradle can use to sign release APKs.
#
# ⚠️ IMPORTANT
#   • Back up the generated .jks file in 3 places
#   • Never commit .jks or keystore.properties to git
#   • Once used on Play Store, keep the SAME keystore forever
#

set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

KEYSTORE_FILE="inweb-release.jks"
KEY_ALIAS="inweb"
VALIDITY_DAYS=3650   # 10 years

echo "═══════════════════════════════════════════"
echo "  🔐 INWEB Release Keystore Generator"
echo "═══════════════════════════════════════════"
echo ""
echo "  Output file : $ROOT/$KEYSTORE_FILE"
echo "  Key alias   : $KEY_ALIAS"
echo "  Validity    : $VALIDITY_DAYS days (~10 years)"
echo ""

# Sanity checks
if [ -f "$KEYSTORE_FILE" ]; then
    echo "  ❌ $KEYSTORE_FILE already exists — refusing to overwrite."
    echo "     Move or delete it first, then re-run."
    exit 1
fi

if ! command -v keytool >/dev/null 2>&1; then
    echo "  ❌ keytool not found."
    echo "     Install JDK 17: 'sudo apt install openjdk-17-jdk' or 'brew install openjdk@17'"
    exit 1
fi

echo "───────────────────────────────────────────"
echo "  Please provide the following:"
echo "───────────────────────────────────────────"

# Collect info
read -srp "  🔑 Keystore password (12+ chars, mixed):  " KS_PASS; echo
read -srp "  🔑 Confirm keystore password:            " KS_PASS_2; echo

if [ "$KS_PASS" != "$KS_PASS_2" ]; then
    echo "  ❌ Passwords do not match."
    exit 1
fi

if [ ${#KS_PASS} -lt 8 ]; then
    echo "  ❌ Password too short (minimum 8 characters)."
    exit 1
fi

read -srp "  🔑 Key password (Enter to reuse keystore password): " KEY_PASS; echo
if [ -z "$KEY_PASS" ]; then
    KEY_PASS="$KS_PASS"
    echo "     ℹ️ Using same password for both."
fi

echo ""
echo "  📝 Certificate metadata (any value works — used only for display)"
read -p "     Your name / organization  [INWEB]: " NAME
NAME="${NAME:-INWEB}"
read -p "     Organizational unit       [Dev]: " OU
OU="${OU:-Dev}"
read -p "     Organization              [INWEB]: " ORG
ORG="${ORG:-INWEB}"
read -p "     City / locality           [Dhaka]: " CITY
CITY="${CITY:-Dhaka}"
read -p "     State                     [Dhaka]: " STATE
STATE="${STATE:-Dhaka}"
read -p "     2-letter country code     [BD]: " CC
CC="${CC:-BD}"

echo ""
echo "  🏭 Generating keystore... (this takes a few seconds)"

keytool -genkey -v -noprompt \
  -keystore "$KEYSTORE_FILE" \
  -keyalg RSA \
  -keysize 2048 \
  -validity "$VALIDITY_DAYS" \
  -alias "$KEY_ALIAS" \
  -storepass "$KS_PASS" \
  -keypass "$KEY_PASS" \
  -dname "CN=$NAME, OU=$OU, O=$ORG, L=$CITY, ST=$STATE, C=$CC" \
  2>&1 | grep -v "Warning:" || true

if [ ! -f "$KEYSTORE_FILE" ]; then
    echo "  ❌ Keystore creation failed."
    exit 1
fi

# Write keystore.properties
cat > keystore.properties << EOF
# ⚠️  This file contains PRIVATE credentials — never commit to git!
# It's already in .gitignore
storeFile=../$KEYSTORE_FILE
storePassword=$KS_PASS
keyAlias=$KEY_ALIAS
keyPassword=$KEY_PASS
EOF
chmod 600 keystore.properties

# Compute base64 for CI
BASE64=$(base64 -w 0 "$KEYSTORE_FILE" 2>/dev/null || base64 "$KEYSTORE_FILE" | tr -d '\n')
echo "$BASE64" > keystore.b64
chmod 600 keystore.b64

echo ""
echo "═══════════════════════════════════════════"
echo "  ✅ Success! Files created:"
echo "═══════════════════════════════════════════"
echo ""
echo "  📁 $ROOT/"
echo "     ├── $KEYSTORE_FILE       ← keystore (binary, KEEP SAFE)"
echo "     ├── keystore.properties  ← Gradle config (KEEP SAFE)"
echo "     └── keystore.b64         ← base64 for CI (KEEP SAFE)"
echo ""
echo "  🔐 All three files are in .gitignore — will NOT be committed."
echo ""
echo "───────────────────────────────────────────"
echo "  📋 NEXT STEPS"
echo "───────────────────────────────────────────"
echo ""
echo "  1️⃣  Test local signed build:"
echo "        ./gradlew assembleRelease"
echo "        # → app/build/outputs/apk/release/app-release.apk"
echo ""
echo "  2️⃣  For CI/CD (GitHub Actions):"
echo "        Go to your repo → Settings → Secrets and variables → Actions"
echo "        Add these 4 secrets:"
echo ""
echo "        KEYSTORE_BASE64    = (contents of keystore.b64)"
echo "        KEYSTORE_PASSWORD  = your keystore password"
echo "        KEY_PASSWORD       = your key password"
echo "        KEY_ALIAS          = $KEY_ALIAS"
echo ""
echo "  3️⃣  BACK UP $KEYSTORE_FILE  (Play Store needs the SAME keystore forever!)"
echo "        • Copy to encrypted USB drive"
echo "        • Copy to password manager (1Password, Bitwarden)"
echo "        • Copy to offline cold storage"
echo ""
echo "  ⚠️  If you lose this keystore, you can NEVER update your app on Play Store."
echo ""
echo "═══════════════════════════════════════════"
