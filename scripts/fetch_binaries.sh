#!/usr/bin/env bash
#
# INWEB — Termux binary + library fetcher (v2.1, index-driven)
# =============================================================
# Downloads pre-built ARM64 binaries (nginx, apache, php, mariadb, node,
# caddy, cloudflared) from the Termux repository AND — the crucial v2
# addition — every shared library (.so) those binaries link against, so
# they actually RUN on-device instead of dying with "library not found".
#
# Package file locations are resolved through the official Packages index
# (dists/stable/main/binary-aarch64/Packages.gz) — robust against pool
# directory naming quirks.
#
# Output under app/src/main/assets/server_env/:
#   bin/            executables (nginx, httpd, mariadbd, php, ...)
#   lib/            shared libraries (libssl, libpcre2, libicu, ...)  ← NEW
#   lib/plugin/     MariaDB storage-engine plugins                   ← NEW
#   etc/tls/        CA bundle for curl/openssl/cloudflared           ← NEW
#   mysql/share     MariaDB error messages + seed SQL
#   apache/modules  Apache .so modules + conf/mime.types
#   php/extensions  PHP loadable extensions (opcache, ...)
#   phpmyadmin/     full phpMyAdmin
#
# Ends with a LINKER CLOSURE AUDIT: every NEEDED entry of every shipped
# ELF must resolve to a bundled .so or an Android system library; the
# script FAILS otherwise, so CI can never ship a broken APK again.
#
# Usage:
#   ./scripts/fetch_binaries.sh
#   AUDIT_ONLY=1 ./scripts/fetch_binaries.sh   (skip downloads, audit only)
#
set -euo pipefail

BASE="https://packages.termux.dev/apt/termux-main"
INDEX_URL="$BASE/dists/stable/main/binary-aarch64/Packages.gz"
ARCH="aarch64"
WORK="${INWEB_FETCH_WORK:-$(mktemp -d)}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DEST_BIN="$ROOT/app/src/main/assets/server_env/bin"
DEST_LIB="$ROOT/app/src/main/assets/server_env/lib"
DEST_ETC="$ROOT/app/src/main/assets/server_env/etc"
DEST_TUNNEL="$ROOT/app/src/main/assets/server_env/tunnel"
DEST_PHP_EXT="$ROOT/app/src/main/assets/server_env/php"
DEST_MYSQL_SHARE="$ROOT/app/src/main/assets/server_env/mysql/share"
DEST_APACHE_MODULES="$ROOT/app/src/main/assets/server_env/apache/modules"

mkdir -p "$DEST_BIN" "$DEST_LIB" "$DEST_ETC/tls" "$DEST_TUNNEL" \
         "$DEST_MYSQL_SHARE" "$DEST_APACHE_MODULES" "$DEST_PHP_EXT"

TX="data/data/com.termux/files/usr"

echo "───────────────────────────────────────────────────────"
echo "  INWEB · Termux binary + library fetcher (v2.1)"
echo "  Arch:  $ARCH"
echo "───────────────────────────────────────────────────────"

# ---------------------------------------------------------------------------
# Package index
# ---------------------------------------------------------------------------
echo "  ↓ Resolving package index..."
curl -fsSL "$INDEX_URL" -o "$WORK/Packages.gz"
zcat "$WORK/Packages.gz" > "$WORK/Packages.txt"

pkg_path () {
  # pkg_path <name> → pool path (or empty)
  awk -v pkg="$1" '
    $1=="Package:"{cur=$2}
    $1=="Filename:" && cur==pkg {print $2; exit}
  ' "$WORK/Packages.txt"
}

# ---------------------------------------------------------------------------
# Phase 1 — server executables. "pkgname:desc:dest_dir:copy_globs"
# ---------------------------------------------------------------------------
PACKAGES=(
  "nginx:Nginx web server:$DEST_BIN:$TX/bin/nginx"
  "apache2:Apache HTTP Server:$DEST_BIN:$TX/bin/httpd $TX/bin/apachectl"
  "caddy:Caddy web server (auto-HTTPS):$DEST_BIN:$TX/bin/caddy"
  "nodejs:Node.js runtime:$DEST_BIN:$TX/bin/node $TX/bin/npm"
  "php:PHP CLI:$DEST_BIN:$TX/bin/php"
  "php-fpm:PHP-FPM:$DEST_BIN:$TX/bin/php-fpm"
  "mariadb:MariaDB server + MySQL-compatible client:$DEST_BIN:$TX/bin/mariadbd $TX/bin/mysql $TX/bin/mariadb $TX/bin/mysqladmin $TX/bin/mariadb-admin $TX/bin/mariadb-install-db $TX/bin/mysql_install_db $TX/bin/mysqld_safe $TX/bin/mariadb-safe"
  "cloudflared:Cloudflare Tunnel:$DEST_TUNNEL:$TX/bin/cloudflared"
)

