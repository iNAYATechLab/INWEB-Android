# 🌐 INWEB

![INWEB logo](branding/inweb_logo.png)

**A Powerful Web Server that fits in your Pocket.**
_আপনার Android ফোনটাই এখন Nginx / Apache / LiteSpeed / Caddy / Node + PHP + MariaDB সার্ভার_

![Platform](https://img.shields.io/badge/platform-Android%208.0%2B-3DDC84?style=flat-square&logo=android&logoColor=white)
![Kotlin](https://img.shields.io/badge/kotlin-1.9-7F52FF?style=flat-square&logo=kotlin&logoColor=white)
![License](https://img.shields.io/badge/license-MIT-14B8A6?style=flat-square)
[![Latest Release](https://img.shields.io/github/v/release/InayaTechLabs/INWEB-Android?style=flat-square&color=14B8A6&label=release)](https://github.com/InayaTechLabs/INWEB-Android/releases/latest)
[![Android CI](https://github.com/InayaTechLabs/INWEB-Android/actions/workflows/android-ci.yml/badge.svg)](https://github.com/InayaTechLabs/INWEB-Android/actions/workflows/android-ci.yml)

📖 [English](#-english) · [বাংলা](#-বাংলা) · [🍎 iOS Companion](https://github.com/InayaTechLabs/INWEB-iOS)

### 🚀 Quick download

📱 **[⬇ Download latest APK](https://github.com/InayaTechLabs/INWEB-Android/releases/latest)** — 91 MB · Full LAMP stack bundled · Android 8.0+

🍎 **[⬇ Download iOS IPA](https://github.com/InayaTechLabs/INWEB-iOS/releases/latest)** — 273 KB unsigned · iOS 16+ (see [INSTALL-NO-MAC guide](https://github.com/InayaTechLabs/INWEB-iOS/blob/main/docs/INSTALL-NO-MAC.md))

---

## 🇬🇧 English

### 💡 What is INWEB?

**INWEB** (from Arabic **إناية** *Inaya* — "care" — combined with *Web*) is a full-stack local web server that runs **natively on any Android phone**. No root, no VPS, no cloud bill. Spin up **Nginx + PHP-FPM + MariaDB + phpMyAdmin** in one tap and start building — anywhere, anytime, offline-first.

> Built for the Bangladeshi & Muslim developer community 💚 — but useful to anyone who wants a real dev server in their pocket.

### ✨ Highlights

| | Feature | Details |
|---|---|---|
| ⚡ | **5 web engines** | Nginx · Apache · OpenLiteSpeed · Caddy · Node.js — switch anytime |
| 🐘 | **Full LAMP stack** | PHP-FPM 8.x · MariaDB 10.x · phpMyAdmin 5.2 |
| 🌍 | **Custom DNS system** | Virtual Hosts + Local Hosts VPN + LAN DNS (`mysite.local` works everywhere) |
| 🔐 | **HTTPS + Security** | Self-signed SSL · HTTP Basic Auth · IP allow/deny · rate-limiting |
| 🔥 | **Live Reload** | Pure-Kotlin WebSocket server (port 35729) + recursive file-watcher + auto script injection |
| 🌐 | **Dynamic DNS** | DuckDNS · No-IP · Cloudflare · INAYA — auto-updated by WorkManager |
| 🕋 | **Islamic APIs** | Prayer times · Qibla direction · Hijri date · Zakat calc — all offline PHP endpoints |
| 📝 | **Live code editor** | Split-view editor + preview, syntax highlighting, dirty-tracking |
| 📊 | **Server cluster dashboard** | Per-engine sparklines (CPU/RAM), rich status cards, log preview |
| 🔌 | **REST Control API** | Token-authenticated `/api/inweb/*` on port 8181 (used by Web PWA + iOS) |
| 📲 | **Web PWA dashboard** | Zero-dep vanilla HTML/CSS/JS control panel served from the app |
| 🍎 | **iOS companion app** | Native SwiftUI remote — [InayaTechLabs/INWEB-iOS](https://github.com/InayaTechLabs/INWEB-iOS) |
| 🌐 | **5 languages** | English · বাংলা · العربية · हिन्दी · اردو |
| ♿ | **Zero external HTTP deps** | Pure Java stdlib — no OkHttp, no Retrofit — minimal APK size |

### 🏗 Architecture

```
┌──────────────────────────────────────────────────────────────────┐
│                       INWEB Android App                          │
│                                                                  │
│  ┌────────────┐   ┌──────────────┐   ┌──────────────┐            │
│  │  Nginx /   │   │  PHP-FPM     │   │  MariaDB     │            │
│  │  Apache /  │◄──┤  127.0.0.1   │──►│  127.0.0.1   │            │
│  │  Caddy ... │   │       :9000  │   │       :3306  │            │
│  └─────┬──────┘   └──────────────┘   └──────────────┘            │
│        │ :8080 / :8443                                           │
│        ▼                                                         │
│  ┌────────────┐   ┌──────────────┐   ┌──────────────┐            │
│  │  Custom    │   │  LiveReload  │   │  REST API    │            │
│  │  DNS/VPN   │   │  WebSocket   │   │  :8181       │            │
│  │  :5353     │   │  :35729      │   │  (Bearer)    │            │
│  └────────────┘   └──────────────┘   └──────┬───────┘            │
│                                             │                    │
└─────────────────────────────────────────────┼────────────────────┘
                                              │
                        ┌─────────────────────┼─────────────────────┐
                        ▼                     ▼                     ▼
                 ┌─────────────┐     ┌─────────────┐        ┌─────────────┐
                 │  Web PWA    │     │  iOS App    │        │  Any REST   │
                 │  (browser)  │     │  (SwiftUI)  │        │  client     │
                 └─────────────┘     └─────────────┘        └─────────────┘
```

### 🚀 Quick start

```bash
# 1. Clone the repo
git clone https://github.com/your-org/INWEB-Android.git
cd INWEB-Android

# 2. Fetch native binaries (nginx, apache, php-fpm, mariadb, ...)
bash scripts/fetch_binaries.sh

# 3. Open in Android Studio → Build → Run
#    OR from CLI:
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

**On the phone:** open **INWEB** → tap **START** → visit `http://localhost:8080` in Chrome. Done. 🎉

### 📁 Project structure

```
INWEB-Android/
├── app/
│   ├── build.gradle.kts                     applicationId = com.inweb.app
│   └── src/main/
│       ├── AndroidManifest.xml              20 activities · VPN service · Widget · Tile
│       ├── java/com/inweb/app/              77 Kotlin files across 18 packages
│       │   ├── api/                         REST API server + router
│       │   ├── dashboard/                   Dashboard data + prayer calc
│       │   ├── ddns/                        Dynamic DNS providers + worker
│       │   ├── dns/                         LAN DNS server + VPN service
│       │   ├── framework/                   Site templates (5 starters)
│       │   ├── islamic/                     Islamic API wrappers
│       │   ├── livereload/                  WebSocket + file-watcher
│       │   ├── net/                         Networking + QR generator
│       │   ├── security/                    APR1 hash + IP filter + rate-limit
│       │   ├── services/                    Server engines abstraction
│       │   ├── ui/                          20 activities across sub-packages
│       │   ├── util/                        Prefs + FileUtils + helpers
│       │   ├── vhost/                       Virtual-host renderer + import/export
│       │   └── widget/                      Home-screen widget + Quick Settings tile
│       ├── assets/
│       │   ├── server_env/                  binaries · configs · php.ini
│       │   ├── inweb_dashboard/             5 Web PWA files (vanilla JS)
│       │   ├── islamic_apis/                5 PHP endpoints (offline)
│       │   └── livereload/                  client-side livereload.js
│       └── res/
│           ├── layout/                      30 XML layouts (shared includes)
│           ├── values/, values-bn/,
│           ├── values-ar/, values-hi/,
│           ├── values-ur/                   5 locales
│           └── xml/locales_config.xml
├── branding/                                logos + wordmark + Play Store assets
├── play_store/                              store listing metadata
├── scripts/fetch_binaries.sh                Termux .deb fetcher
├── screenshots/                             18 in-app previews
└── PROJECT_STATUS.md                        detailed feature-by-feature log
```

### 🔌 Ports used

| Port | Service | Note |
|---|---|---|
| **8080** | HTTP (default) | change in Settings → Ports |
| **8443** | HTTPS | self-signed cert auto-generated |
| **3306** | MariaDB | localhost only by default |
| **9000** | PHP-FPM | FastCGI backend |
| **5353** | Custom DNS | unprivileged (use 53 with root) |
| **35729** | LiveReload | industry standard WebSocket |
| **8181** | REST Control API | Bearer-token auth |

### 🌐 Companion clients

| Client | Path | Stack |
|---|---|---|
| 📲 **Web PWA** | Installed to `www/inweb-dashboard/` on the phone | vanilla HTML/CSS/JS |
| 🍎 **iOS app** | [InayaTechLabs/INWEB-iOS](https://github.com/InayaTechLabs/INWEB-iOS) | SwiftUI, iOS 16+ |

Both talk to the Android app's REST API on port **8181** using a Bearer token you copy from **Settings → API Access**.

### 🎨 Brand palette

| Role | Hex | |
|---|---|---|
| Background | `#0B1410` | deep forest green |
| Surface | `#132821` | card |
| Accent | `#14B8A6` | teal 500 |
| Success | `#10B981` | emerald |
| Warning | `#F59E0B` | amber |
| Danger | `#EF4444` | red |
| Text primary | `#F5F7FA` | |
| Text secondary | `#9AB5AA` | |

### 📄 License

MIT © INWEB — see [`LICENSE`](LICENSE).

---

## 🇧🇩 বাংলা

### 💡 INWEB কী?

**INWEB** (আরবি **إناية** *ইনায়া* — "যত্ন" + *Web*) হলো একটি পূর্ণাঙ্গ লোকাল ওয়েব সার্ভার যেটা আপনার **Android ফোনে সরাসরি** চলে। কোনো root লাগবে না, কোনো VPS কিনতে হবে না, কোনো cloud bill নাই। এক ট্যাপে **Nginx + PHP-FPM + MariaDB + phpMyAdmin** চালু — যেখানে-যখন খুশি, অফলাইনেও।

> বাংলাদেশি ও মুসলিম ডেভেলপার কমিউনিটির জন্য যত্ন নিয়ে বানানো 💚 — কিন্তু যেকোনো ডেভেলপারের পকেটে সত্যিকারের dev server এর জন্য কাজে লাগবে।

### ✨ প্রধান ফিচার

| | ফিচার | বিস্তারিত |
|---|---|---|
| ⚡ | **৫টা web engine** | Nginx · Apache · OpenLiteSpeed · Caddy · Node.js — যেকোনো সময় switch |
| 🐘 | **পুরো LAMP স্ট্যাক** | PHP-FPM 8.x · MariaDB 10.x · phpMyAdmin 5.2 |
| 🌍 | **Custom DNS সিস্টেম** | Virtual Hosts + Local Hosts VPN + LAN DNS (`mysite.local` কাজ করবে সবখানে) |
| 🔐 | **HTTPS + Security** | Self-signed SSL · HTTP Basic Auth · IP allow/deny · rate-limiting |
| 🔥 | **Live Reload** | Pure-Kotlin WebSocket server (port 35729) + recursive file-watcher + auto script inject |
| 🌐 | **Dynamic DNS** | DuckDNS · No-IP · Cloudflare · INAYA — WorkManager দিয়ে auto update |
| 🕋 | **Islamic APIs** | নামাজের সময় · কিবলা · হিজরি তারিখ · যাকাত ক্যালকুলেটর — সব offline PHP |
| 📝 | **Live Code Editor** | Split-view editor + preview, syntax highlighting, dirty-tracking |
| 📊 | **Server Cluster Dashboard** | প্রতিটা engine এর CPU/RAM sparkline, rich status card, log preview |
| 🔌 | **REST Control API** | Token auth সহ `/api/inweb/*` port 8181-এ (Web PWA + iOS ব্যবহার করে) |
| 📲 | **Web PWA dashboard** | Zero-dependency vanilla JS control panel — app এর ভেতর থেকেই serve হয় |
| 🍎 | **iOS companion app** | Native SwiftUI remote — দেখুন [InayaTechLabs/INWEB-iOS](https://github.com/InayaTechLabs/INWEB-iOS) |
| 🌐 | **৫টা ভাষা** | English · বাংলা · العربية · हिन्दी · اردو |
| ♿ | **শূন্য external HTTP dependency** | শুধু Java stdlib — OkHttp/Retrofit নাই — APK size ছোট |

### 🚀 কীভাবে শুরু করবেন?

```bash
# ১. Repo clone করুন
git clone https://github.com/your-org/INWEB-Android.git
cd INWEB-Android

# ২. Native binary গুলা download করুন (nginx, apache, php-fpm ইত্যাদি)
bash scripts/fetch_binaries.sh

# ৩. Android Studio-তে open করুন → Build → Run
#    অথবা CLI থেকে:
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

**ফোনে:** **INWEB** app খুলুন → **START** চাপুন → Chrome-এ `http://localhost:8080` লিখুন। শেষ! 🎉

### 📱 কীভাবে ব্যবহার করবেন?

| ধাপ | কী করবেন |
|---|---|
| **১. START চাপুন** | Dashboard এর সবুজ START button — সার্ভার চালু হবে, notification আসবে |
| **২. Site বানান** | **More → Sites → +** — নতুন virtual host, template থেকে choose করুন |
| **৩. Code লিখুন** | **More → Files → Edit** — built-in editor দিয়ে PHP/HTML edit করুন |
| **৪. Preview দেখুন** | Dashboard এ URL চাপুন — browser খুলবে, LiveReload অন থাকলে save করলেই auto refresh |
| **৫. অন্য ডিভাইস থেকে access** | **Share → QR code** — WiFi এর অন্য ফোন/ল্যাপটপ থেকে scan করুন |

### 🔌 কোন port গুলা ব্যবহৃত হয়?

| Port | সার্ভিস | নোট |
|---|---|---|
| **8080** | HTTP (default) | Settings → Ports থেকে change করা যায় |
| **8443** | HTTPS | self-signed cert auto তৈরি হয় |
| **3306** | MariaDB | default-এ শুধু localhost |
| **9000** | PHP-FPM | FastCGI backend |
| **5353** | Custom DNS | unprivileged (root থাকলে 53 use করুন) |
| **35729** | LiveReload | industry standard WebSocket |
| **8181** | REST Control API | Bearer token auth |

### 🎨 Design সিস্টেম

সব page একই pattern follow করে — shared `inweb_header.xml` header + `inweb_bottom_nav.xml` bottom nav + 14dp rounded corner card + emerald/teal accent + deep forest green background। কারণ **INWEB** এর pehchan (identity) থাকতে হবে consistent 💚

**Bottom nav:** 🏠 Home · ⚙️ Services · 📊 Logs · 📤 Share · ⋯ More

**More menu:** Sites · Local Hosts · Files · Frameworks · Islamic APIs · Security · Dynamic DNS · Settings

### 🏗 iOS + Web সাথে কীভাবে কাজ করে?

```
     Android ফোন (আসল সার্ভার)
           │
           ├── REST API :8181 (Bearer token)
           │
     ┌─────┴──────┬──────────────┐
     ▼            ▼              ▼
   Web PWA     iOS App      অন্য যেকোনো
  (browser)   (SwiftUI)    REST client
```

- Android app = **আসল web server host** (Nginx/PHP/MariaDB সব চালায়)
- iOS + Web = **remote control** (শুধু REST API দিয়ে control করে)
- iPhone-এ Nginx চলতে পারে না (App Store restriction) — তাই iOS শুধু client

### 🤝 Contributing

1. Fork the repo
2. Create your feature branch (`git checkout -b feature/awesome`)
3. Commit করুন (`git commit -m 'Add awesome feature'`)
4. Push করুন (`git push origin feature/awesome`)
5. Pull Request খুলুন

কোড লেখার সময় দয়া করে:
- **KDoc / comments** দিন (Bangla বা English দুটাই OK)
- Design consistency মানুন (`INWEB.SectionLabel`, `@color/surface`, 14dp corner)
- 5টা locale-এই strings add করুন (`values/`, `values-bn/`, `values-ar/`, `values-hi/`, `values-ur/`)
- `com.example.*` reference দিবেন না — শুধু `com.inweb.app.*`

### 📞 Support

কোনো সমস্যা? [Issues](https://github.com/your-org/INWEB-Android/issues) খুলুন অথবা email: **support@inweb.app**

---

Made with 💚 in Bangladesh · **INWEB** · *"A Powerful Web Server that fits in your Pocket."*

