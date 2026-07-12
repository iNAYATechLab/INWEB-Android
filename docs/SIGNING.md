# 🔐 INWEB — APK Signing Guide

Complete guide to sign your INWEB APK for distribution.

📖 **Table of contents:**
1. [Why sign?](#-why-sign)
2. [Bangla explanation (বাংলা)](#-বাংলা-ব্যাখ্যা)
3. [Local signing (one-time)](#-local-signing-one-time)
4. [Automated CI signing](#-automated-ci-signing-play-store-ready)
5. [Play Store deployment](#-play-store-deployment)
6. [Troubleshooting](#-troubleshooting)

---

## 🤔 Why sign?

Android **requires** every APK to be signed with a cryptographic key before it can be installed. There are 3 types:

| Type | Signed with | Where used | Play Store? |
|---|---|:---:|:---:|
| **Debug APK** | Android's default `debug.keystore` (universal) | Development, sideload testing | ❌ No |
| **Unsigned Release APK** | Nothing | ⚠️ Cannot install anywhere | ❌ No |
| **Signed Release APK** | Your private keystore | Play Store, sideload production | ✅ Yes |

⚠️ **CRITICAL:** Once you publish to Play Store with a keystore, you **must** use the same keystore for all future updates. **Lose the keystore = lose the ability to update your app.** Back it up in 3 places!

---

## 🇧🇩 বাংলা ব্যাখ্যা

### সাইনিং কী এবং কেন?

Android-এ যেকোনো APK install করতে গেলে সেটাকে **cryptographic key** দিয়ে **sign** করতে হয়। এটা প্রমাণ করে যে APK কে বানিয়েছে (তা যাচাইয়ের জন্য)।

### ৩ ধরনের APK

| ধরন | কী দিয়ে signed | কোথায় use | Play Store? |
|---|---|:---:|:---:|
| 🧪 **Debug APK** | Android-এর default keystore (সবার একই) | Testing, sideload | ❌ না |
| ⚠️ **Unsigned Release APK** | কিছুই না | ⚠️ install করা যায় না | ❌ না |
| ✅ **Signed Release APK** | আপনার private keystore | Play Store, distribution | ✅ হ্যাঁ |

### ⚠️ গুরুত্বপূর্ণ

Play Store-এ একবার publish করার পর একই keystore দিয়েই **সব future update** sign করতে হবে। **Keystore হারালে = app update করা যাবে না।** তাই ৩ জায়গায় backup রাখুন!

---

## 🏠 Local Signing (one-time)

Simplest path for testing signed builds locally.

### Step 1: Generate keystore

```bash
# From INWEB-Android/ root directory
bash scripts/generate_keystore.sh
```

Or manually:

```bash
keytool -genkey -v \
  -keystore inweb-release.jks \
  -keyalg RSA \
  -keysize 2048 \
  -validity 10000 \
  -alias inweb
```

You'll be prompted for:
- **Keystore password** (choose strong, remember it!)
- **Key password** (can be same as keystore password)
- **Name/organization/city/etc.** (any value)

**Output:** `inweb-release.jks` (keep this SECRET, never commit)

### Step 2: Create `keystore.properties`

Create `keystore.properties` in the project root:

```properties
storeFile=../inweb-release.jks
storePassword=YOUR_KEYSTORE_PASSWORD
keyAlias=inweb
keyPassword=YOUR_KEY_PASSWORD
```

⚠️ **Add to `.gitignore`** (already done):
```
keystore.properties
*.jks
```

### Step 3: Update `app/build.gradle.kts`

Add signing config:

```kotlin
// Top of file
import java.util.Properties
import java.io.FileInputStream

android {
    // ... existing config ...

    signingConfigs {
        create("release") {
            val keystorePropertiesFile = rootProject.file("keystore.properties")
            if (keystorePropertiesFile.exists()) {
                val props = Properties().apply {
                    load(FileInputStream(keystorePropertiesFile))
                }
                storeFile = file(props["storeFile"] as String)
                storePassword = props["storePassword"] as String
                keyAlias = props["keyAlias"] as String
                keyPassword = props["keyPassword"] as String
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
            // ... existing release config
        }
    }
}
```

### Step 4: Build signed APK

```bash
./gradlew assembleRelease
```

**Output:** `app/build/outputs/apk/release/app-release.apk` (signed!)

Or Play Store bundle:
```bash
./gradlew bundleRelease
# Output: app/build/outputs/bundle/release/app-release.aab
```

---

## 🤖 Automated CI Signing (Play Store ready)

This is what production teams do — CI signs on every tag push.

### Step 1: Convert keystore to base64

```bash
base64 -w 0 inweb-release.jks > keystore.b64
cat keystore.b64
```

Copy the entire base64 string (very long).

### Step 2: Add GitHub Secrets

Go to your repo: **Settings → Secrets and variables → Actions → New repository secret**

Add these 4 secrets:

| Secret name | Value |
|---|---|
| `KEYSTORE_BASE64` | The base64 string from step 1 |
| `KEYSTORE_PASSWORD` | Your keystore password |
| `KEY_PASSWORD` | Your key password |
| `KEY_ALIAS` | `inweb` (or whatever alias you chose) |

### Step 3: CI workflow (already configured!)

The `.github/workflows/android-ci.yml` workflow now automatically:
1. Decodes `KEYSTORE_BASE64` back into a `.jks` file
2. Passes credentials to Gradle via env vars
3. Signs the release APK
4. Uploads signed APK to GitHub Release

### Step 4: Push a version tag

```bash
git tag v1.0.1
git push origin v1.0.1
```

CI will build and publish a **signed** release APK automatically! 🎉

---

## 🏪 Play Store Deployment

Once you have a signed AAB:

1. **Go to Play Console:** https://play.google.com/console
2. **Create app** (one-time):
   - App name: `INWEB — Web Server in your Pocket`
   - Language: English (US) + বাংলা
   - Type: App
   - Free / Paid
3. **Fill store listing** — use content from [`play_store/listing.md`](../play_store/listing.md)
4. **Upload feature graphic:** [`branding/play_store_feature_v2.png`](../branding/play_store_feature_v2.png)
5. **Upload screenshots** from [`screenshots/`](../screenshots/) folder
6. **Privacy policy:** Host [`play_store/privacy_policy.md`](../play_store/privacy_policy.md) and provide URL
7. **Content rating:** Answer questionnaire → likely "Everyone"
8. **Upload signed AAB:** From `app/build/outputs/bundle/release/app-release.aab`
9. **Submit for review** — usually approved in 1-7 days

---

## 🐛 Troubleshooting

### "Keystore was tampered with, or password was incorrect"

- Password wrong
- OR: keystore file corrupted (re-download / regenerate base64)

### "App not installed" on Android

If you're upgrading from a differently-signed APK (e.g. debug → release), **uninstall the old one first**.

### "Cannot upload — signing certificate does not match previous upload"

You've used a different keystore than the first Play Store upload. Play Store enforces same-key for updates. **Retrieve your original keystore** (from backups) or use **Play App Signing** (recommended for new apps).

### Enable Play App Signing (recommended)

Instead of managing your own upload keystore forever, let Google manage the app signing key. Go to Play Console → **Setup → App integrity → App signing** → Enable Google-managed key. You'll only need to protect an **upload key** (Google re-signs with the app key).

---

## 🔒 Security Best Practices

| Practice | Why |
|---|---|
| 🔐 Use **strong passwords** (12+ chars, mixed) | Weak = easy to bruteforce |
| 📤 **3-2-1 backup rule** | 3 copies, 2 media types, 1 off-site |
| 📵 **Never commit** keystore/passwords to git | Public keystore = anyone can publish updates as you |
| 🔄 **Rotate passwords** if leaked | Even if keystore itself is safe |
| 🚫 **Never share** keystore in Slack/email plaintext | Use secure vaults (1Password, Bitwarden Send) |
| ✅ **Enable Play App Signing** | Google keeps ultimate key safe |

---

Made with 💚 in Bangladesh · **INWEB**
