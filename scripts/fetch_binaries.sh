#!/usr/bin/env bash
#
# INWEB — Termux binary fetcher
# =============================
# Downloads pre-built ARM64 binaries (nginx, php, php-fpm, mariadbd,
# cloudflared) from the Termux .deb repository and drops them into
# app/src/main/assets/server_env/ so the app can bundle them on build.
#
# Usage:
#   ./scripts/fetch_binaries.sh
#
# Requirements: curl, dpkg-deb OR ar+tar, xz-utils
#
# NOTE: All binaries are GPL/BSD licensed by their upstreams. Termux
# repackages them; check licence terms before distributing your APK.

set -euo pipefail

REPO="https://packages.termux.dev/apt/termux-main/pool/main"
ARCH="aarch64"
WORK="$(mktemp -d)"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DEST_BIN="$ROOT/app/src/main/assets/server_env/bin"
DEST_TUNNEL="$ROOT/app/src/main/assets/server_env/tunnel"
DEST_MYSQL_SHARE="$ROOT/app/src/main/assets/server_env/mysql/share"

mkdir -p "$DEST_BIN" "$DEST_TUNNEL" "$DEST_MYSQL_SHARE"

echo "───────────────────────────────────────────────────────"
echo "  INWEB · Termux binary fetcher"
echo "  Arch:  $ARCH"
echo "  Dest:  $DEST_BIN"
echo "───────────────────────────────────────────────────────"

# ---------------------------------------------------------------------------
# Package list to fetch. Format: "pkgname:desc:target_folder:copy_globs"
# ---------------------------------------------------------------------------
PACKAGES=(
  "n/nginx:Nginx web server:$DEST_BIN:usr/bin/nginx"
  "a/apache2:Apache HTTP Server:$DEST_BIN:usr/bin/httpd usr/bin/apachectl"
  "c/caddy:Caddy web server (auto-HTTPS):$DEST_BIN:usr/bin/caddy"
  "n/nodejs:Node.js runtime:$DEST_BIN:usr/bin/node usr/bin/npm"
  "p/php:PHP CLI + FPM:$DEST_BIN:usr/bin/php usr/bin/php-fpm"
  "m/mariadb:MariaDB server + client:$DEST_BIN:usr/bin/mariadbd usr/bin/mysql usr/bin/mysqladmin usr/bin/mysql_install_db"
  "c/cloudflared:Cloudflare Tunnel:$DEST_TUNNEL:usr/bin/cloudflared"
)

# OpenLiteSpeed isn't in Termux — user must supply their own aarch64 build.
# See https://openlitespeed.org/kb/build-openlitespeed-from-source/

# Apache also ships with .so modules that must live in
# apache/modules/ inside the app. We copy the whole modules dir separately.
DEST_APACHE_MODULES="$ROOT/app/src/main/assets/server_env/apache/modules"

fetch_one () {
  local rel="$1"; local desc="$2"; local dest="$3"; shift 3
  local globs="$*"
  local pkgname="${rel##*/}"

  # Termux publishes URLs like:  <repo>/<letter>/<pkg>/<pkg>_<version>_aarch64.deb
  # We enumerate to find the latest .deb because versions change frequently.
  echo ""
  echo "▶ $desc  ($pkgname)"
  local list_url="$REPO/$rel/"
  local latest_deb
  latest_deb=$(curl -sSL "$list_url" \
      | grep -oE "${pkgname}_[^\"']*_${ARCH}\.deb" \
      | sort -V | tail -1 || true)

  if [ -z "${latest_deb:-}" ]; then
    echo "  ✗ Could not locate $pkgname .deb at $list_url"
    echo "    Skipping — you'll need to fetch this one manually."
    return
  fi

  local url="$list_url$latest_deb"
  echo "  ↓ $latest_deb"
  local tmpdeb="$WORK/$latest_deb"
  curl -fsSL "$url" -o "$tmpdeb"

  # Unpack .deb → data.tar.xz → files
  local unpack="$WORK/${pkgname}_unpacked"
  mkdir -p "$unpack"
  if command -v dpkg-deb >/dev/null 2>&1; then
    dpkg-deb -x "$tmpdeb" "$unpack"
  else
    (cd "$WORK" && ar x "$tmpdeb")
    # data.tar.xz or data.tar.zst
    if [ -f "$WORK/data.tar.xz" ];  then tar -xJf "$WORK/data.tar.xz"  -C "$unpack"
    elif [ -f "$WORK/data.tar.zst" ]; then
        command -v zstd >/dev/null || { echo "  ✗ Need zstd"; return; }
        zstd -dc "$WORK/data.tar.zst" | tar -x -C "$unpack"
    else echo "  ✗ Unknown data archive"; return; fi
  fi

  # Copy requested globs
  for g in $globs; do
    if [ -f "$unpack/$g" ]; then
      cp -v "$unpack/$g" "$dest/"
      chmod 755 "$dest/$(basename "$g")"
    else
      echo "  ✗ Missing $g inside deb"
    fi
  done

  # MariaDB extras: share/ (error messages, seed SQL)
  if [ "$pkgname" = "mariadb" ] && [ -d "$unpack/usr/share/mariadb" ]; then
    echo "  📚 Copying MariaDB share/ →  $DEST_MYSQL_SHARE"
    cp -r "$unpack/usr/share/mariadb/." "$DEST_MYSQL_SHARE/"
  fi

  # Apache extras: modules/ (.so shared modules) + conf/mime.types
  if [ "$pkgname" = "apache2" ]; then
    mkdir -p "$DEST_APACHE_MODULES"
    if [ -d "$unpack/usr/libexec/apache2" ]; then
      echo "  🧩 Copying Apache modules →  $DEST_APACHE_MODULES"
      cp -r "$unpack/usr/libexec/apache2/." "$DEST_APACHE_MODULES/"
    fi
    if [ -f "$unpack/usr/etc/apache2/mime.types" ]; then
      mkdir -p "$ROOT/app/src/main/assets/server_env/apache/conf"
      cp "$unpack/usr/etc/apache2/mime.types" \
         "$ROOT/app/src/main/assets/server_env/apache/conf/"
    fi
  fi
}

for line in "${PACKAGES[@]}"; do
  IFS=':' read -r rel desc dest globs <<< "$line"
  fetch_one "$rel" "$desc" "$dest" "$globs"
done

# ---------------------------------------------------------------------------
# phpMyAdmin  (Composer-free tarball from official downloads)
# ---------------------------------------------------------------------------
echo ""
echo "▶ phpMyAdmin (official all-languages)"
PMA_VERSION="5.2.1"
PMA_URL="https://files.phpmyadmin.net/phpMyAdmin/${PMA_VERSION}/phpMyAdmin-${PMA_VERSION}-all-languages.zip"
PMA_DEST="$ROOT/app/src/main/assets/server_env/phpmyadmin"
mkdir -p "$PMA_DEST"
curl -fsSL "$PMA_URL" -o "$WORK/pma.zip"
unzip -q -o "$WORK/pma.zip" -d "$WORK/pma"
# Flatten the top-level directory
cp -r "$WORK/pma/phpMyAdmin-${PMA_VERSION}-all-languages/." "$PMA_DEST/"
echo "  ✓ phpMyAdmin $PMA_VERSION → $PMA_DEST"

echo ""
echo "───────────────────────────────────────────────────────"
echo "  ✓ Done."
echo ""
echo "  Now build the APK:"
echo "    ./gradlew assembleDebug"
echo "───────────────────────────────────────────────────────"
