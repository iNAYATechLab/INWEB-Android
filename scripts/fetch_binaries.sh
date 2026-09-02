#!/usr/bin/env bash
#
# INWEB — Termux binary + library fetcher (v3, jniLibs / KSWEB-style)
# ====================================================================
# WHY jniLibs?  Android 10+ (SELinux) forbids exec() from an app's home
# dir for targetSdk>=29 — /data/user/0/<pkg>/files/... returns
# "error=13, Permission denied" (proven on-device, Xiaomi Android 16).
#
# The ONLY user-writable exec location Android grants is the native
# library directory — where the package installer extracts
# jniLibs/<abi>/*.  So every executable ships disguised as a .so:
#
#   nginx      →  jniLibs/arm64-v8a/libexec_nginx.so
#   mariadbd   →  jniLibs/arm64-v8a/libexec_mariadbd.so
#   libssl.so.3→  jniLibs/arm64-v8a/libssl.so.3   (exact SONAME kept)
#
# Shell scripts (apachectl, mysql_install_db …) stay as ASSETS bin/ and
# run through /system/bin/sh — reading needs no exec permission.
#
# Requires  android:extractNativeLibs="true"  in AndroidManifest.
#
# Phases:
#   1. server binaries         (Exec → jniLibs, scripts → assets)
#   2. shared libraries        (jniLibs, SONAME aliases materialised)
#   3. phpMyAdmin tarball
#   4. LINKER CLOSURE AUDIT — fails CI if any NEEDED lib is missing
#
# Usage:
#   ./scripts/fetch_binaries.sh
#   AUDIT_ONLY=1 ./scripts/fetch_binaries.sh
#
set -euo pipefail

BASE="https://packages.termux.dev/apt/termux-main"
INDEX_URL="$BASE/dists/stable/main/binary-aarch64/Packages.gz"
ARCH="aarch64"
WORK="${INWEB_FETCH_WORK:-$(mktemp -d)}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"

JNI_DIR="$ROOT/app/src/main/jniLibs/arm64-v8a"
DEST_BIN="$ROOT/app/src/main/assets/server_env/bin"          # shell scripts only
DEST_ETC="$ROOT/app/src/main/assets/server_env/etc"
DEST_PHP_EXT="$ROOT/app/src/main/assets/server_env/php"
DEST_MYSQL_SHARE="$ROOT/app/src/main/assets/server_env/mysql/share"

mkdir -p "$JNI_DIR" "$DEST_BIN" "$DEST_ETC/tls" "$DEST_MYSQL_SHARE" "$DEST_PHP_EXT"

TX="data/data/com.termux/files/usr"

echo "───────────────────────────────────────────────────────"
echo "  INWEB · Termux fetcher v3 (jniLibs layout)"
echo "  Arch: $ARCH"
echo "───────────────────────────────────────────────────────"

# --- package index ------------------------------------------------------------
echo "  ↓ Resolving package index..."
curl -fsSL "$INDEX_URL" -o "$WORK/Packages.gz"
zcat "$WORK/Packages.gz" > "$WORK/Packages.txt"

pkg_path () {
  awk -v pkg="$1" '
    $1=="Package:"{cur=$2}
    $1=="Filename:" && cur==pkg {print $2; exit}
  ' "$WORK/Packages.txt"
}

fetch_deb () {
  local name="$1" path
  path=$(pkg_path "$name") || true
  [ -z "${path:-}" ] && { echo ""; return 1; }
  curl -fsSL "$BASE/$path" -o "$WORK/$name.deb" || { echo ""; return 1; }
  local unpack="$WORK/unp_$name"
  rm -rf "$unpack"; mkdir -p "$unpack"
  dpkg-deb -x "$WORK/$name.deb" "$unpack" || { echo ""; return 1; }
  echo "$unpack"
}

# --- Phase 1: server packages --------------------------------------------------
declare -A EXEC_MAP=(
  [nginx]=libexec_nginx.so
  [httpd]=libexec_httpd.so
  [caddy]=libexec_caddy.so
  [node]=libexec_node.so
  [php]=libexec_php.so
  [php-fpm]=libexec_php_fpm.so
  [mariadbd]=libexec_mariadbd.so
  [mysql]=libexec_mysql.so
  [mariadb]=libexec_mysql.so
  [mysqladmin]=libexec_mysqladmin.so
  [mariadb-admin]=libexec_mysqladmin.so
  [cloudflared]=libexec_cloudflared.so
)
SCRIPT_BINS=(apachectl mysql_install_db mariadb-install-db mysqld_safe)

