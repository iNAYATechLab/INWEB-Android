# Apache HTTP Server bundle

INWEB's Apache backend expects:

```
apache/
├── modules/
│   ├── mod_mpm_event.so
│   ├── mod_proxy_fcgi.so
│   ├── mod_rewrite.so
│   └── ... (rest of the shared modules)
└── conf/
    └── mime.types           (Apache's mime type map — optional)
```

The `apache/logs/` folder is created empty at runtime by AssetInstaller.

## Getting the binaries + modules

Run:
```bash
./scripts/fetch_binaries.sh
```
This grabs the Termux `apache2` .deb, extracts `httpd` + `apachectl` into
`bin/`, and pulls the full `/usr/libexec/apache2/` module set into
`apache/modules/`.

## Why does INWEB support both Nginx and Apache?

Some users specifically need `.htaccess` support (WordPress, legacy apps)
which is much easier with Apache. Others prefer Nginx for speed. INWEB
lets you switch in **Settings → Web Server** without touching any config.

Both front-ends proxy PHP to the same PHP-FPM socket on `127.0.0.1:9000`.
