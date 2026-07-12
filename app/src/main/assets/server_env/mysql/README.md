# MariaDB / MySQL bundle folder

This directory is where INWEB expects the **MySQL error-message bundle** and
**seed system tables** (the `share/` directory shipped with MariaDB).

Recommended contents (from a MariaDB/MySQL install for `aarch64`):

```
mysql/
├── share/
│   ├── english/errmsg.sys
│   ├── charsets/
│   ├── mysql_system_tables.sql
│   ├── mysql_system_tables_data.sql
│   └── ...
└── (data/ is created empty at runtime; do NOT ship it)
```

The `data/` folder inside `filesDir/server_env/mysql/data/` is created and
populated on first launch via `mysql_install_db` (or `mysqld --initialize-insecure`).

## Binaries

The MariaDB binaries themselves (`mariadbd`, `mysql`, `mysqladmin`,
`mysql_install_db`) belong in `assets/server_env/bin/` — not here.

## Where to get these

- Build MariaDB with the Android NDK for `aarch64`.
- Extract from Termux's `mariadb` package
  (`data/data/com.termux/files/usr/share/mariadb/`).
- Any other pre-built distribution respecting the GPLv2 licence.