PACKAGES=(nginx apache2 caddy nodejs php php-fpm mariadb cloudflared)

in_script_list () { local x="$1"; for s in "${SCRIPT_BINS[@]}"; do [ "$x" = "$s" ] && return 0; done; return 1; }

for name in "${PACKAGES[@]}"; do
  [ "${AUDIT_ONLY:-0}" = "1" ] && break
  echo ""
  echo "▶ $name"
  if ! unpack=$(fetch_deb "$name"); then echo "  ✗ fetch failed — skipping"; continue; fi

  # executables → jniLibs as libexec_*.so
  for src in "$unpack/$TX/bin/"*; do
    base="$(basename "$src")"
    if [ -n "${EXEC_MAP[$base]:-}" ]; then
      cp -L "$src" "$JNI_DIR/${EXEC_MAP[$base]}"
      echo "  🔨 $base → jniLibs/${EXEC_MAP[$base]}"
    elif in_script_list "$base"; then
      cp -L "$src" "$DEST_BIN/$base"; chmod 755 "$DEST_BIN/$base"
      echo "  📜 script $base → assets/bin/"
    fi
  done

  # package's OWN shared libs → jniLibs (exact SONAME names)
  if [ -d "$unpack/$TX/lib" ]; then
    find "$unpack/$TX/lib" -maxdepth 1 -name '*.so*' -exec cp -L {} "$JNI_DIR/" \; 2>/dev/null | true
  fi

  # MariaDB: share/ + plugin engines (plugins also load via dlopen → jniLibs)
  if [ "$name" = "mariadb" ]; then
    [ -d "$unpack/$TX/share/mariadb" ] && { cp -r "$unpack/$TX/share/mariadb/." "$DEST_MYSQL_SHARE/"; echo "  📚 mariadb share/"; }
    [ -d "$unpack/$TX/lib/plugin" ] && {
      find "$unpack/$TX/lib/plugin" -name '*.so*' -exec cp -L {} "$JNI_DIR/" \; 2>/dev/null | true
      echo "  🧩 mariadb plugins → jniLibs"
    }
  fi

  # Apache: modules (mod_*.so → jniLibs) + mime.types (asset)
  if [ "$name" = "apache2" ]; then
    [ -d "$unpack/$TX/libexec/apache2" ] && {
      find "$unpack/$TX/libexec/apache2" -name '*.so' -exec cp -L {} "$JNI_DIR/" \; 2>/dev/null | true
      echo "  🧩 apache modules → jniLibs"
    }
    [ -f "$unpack/$TX/etc/apache2/mime.types" ] && {
      mkdir -p "$ROOT/app/src/main/assets/server_env/apache/conf"
      cp "$unpack/$TX/etc/apache2/mime.types" "$ROOT/app/src/main/assets/server_env/apache/conf/"
    }
  fi

  # PHP extensions dir (opcache etc.)
  if [ "$name" = "php" ] && [ -d "$unpack/$TX/lib/php" ]; then
    find "$unpack/$TX/lib/php" -name '*.so' -exec cp -L {} "$JNI_DIR/" \; 2>/dev/null | true
    echo "  🐘 php extensions → jniLibs"
  fi
done

# --- Phase 2: shared-library packages ------------------------------------------
LIB_PACKAGES=(
  openssl pcre2 zlib libxml2 libsqlite ncurses readline tidy libiconv
  libbz2 libcurl libffi libgmp libicu oniguruma capstone libxslt libzip
  libgcrypt libgpg-error liblzma libc++ libandroid-support libandroid-glob
  c-ares libresolv-wrapper apr apr-util libexpat libuuid libedit
  libngtcp2 libnghttp2 libnghttp3 libssh2 libandroid-posix-semaphore zstd
  ca-certificates
)