# ---------------------------------------------------------------------------
# Phase 2 — shared-library packages (empirically determined 2026-09 via
# readelf NEEDED audit on every shipped binary + transitive closure).
# ---------------------------------------------------------------------------
LIB_PACKAGES=(
  "openssl:OpenSSL 3 (libssl/libcrypto)"
  "pcre2:PCRE2 regex (nginx/mariadb/php)"
  "zlib:zlib (libz.so.1)"
  "libxml2:libxml2 (PHP XML)"
  "libsqlite:SQLite3 (PHP PDO/sqlite, node)"
  "ncurses:ncurses (readline dependency)"
  "readline:GNU readline (PHP interactive)"
  "tidy:HTML Tidy (PHP tidy ext)"
  "libiconv:iconv (PHP charset)"
  "libbz2:bzip2 lib (PHP bz2 ext)"
  "libcurl:cURL client lib (PHP curl ext)"
  "libffi:libffi (PHP FFI)"
  "libgmp:GMP math (PHP gmp ext)"
  "libicu:ICU (PHP intl, node i18n) incl. libicudata"
  "oniguruma:Oniguruma regex (PHP mbstring)"
  "capstone:Capstone (PHP ext)"
  "libxslt:XSLT (PHP xsl ext)"
  "libzip:libzip (PHP zip ext)"
  "libgcrypt:libgcrypt (libxslt dependency)"
  "libgpg-error:libgpg-error (libgcrypt dependency)"
  "liblzma:liblzma (libxml2 dependency)"
  "libc++:libc++_shared (mariadb/node/php runtime)"
  "libandroid-support:libandroid-support (Termux compat shims)"
  "libandroid-glob:libandroid-glob (nginx glob())"
  "c-ares:c-ares DNS resolver (node)"
  "libresolv-wrapper:resolv wrapper (PHP)"
  "apr:APR (Apache httpd)"
  "apr-util:APR-util (Apache httpd)"
  "libexpat:expat (apr-util dependency)"
  "libuuid:libuuid (apr dependency)"
  "libedit:libedit (mysql client line editing)"
  "libnghttp2:nghttp2 (Apache mod_http2)"
  "zstd:zstd (mariadb compression)"
  "libngtcp2:ngtcp2 (curl HTTP/3)"
  "libnghttp3:nghttp3 (curl HTTP/3)"
  "libssh2:libssh2 (curl SFTP)"
  "libandroid-posix-semaphore:posix semaphore shim (libuuid dep)"
  "ca-certificates:CA root bundle (HTTPS)"
)

fetch_deb () {
  # fetch_deb <pkgname> → echoes unpack dir, returns 1 on failure
  local name="$1"
  local path
  path=$(pkg_path "$name")
  if [ -z "$path" ]; then echo ""; return 1; fi

  local tmpdeb="$WORK/$name.deb"
  curl -fsSL "$BASE/$path" -o "$tmpdeb" || { echo ""; return 1; }
  local unpack="$WORK/unp_$name"
  rm -rf "$unpack"; mkdir -p "$unpack"
  if command -v dpkg-deb >/dev/null 2>&1; then
    dpkg-deb -x "$tmpdeb" "$unpack" || { echo ""; return 1; }
  else
    (cd "$WORK" && ar x "$tmpdeb" 2>/dev/null)
    if [ -f "$WORK/data.tar.xz" ]; then tar -xJf "$WORK/data.tar.xz" -C "$unpack"
    elif [ -f "$WORK/data.tar.zst" ]; then
      command -v zstd >/dev/null || { echo ""; return 1; }
      zstd -dc "$WORK/data.tar.zst" | tar -x -C "$unpack" || { echo ""; return 1; }
    else echo ""; return 1; fi
    rm -f "$WORK"/data.tar.* "$WORK"/control.tar.* "$WORK"/debian-binary "$WORK/$name.deb"
  fi
  echo "$unpack"
}

