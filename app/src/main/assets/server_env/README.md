# INWEB — server_env/ layout

INWEB ships **empty** binary slots. Before building the APK, populate:

```
assets/server_env/
├── bin/                       ← ALL executables live here
│   ├── nginx
│   ├── httpd, apachectl       ← Apache HTTP Server
│   ├── lshttpd                ← OpenLiteSpeed (⚠️ user-supplied, see below)
│   ├── caddy                  ← Caddy (single static binary)
│   ├── node, npm              ← Node.js runtime
│   ├── php, php-fpm           ← PHP + FastCGI process manager
│   └── mariadbd, mysql, ...   ← MariaDB
│
├── apache/
│   ├── modules/*.so           ← Apache shared modules
│   └── conf/mime.types
│
├── conf/                      ← rendered from *.template on first run
│   ├── nginx.conf.template
│   ├── httpd.conf.template    (Apache)
│   ├── litespeed.conf.template
│   ├── vhconf.conf.template   (LiteSpeed virtual host)
│   ├── Caddyfile.template
│   ├── php-fpm.conf.template
│   └── my.cnf.template        (MariaDB)
│
├── mysql/
│   └── share/                 ← MariaDB seed SQL + error msgs
│
├── node/
│   └── server.js              ← bundled zero-dep static server
│
├── php/
│   └── php.ini
│
├── phpmyadmin/                ← unpacked phpMyAdmin
│
└── tunnel/
    └── cloudflared            ← Cloudflare Tunnel
```

## Auto-fetch what's available

The bundled script grabs the easy ones from Termux's official APT repo:

```bash
./scripts/fetch_binaries.sh
```
This fills in: **nginx, apache2, caddy, nodejs, php, mariadb, cloudflared**
plus phpMyAdmin from phpmyadmin.net.

## Manually-supplied binaries

Two engines aren't in Termux and need your attention:

### 🔥 OpenLiteSpeed (`lshttpd`)

OpenLiteSpeed doesn't ship a mobile binary. You have two paths:

1. **Cross-compile from source** — clone
   [litespeedtech/openlitespeed](https://github.com/litespeedtech/openlitespeed)
   and build with the Android NDK targeting `aarch64-linux-android21`.
2. **Skip it entirely** — most users will be happy with Nginx / Apache / Caddy.
   The UI will show a friendly error if the binary is missing when they try
   to start it.

### 🟢 Node.js (`node`)

Node has semi-official static Linux builds — but standard glibc ones don't
work on Android's bionic libc. Two options:

1. Grab a **musl-linked static build** from
   [unofficial-builds.nodejs.org](https://unofficial-builds.nodejs.org/download/release/)
   (look for `linux-arm64-musl`).
2. Use Termux's `nodejs` package (bionic-linked, works out of the box) —
   this is what `fetch_binaries.sh` does.

## Which engine when?

| Engine | Great for | Not great for |
|---|---|---|
| **Nginx** | Everything — default choice | If you need `.htaccess` |
| **Apache** | WordPress, `.htaccess`, mod_rewrite | Raw speed |
| **OpenLiteSpeed** | High-traffic, LSCache-enabled sites | Simplicity |
| **Caddy** | Small deploys, auto-HTTPS demos | Legacy PHP apps |
| **Node.js** | Static sites, JS-only, React/Vue builds | PHP (no PHP support) |