if [ "${AUDIT_ONLY:-0}" != "1" ]; then
  echo ""
  echo "──────────────── Phase 2: shared libraries ────────────────"
  for name in "${LIB_PACKAGES[@]}"; do
    echo "▶ $name"
    if unpack=$(fetch_deb "$name"); then
      [ -d "$unpack/$TX/lib" ] && \
        find "$unpack/$TX/lib" -maxdepth 1 -name '*.so*' -exec cp -L {} "$JNI_DIR/" \; 2>/dev/null | true
      if [ "$name" = "ca-certificates" ] && [ -f "$unpack/$TX/etc/tls/cert.pem" ]; then
        cp "$unpack/$TX/etc/tls/cert.pem" "$DEST_ETC/tls/cert.pem"
        echo "  🔒 CA bundle"
      fi
    else
      echo "  ✗ fetch failed — skipping"
    fi
  done

  # --- phpMyAdmin ---
  echo ""
  echo "▶ phpMyAdmin"
  PMA_VERSION="5.2.1"
  PMA_URL="https://files.phpmyadmin.net/phpMyAdmin/${PMA_VERSION}/phpMyAdmin-${PMA_VERSION}-all-languages.zip"
  PMA_DEST="$ROOT/app/src/main/assets/server_env/phpmyadmin"
  mkdir -p "$PMA_DEST"
  curl -fsSL "$PMA_URL" -o "$WORK/pma.zip"
  unzip -q -o "$WORK/pma.zip" -d "$WORK/pma"
  cp -r "$WORK/pma/phpMyAdmin-${PMA_VERSION}-all-languages/." "$PMA_DEST/"
  echo "  ✓ phpMyAdmin $PMA_VERSION"
fi

# Dedupe identical files (hardlink aliases) to save APK space
if command -v hardlink >/dev/null 2>&1; then hardlink -q "$JNI_DIR" 2>/dev/null || true; fi

# --- Phase 4: LINKER CLOSURE AUDIT ---------------------------------------------
echo ""
echo "──────────────── 🔬 Linker closure audit ────────────────"
SYSTEM_LIBS="^(libc\.so|libm\.so|libdl\.so|liblog\.so|libz\.so|libcrypt\.so|libandroid\.so|libmediandk\.so|libjnigraphics\.so|libnativewindow\.so|libEGL\.so|libGLES.*\.so|libOpenSLES\.so|libaaudio\.so|libcamera2ndk\.so|libneuralnetworks\.so|libvulkan\.so|libwebviewchromium_plat_support\.so|ld\.so)$"

declare -A present=()
for f in "$JNI_DIR"/*.so* "$JNI_DIR"/mod_*.so; do [ -e "$f" ] && present["$(basename "$f")"]=1; done

declare -A missing=()
for f in "$JNI_DIR"/*.so*; do
  [ -f "$f" ] || continue
  [ "$(head -c4 "$f" | tr -d '\0' | cut -c2-4)" = "ELF" ] || continue
  while IFS= read -r soname; do
    [ -z "$soname" ] && continue
    echo "$soname" | grep -qE "$SYSTEM_LIBS" && continue
    [ -n "${present[$soname]:-}" ] || missing["$soname"]="${missing[$soname]:-} $(basename "$f")"
  done < <(readelf -d "$f" 2>/dev/null | grep NEEDED | sed -E 's/.*\[(.*)\].*/\1/')
done

if [ "${#missing[@]}" -gt 0 ]; then
  echo "  ❌ UNRESOLVED LIBRARIES:"
  for k in "${!missing[@]}"; do echo "     $k ← needed by:${missing[$k]}"; done
  echo "  Add provider package(s) to LIB_PACKAGES and re-run."
  exit 1
fi
echo "  ✅ All NEEDED libraries resolve — closure complete!"
echo ""
echo "  jniLibs: $(ls "$JNI_DIR" | wc -l) files · $(du -sh "$JNI_DIR" | cut -f1)"
echo "  scripts: $(ls "$DEST_BIN" 2>/dev/null | wc -l)"
echo "  TOTAL assets+libs: $(du -sh "$JNI_DIR" "$ROOT/app/src/main/assets/server_env" | awk '{s+=$1}END{print}s' 2>/dev/null || du -ch "$JNI_DIR" "$ROOT/app/src/main/assets/server_env" | tail -1 | cut -f1)"
echo "───────────────────────────────────────────────────────"
echo "  ✓ Done"