fetch_one () {
  local name="$1"; local desc="$2"; local dest="$3"; shift 3
  local globs="$*"

  echo ""
  echo "▶ $desc  ($name)"
  local unpack
  if ! unpack=$(fetch_deb "$name"); then
    echo "  ✗ Could not fetch $name — skipping"
    return
  fi

  for g in $globs; do
    if [ -L "$unpack/$g" ] || [ -f "$unpack/$g" ]; then
      cp -v -L "$unpack/$g" "$dest/" 2>/dev/null | sed "s|$WORK/||"
      chmod 755 "$dest/$(basename "$g")" 2>/dev/null || true
    else
      echo "  ✗ Missing $g inside deb"
    fi
  done

  # Always harvest this package's own top-level shared libs
  if [ -d "$unpack/$TX/lib" ]; then
    find "$unpack/$TX/lib" -maxdepth 1 -name '*.so*' -exec cp -P {} "$DEST_LIB/" \; 2>/dev/null | sed "s|$WORK/||" | head -20
  fi

  # MariaDB extras
  if [ "$name" = "mariadb" ]; then
    if [ -d "$unpack/$TX/share/mariadb" ]; then
      echo "  📚 MariaDB share/ → assets/mysql/share"
      cp -r "$unpack/$TX/share/mariadb/." "$DEST_MYSQL_SHARE/"
    fi
    if [ -d "$unpack/$TX/lib/plugin" ]; then
      mkdir -p "$DEST_LIB/plugin"
      echo "  🧩 MariaDB plugins → assets/lib/plugin"
      find "$unpack/$TX/lib/plugin" -name '*.so*' -exec cp -P {} "$DEST_LIB/plugin/" \; 2>/dev/null | sed "s|$WORK/||" | head -10
    fi
  fi

  # Apache extras
  if [ "$name" = "apache2" ]; then
    if [ -d "$unpack/$TX/libexec/apache2" ]; then
      echo "  🧩 Apache modules → assets/apache/modules"
      cp -r -L "$unpack/$TX/libexec/apache2/." "$DEST_APACHE_MODULES/"
    fi
    if [ -f "$unpack/$TX/etc/apache2/mime.types" ]; then
      mkdir -p "$ROOT/app/src/main/assets/server_env/apache/conf"
      cp "$unpack/$TX/etc/apache2/mime.types" "$ROOT/app/src/main/assets/server_env/apache/conf/"
    fi
  fi

  # Node extras — bundled npm lives in lib/node_modules
  if [ "$name" = "nodejs" ]; then
    if [ -d "$unpack/$TX/lib/node_modules/npm" ]; then
      mkdir -p "$DEST_LIB/node_modules"
      echo "  📦 npm → assets/lib/node_modules"
      cp -r -L "$unpack/$TX/lib/node_modules/npm" "$DEST_LIB/node_modules/" 2>/dev/null || true
    fi
  fi

  # PHP extensions dir
  if [ "$name" = "php" ] && [ -d "$unpack/$TX/lib/php" ]; then
    echo "  🐘 PHP extensions → assets/php/extensions"
    cp -r -L "$unpack/$TX/lib/php" "$DEST_PHP_EXT/extensions-tmp" 2>/dev/null || true
    mv "$DEST_PHP_EXT/extensions-tmp" "$DEST_PHP_EXT/extensions" 2>/dev/null || true
  fi
}

if [ "${AUDIT_ONLY:-0}" != "1" ]; then
  for line in "${PACKAGES[@]}"; do
    IFS=':' read -r rel desc dest globs <<< "$line"
    fetch_one "$rel" "$desc" "$dest" "$globs"
  done

  echo ""
  echo "───────────────────────────────────────────────────────"
  echo "  Phase 2: shared libraries"
  echo "───────────────────────────────────────────────────────"
  for line in "${LIB_PACKAGES[@]}"; do
    IFS=':' read -r name desc <<< "$line"
    echo ""
    echo "▶ $desc ($name)"
    if unpack=$(fetch_deb "$name"); then
      [ -d "$unpack/$TX/lib" ] && \
        find "$unpack/$TX/lib" -maxdepth 1 -name '*.so*' -exec cp -P {} "$DEST_LIB/" \; 2>/dev/null | sed "s|$WORK/||"
      if [ "$name" = "ca-certificates" ] && [ -f "$unpack/$TX/etc/tls/cert.pem" ]; then
        cp "$unpack/$TX/etc/tls/cert.pem" "$DEST_ETC/tls/cert.pem"
        echo "  🔒 CA bundle → assets/etc/tls/cert.pem"
      fi
    else
      echo "  ✗ fetch failed — skipping"
    fi
  done

  # ---------------------------------------------------------------------
  # phpMyAdmin (Composer-free tarball from official downloads)
  # ---------------------------------------------------------------------
  echo ""
  echo "▶ phpMyAdmin (official all-languages)"
  PMA_VERSION="5.2.1"
  PMA_URL="https://files.phpmyadmin.net/phpMyAdmin/${PMA_VERSION}/phpMyAdmin-${PMA_VERSION}-all-languages.zip"
  PMA_DEST="$ROOT/app/src/main/assets/server_env/phpmyadmin"
  mkdir -p "$PMA_DEST"
  curl -fsSL "$PMA_URL" -o "$WORK/pma.zip"
  unzip -q -o "$WORK/pma.zip" -d "$WORK/pma"
  cp -r "$WORK/pma/phpMyAdmin-${PMA_VERSION}-all-languages/." "$PMA_DEST/"
  echo "  ✓ phpMyAdmin $PMA_VERSION → assets/phpmyadmin"
