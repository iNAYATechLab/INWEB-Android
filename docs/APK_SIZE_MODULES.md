# 📦 APK Size — On-demand Runtime Modules + Hotfix Channel

> লক্ষ্য: **মূল অ্যাপ ছোট রাখে** (core APK), ভারী বাইনারি (node/caddy/cloudflared/mariadb/phpMyAdmin)
> অ্যাপের ভেতর থেকে ডাউনলোড হবে। ছোট ফিক্স (config/script/JS/PHP/flags) **পুনরায় ইনস্টল ছাড়াই**
> অ্যাপ ওপেন করলেই অ্যাপ্লাই হবে।
>
> এখানে প্রতিটা সংখ্যা **মাপা** — beta.8-এর আসল release APK (324.2 MB) নামিয়ে zip/ELF অ্যানালাইসিস করা হয়েছে।

---

## ১। বর্তমান অ্যানাটমি (beta.8, মাপা)

| অংশ | এন্ট্রি | raw | APK-তে |
|---|---:|---:|---:|
| `libexec_*` (binaries) | 10 | 228.7 MB | **228.7 MB (STORED!)** |
| `*.so` (shared libs) | 169 | 74.0 MB | **74.0 MB (STORED!)** |
| `assets/server_env/*` | 4249 | 52.7 MB | 14.7 MB |
| `classes*.dex` | 2 | 11.5 MB | 4.4 MB |
| res + other | 992 | 1.9 MB | 1.4 MB |
| **মোট** | 5425 | 368.7 MB | **324.2 MB** |

**মূল আবিষ্কার:** ১৮২টা `.so` এন্ট্রি APK-তে **STORED** (uncompressed) ছিল → 302.6 MB খালি বসে ছিল।
`packaging { jniLibs { useLegacyPackaging = true } }` যোগ করলে DEFLATE হয়: **302.6 → 89.1 MB (ratio 0.294)**।
সিমুলেশন (আসল APK-এর সব `lib/arm64-v8a/*` এন্ট্রি deflate-6 করে রিকম্প্রেস): **324.2 MB → 110.6 MB (−65.9%)**।
→ **কমিট করা হয়েছে** (`perf(size): compress jniLibs in APK`)।

| ❌ কাজ করে না | প্রমাণ |
|---|---|
| `strip` দিয়ে সাইজ কমানো | `strip --strip-unneeded` → libexec_node.so 57 MB → 57 MB, mariadbd 29 → 29 MB (**Termux builds আগেই stripped, 0 KB সেভ**) |

---

## ২। কী কী আসলে দরকার? (ELF DT_NEEDED dependency closure)

`readelf -d` দিয়ে পুরো 179টা ফাইলের গ্রাফ বানিয়ে ক্লোজার বের করেছি:

| সেট | ফাইল | raw | DEFLATE |
|---|---:|---:|---:|
| **CORE = nginx + php + php-fpm** | 37 | 113.0 MB | **33.5 MB** |
| + MariaDB (server+client) unique | 5 | 40.5 MB | 9.8 MB |
| + Apache httpd unique | 6 | 1.2 MB | 0.5 MB |
| + Node.js unique | 2 | 57.8 MB | 15.4 MB |
| + Caddy unique | 1 | 54.3 MB | 17.2 MB |
| + cloudflared unique | 1 | 27.5 MB | 9.1 MB |
| ⚠️ কোনো বাইনারির দ্বারাই reachable না (orphan) | 130 | 12.5 MB | 5.8 MB |

গুরুত্বপূর্ণ নোট:
- **php একাই 111.5 MB** টেনে নেয় — কারণ `libicu*` (31.6+3.9+2.1 MB) `libxml2`→php চেইনে, আর `libcapstone.so` (8.8 MB) সরাসরি `libexec_php.so`-র NEEDED।
- `assets/server_env/phpmyadmin` = **47 MB raw / ~9 MB zip** → ডাউনলোডেবল মডিউল হওয়া উচিত (4138 ফাইল!)।
- orphan 130 ফাইলের মধ্যে `mod_*.so` (Apache runtime modules) আছে → **ডিলিট করার আগে** httpd-র dlopen পথ চেক করতে হবে।

### প্রস্তাবিত মডিউল ম্যাপ (module = downloadable, core = bundled)

