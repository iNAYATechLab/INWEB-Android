# 📦 INWEB Runtime Modules — বড় বাইনারি অ্যাপের ভেতর থেকে ডাউনলোড

মূল APK 324.2 MB ছিল, তার **302.6 MB (93%) শুধু bundled `.so`** (মাপা: beta.8 APK
এন্ট্রি-বাই-এন্ট্রি অ্যানালাইসিস)। এই ডিরের module APK-গুলো সেই ভারী-but-optional
অংশটা বহন করে — অ্যাপ সেগুলো ইনস্টলড কিনা `PackageManager` দিয়ে বুঝে নেয়।

═══════════════════════════════════════════
## কেন সাধারণ "download → files/ → run" করা যায় না
═══════════════════════════════════════════

Android 10+ (targetSdk ≥ 29) W^X SELinux rule: **অ্যাপের নিজস্ব data ডিরেক্টরির ফাইলে
`execve()` ব্লকড**। এটা আমাদের নিজের beta.6 ডায়াগনস্টিকে মাপা:

```
Cannot run program "/data/user/0/com.inweb.app/files/server_env/bin/nginx" ... error=13, Permission denied
```

exec শুধু **read-only native lib dir** (`/data/app/~~…/com.inweb.app-…/lib/arm64`) থেকে চলে
(সেটাই `jniLibs/` + `android:extractNativeLibs="true"` এর কাজ)। তাই runtime-এ নামানো
বাইনারি চালাতে হলে সেটাও **একটা ইনস্টলড APK-র ভেতরে** থাকতেই হবে → module = APK।

═══════════════════════════════════════════
## আর্কিটেকচার
═══════════════════════════════════════════

```
app/src/main/jniLibs/arm64-v8a/     ← CORE (APK-তেই): nginx, apache, php, php-fpm, mariadb
runtime-modules/node/src/main/jniLibs/arm64-v8a/     ← libexec_node.so   (≈15 MB)
runtime-modules/caddy/src/main/jniLibs/arm64-v8a/    ← libexec_caddy.so  (≈17 MB)
runtime-modules/tunnel/src/main/jniLibs/arm64-v8a/   ← libexec_cloudflared.so (≈9 MB)
                    │
                    ▼  GitHub Release asset হিসেবে প্রকাশিত
        অ্যাপ → DownloadManager → installer → ইনস্টলড
                    │
   RuntimeModuleManager.installed() → getPackageInfo(pkg).applicationInfo.nativeLibraryDir
   RuntimeModuleManager.resolveExecutable() → File(nativeLibDir, "libexec_x.so")
                    ▼
   ServerManager.spawn(): LD_LIBRARY_PATH = core lib dir + প্রতিটা ইনস্টলড module-এর lib dir
   (শেয়ার্ড লাইব্রেরি core থেকেই resolve হয় → module APK-তে ডুপ্লিকেট লাগে না)
```

| ফাইল | ভূমিকা |
|---|---|
| `app/src/main/java/com/inweb/app/runtime/RuntimeModule.kt` | module registry (id, package, asset নাম, এক্সিকিউটেবল) |
| `app/src/main/java/com/inweb/app/runtime/RuntimeModuleManager.kt` | discovery · resolve · download+install · diagnostics report |
| `app/src/main/AndroidManifest.xml` → `<queries>` | Android 11+ package visibility (ছাড়া getPackageInfo fail) |
| `scripts/split_modules.sh` | core jniLibs → module jniLibs (module-only lib কপি করে, shared রাখে) |
| `scripts/audit_closure.sh` | **union** of core+module dirs এর linker closure চেক |
| `scripts/test_module_split.sh` | ফিক্সচার-ভিত্তিক সেলফ-টেস্ট (আমাদের ৮টা assert: PASS 8 / FAIL 0) |
| `scripts/check_apk_sig.sh` | v1 **না** পেলেও v2/v3 Signing Block দেখে (CI false-negative ফিক্স) |

═══════════════════════════════════════════
## বিল্ড
═══════════════════════════════════════════

```bash
# 1) সব বাইনারি ফেচ (এখনকার মতোই)
bash scripts/fetch_binaries.sh --split-modules
#    → শেষে স্বয়ংক্রিয়ভাবে split_modules.sh + union closure audit চলে

# 2) core + module APK
./gradlew :app:assembleRelease
MODULE_VERSION=1.0.0-beta.10 MODULE_VERSION_CODE=10009 \
  ./gradlew :runtime-modules:node:assembleRelease \
            :runtime-modules:caddy:assembleRelease \
            :runtime-modules:tunnel:assembleRelease
```

CI-তে (`.github/workflows/android-ci.yml`) এখনও `--split-modules` **অফ** — অর্থাৎ
সব কিছু core-তেই বান্ডল হয় (beta.9 = 110.6 MB)। মডিউল চালু করার দিন:
workflow-এ ফেচ স্টেপে ফ্ল্যাগ যোগ + ৩টা module APK release asset-এ attach, এবং
asset নাম রেজিস্ট্রির `assetPattern` মিলিয়ে দিতে হবে
(`INWEB-runtime-node-{ver}.apk`, …)।

═══════════════════════════════════════════
## নতুন module যোগ করার চেকলিস্ট
═══════════════════════════════════════════

1. `RuntimeModule.kt`-এ এন্ট্রি (id / packageName / assetPattern / executables / approxMb)
2. `runtime-modules/<id>/build.gradle.kts` + `src/main/AndroidManifest.xml` (এই ডিরের নকল করো)
3. `settings.gradle.kts`-এর `listOf("node","caddy","tunnel")` → id যোগ করো
4. `scripts/split_modules.sh`-এর `MODULE_BIN` ম্যাপে যোগ করো
5. app manifest-এর `<queries>`-এ `<package android:name="com.inweb.app.runtime.<id>" />`
6. `ServerManager`/`TunnelManager`-এ `moduleBin("libexec_<x>.so")` দিয়ে resolve করো
7. `bash scripts/test_module_split.sh` → FAIL 0
8. CI-তে module APK attach + closure audit union ডিরে

═══════════════════════════════════════════
## এখনো বাকি (Step 3+)
═══════════════════════════════════════════

| # | কাজ |
|---|---|
| ১ | **Settings → Modules UI** (ডাউনলোড বাটন, সাইজ, ইনস্টলড ভার্সন, হটার) |
| ২ | CI-তে `--split-modules` অন + module APK release asset + সাইজ গেট (core ≤ 60 MB) |
| ৩ | Hotfix channel: `modules.json` + conf/script/JS/PHP ওভারলে (ইনস্টল ছাড়াই অ্যাপ-ওপেনে অ্যাপ্লাই) |
| ৪ | ফার্স্ট-রানে "install recommended modules" অনবোর্ডিং প্রম্পট |

> ⚠️ Module UI-তে download শুরু করার আগে `REQUEST_INSTALL_PACKAGES` (unknown sources) অনুমতি
> লাগে — `RuntimeModuleManager.startDownload()` সেটা গেট করে settings-এ পাঠায়।