fi

# ---------------------------------------------------------------------------
# LINKER CLOSURE AUDIT
# ---------------------------------------------------------------------------
echo ""
echo "───────────────────────────────────────────────────────"
echo "  🔗 Symlink consolidation (APK assets can't hold links)"
echo "───────────────────────────────────────────────────────"
LINKS_MANIFEST="$ROOT/app/src/main/assets/server_env/lib/links.txt"
mkdir -p "$DEST_LIB"
: > "$LINKS_MANIFEST"
# Replace every symlink with a manifest line:  reldir|name|target
# The app recreates real symlinks at install time via Os.symlink().
while IFS= read -r f; do
  tgt=$(readlink "$f") || continue
  dir="$(dirname "$f")"
  base="$(basename "$f")"
  rel="${dir#$ROOT/app/src/main/assets/server_env/}"
  # absolute termux prefix targets → keep basename in same dir
  case "$tgt" in /*) tgt="$(basename "$tgt")" ;; esac
  echo "$rel|$base|$tgt" >> "$LINKS_MANIFEST"
  rm "$f"
  echo "  🔗 $rel/$base → $tgt"
done < <(find "$DEST_BIN" "$DEST_LIB" "$DEST_LIB/plugin" "$DEST_TUNNEL" -maxdepth 2 -type l 2>/dev/null)
echo "  $(wc -l < "$LINKS_MANIFEST") symlinks recorded"

echo ""
echo "───────────────────────────────────────────────────────"
echo "  🔬 Linker closure audit"
echo "───────────────────────────────────────────────────────"
SYSTEM_LIBS="^(libc\.so|libm\.so|libdl\.so|liblog\.so|libz\.so|libcrypt\.so|libandroid\.so|libmediandk\.so|libjnigraphics\.so|libnativewindow\.so|libEGL\.so|libGLES.*\.so|libOpenSLES\.so|libaaudio\.so|libcamera2ndk\.so|libneuralnetworks\.so|libvulkan\.so|libwebviewchromium_plat_support\.so|ld\.so)$"

mapfile -t all_files < <(find "$DEST_BIN" "$DEST_TUNNEL" "$DEST_LIB" "$DEST_APACHE_MODULES" \( -type f -o -type l \) 2>/dev/null | sort -u)

missing_total=0
declare -A missing_map=()
declare -A link_names=()
[ -f "$LINKS_MANIFEST" ] && while IFS='|' read -r rel name tgt; do
  link_names["$name"]=1
done < "$LINKS_MANIFEST"

for f in "${all_files[@]}"; do
  [ -f "$f" ] || continue
  # ELF magic check — skip shell scripts
  [ "$(head -c4 "$f" | tr -d '\0' | cut -c2-4)" = "ELF" ] || continue
  while IFS= read -r soname; do
    [ -z "$soname" ] && continue
    if echo "$soname" | grep -qE "$SYSTEM_LIBS"; then continue; fi
    if [ -n "${link_names[$soname]:-}" ]; then continue; fi
    found=0
    for g in "$DEST_LIB" "$DEST_LIB/plugin" "$DEST_BIN" "$DEST_APACHE_MODULES"; do
      [ -e "$g/$soname" ] && { found=1; break; }
    done
    [ "$found" = "0" ] && { missing_map["$soname"]="${missing_map[$soname]:-} $(basename "$f")"; missing_total=$((missing_total+1)); }
  done < <(readelf -d "$f" 2>/dev/null | grep NEEDED | sed -E 's/.*\[(.*)\].*/\1/')
done

if [ "${#missing_map[@]}" -gt 0 ]; then
  echo ""
  echo "  ❌ UNRESOLVED LIBRARIES:"
  for k in "${!missing_map[@]}"; do
    echo "     $k  ← needed by:${missing_map[$k]}"
  done
  echo ""
  echo "  Add the providing Termux package(s) to LIB_PACKAGES and re-run."
  exit 1
else
  echo "  ✅ All NEEDED libraries resolve — closure complete!"
fi

echo ""
echo "  Binaries: $(find "$DEST_BIN" -type f | wc -l) · Libs: $(find "$DEST_LIB" -maxdepth 1 -name '*.so*' | wc -l)"
echo "  Total bundle size: $(du -sh "$ROOT/app/src/main/assets/server_env" | cut -f1)"
echo "───────────────────────────────────────────────────────"
echo "  ✓ Done. Now build the APK:"
echo "    ./gradlew assembleRelease"
echo "───────────────────────────────────────────────────────"