| মডিউল | কী থাকে | ডাউনলোড সাইজ (xz ~0.23 raw) |
|---|---|---:|
| **core** (APK-তেই) | nginx, php, php-fpm + shared libs + conf templates | ~40 MB APK |
| `mariadb` | mariadbd, mysql, mysqladmin + libs + share/* | ~10 MB |
| `apache` | httpd + mod_*.so + apache conf | ~1 MB |
| `node` | node + icu (if not in core) | ~15 MB |
| `caddy` | caddy | ~17 MB |
| `tunnel` | cloudflared | ~9 MB |
| `tools` | phpMyAdmin + adminer | ~9 MB |

---

## ৩। 🚧 বাধা: ডাউনলোড করা বাইনারি exec করা যায় না (verified)

Android 10+ W^X: `targetSdk ≥ 29` হলে app home (`/data/user/0/<pkg>/files/...`) এর ফাইলে `execve()`
**SELinux `app_data_file:file execute` ডিনাই** → আমাদের beta.6 ডায়াগনস্টিকে হুবহু দেখা গেছে:
`error=13, Permission denied`।exec শুধু **read-only native lib dir** (`/data/app/…/lib/arm64`) বা
APK-mmap করা লাইব্রেরিতে চলে (Android-এর নিজস্ব উত্তর: `extractNativeLibs=true` + jniLibs)।
Termux এটা এড়ায় **targetSdk 28** রেখে, আর আধুনিক workaround = `termux-exec`-এর
`system_linker_exec` (execve instead of the binary → `/system/bin/linker64 <binary>`)।

### বিকল্প চারটা

| # | পদ্ধতি | core APK সাইজ | নতুন ইনস্টল লাগে? | ঝুঁকি |
|---|---|---:|---|---|
| **A** | **মডিউল = আলাদা runtime APK** (PackageManager + `<queries>`, exec = plugin-এর `nativeLibraryDir`) | ~15 MB | মডিউলপ্রতি একবার (OTA-র মতো ইনস্টল প্রম্পট) | 🟢 কম — সব legal, Termux:API স্টাইল |
| **B** | **`linker64` fallback** — direct exec ফেল করলে `/system/bin/linker64 <files/…/bin>` দিয়ে exec | ~15 MB | ❌ নেই | 🟡 ডিভাইস-টেস্টে প্রমাণ করতে হবে (Android 14+ exec-variant issue, path-length issue, `/proc/self/exe` linker path হয়ে যায় → env override লাগে) |
| **C** | **targetSdk 28** (Termux পদ্ধতি) — সব ডাউনলোডেবল, সহজ | ~15 MB | ❌ নেই | 🔴 Play Store অসম্ভব; Android 15/16-এ "built for older version" ওয়ার্নিং; edge-to-edge/নতুন behavior বন্ধুত্বপূর্ণ না |
| **D** | **কোনো ডাউনলোড নয় — দুইটা ফ্লেভার APK**: `INWEB-lite` (nginx+php, 40 MB) ও `INWEB-full` (110 MB) | 40 MB | ফুল ফিচারে যেতে চাইলে ফুল APK ইনস্টল | 🟢 সবচেয়ে সহজ, কিন্তু "অ্যাপের ভেতর থেকে ডাউনলোড" চাওয়া পূরণ হয় না |

> **সুপারিশ:** **B (primary) + A (fallback)** — মডিউল ট্যারবল ডাউনলোড করে `files/modules/<name>/`-এ খুলি,
> exec স্ট্র্যাটেজি: `direct → linker64 → (ব্যর্থ হলে) plugin APK`। ডায়াগনস্টিক পেজে কোনটা কাজ করেছে তা ছেপে দেখাবে।
> `ServerManager.spawn()`-এ এটুকুই বদলাতে হয়: binary path resolve + exec strategy + env (`LD_LIBRARY_PATH`,
> `TERMUX_EXEC__PROC_SELF_EXE`-সদৃশ self-exe override)।

---

## ৪। 🔥 Hotfix Channel — "ইনস্টল ছাড়াই ঠিক হয়ে যাবে"

বাস্তবতা (openly): **Android 10+ এ Kotlin/DEX হট-প্যাচ ব্লকড** (writable dex loading deny) →
তাই "কোনো কোডই ইনস্টল ছাড়া ঠিক হবে না" বললে চলবে না; বরং **যে লজিকটা প্রায়ই বাগজন্য, সেটা ডেটা-ড্রিভেন
লেয়ারে সরিয়ে আনতে হবে**।

| লেয়ার | ইনস্টল ছাড়া ঠিক হয়? | মেকানিজম |
|---|---|---|
| server configs (`nginx.conf`, `php.ini`, `my.cnf`, `httpd.conf`, `Caddyfile`) | ✅ | patch bundle → template রিরাইট → `regenerateConfigs()` |
| runtime scripts (`apachectl`, `mysql_install_db`, `mysqld_safe`) | ✅ | patch bundle → `rewriteTermuxScripts()` আবার চালে |
| **Node dashboard `server.js`**, PHP helpers, `inweb_dashboard/*.{html,js,css}` | ✅ | patch bundle → assets ওভারলে |
| module registry (নতুন ভার্সন/নতুন মডিউল/URL) | ✅ | `modules.json` ফেচ |
| feature flags / kill-switches (কোনো ফিচার বন্ধ করে স্থিতিশীলতা) | ✅ | `flags.json` → `Prefs` cache |
| পোর্ট/টাইমআউট/রিস্টার্ট পলিসি, known-bad version ব্লকলিস্ট | ✅ | `policy.json` |
| **binary/module** আপডেট (nginx/php/mariadb সংস্করণ) | ⚠️ মডিউল রি-ডাউনলোড (APK ইনস্টল নয়, A পদ্ধতিতে শুধু plugin APK) |
| **Kotlin কোড** (UI/Service লজিক) | ❌ | OTA APK আপডেট — About → Check for updates (এক ট্যাপ) |

### কার্যপ্রণালী (design)

```
app open → PatchWorker (WorkManager, one-time, 6h throttle)
   ├─ GET https://api.github.com/repos/iNAYATechLab/INWEB-Android/releases/latest
   ├─ asset: inweb-patches-v<N>.json  { "schema":1, "minApp": "1.0.0-beta.9",
   │        "modules": { "mariadb": {…xz, sha256, size} … },
   │        "files": [ {"path":"conf/nginx.conf.template","sha256":…,"url":…}, … ],
   │        "flags": { "disableMysqlAutoStart": true } }
   ├─ per file: sha256 মিলিয়ে না মিললে → DownloadManager → tmp → verify → atomic replace
   ├─ schema/মিন-ভার্সন মিললে → layout regenerate + config re-render
   └─ স্টেট: files/patches/state.json (applied hash roll) → About → "Patches" সেকশনে লিস্ট
ব্যর্থ হলে: আগের ব্যাকআপে রোলব্যাক (`*.pre-patch`), অ্যাপ ক্র্যাশ করবে না।
```

**নিরাপত্তা (এটা বাধ্যতামূলক):** প্রত্যেকটা ডাউনলোডেড ফাইলে **sha256** (registry-তে), TLS only,
`minApp`/`maxApp` gate, রোলব্যাক ব্যাকআপ, কোনো_executable_ ফাইল `files/`-এ রাখার সময় 0644/0755 সীমিত,
এবং **কোনো ডাইনামিক dex/loading করা হবে না**।

---

## ৫। ইমপ্লিমেন্টেশন ফেজ

| ফেজ | কাজ | ঝুঁকি |
|---|---|---|
| **0** ✅ | jniLibs DEFLATE (কমিটেড) | নিম্ন — 324→~110 MB |
| **1** | `fetch_binaries.sh`-এ `--modules` মোড: per-module tar.xz বানায় + orphan strip (safe ones only); core bundle ছোট করা | মধ্যম |
| **2** | `RuntimeModuleManager.kt` (registry fetch, DownloadManager, sha256, extract-tar via `/system/bin/tar`, verify, enable/disable) + `ServiceType`-এ `moduleId` + Settings → **Modules** স্ক্রিন | মধ্যম |
| **3** | `ServerManager` exec strategy chain (direct → linker64 → plugin) + Diagnostics-এ কোন পথে চলছে তা প্রিন্ট | 🔴 ডিভাইস টেস্ট আবশ্যিক |
| **4** | Patch channel (§4) + About → Patches + `docs/PATCHES.md` (কীভাবে patch রিলিজ করবে) | নিম্ন-মধ্যম |
| **5** | CI: module + patch bundle asset গুলো release-তে attach; core APK-র সাইজ গেট (`if size > 60MB fail`) | নিম্ন |

**Version plan:** ফেজ 0 → `1.0.0-beta.9` (সাইজ + বিদ্যমান ফিক্স), ফেজ 1-2 → `beta.10`, ফেজ 3-4 → `beta.11`।

---

## ৬। স্ট্যাটাস (মাপা, ২০২৬-০৯-0৩)

| ধাপ | কাজ | ফলাফল |
|---|---|---|
| **ফেজ ০** ✅ | `packaging.jniLibs.useLegacyPackaging = true` | beta.9 রিলিজে **110.6 MB** (প্রিডিক্ট 110.6 → হুবহু মিলেছে) · CI লগ: `📊 Size: 111M` · সব 179টা `lib/arm64-v8a` entry এখন `Defl:N` |
| **ফেজ ০.৫** ✅ | CI সাইন চেক ফিক্স | পুরনো `META-INF/MANIFEST.MF` grep সাইন্ডেড beta.9-কে-ও "NOT SIGNED" বলেছিল → `scripts/check_apk_sig.sh` (v1 + Signing Block parse) যোগ, **পজিটিভ/নেগেটিভ দুই দিকে টেস্ট করা** |
| **ফেজ ১** ✅ | `scripts/split_modules.sh` + `audit_closure.sh` + `test_module_split.sh` | সেলফ-টেস্ট: **PASS 8 · FAIL 0** |
| **ফেজ ২** ✅ (কোড) | `RuntimeModule.kt` + `RuntimeModuleManager.kt` + `<queries>` + `settings.gradle.kts` + ৩টা module scaffold + ৪ লোকেল string | resolution core-first, module fallback → কোনো regression নেই (module ইনস্টল না থাকলে আগের আচরণ) |
| ফেজ ২.৫ ⏭ | CI-তে `--split-modules` অন + module APK release asset + Settings → Modules UI | বাকি |
| ফেজ ৩ ⏭ | Hotfix/patch channel (conf/script/JS/PHP/flags) | বাকি |
