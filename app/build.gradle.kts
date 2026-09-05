plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// ─── Load release signing config ─────────────────────────────────
// Priority:
//   1. Environment variables (used by CI — GitHub Actions secrets)
//   2. keystore.properties file (used for local signed builds)
//   3. If neither present, release APK will be unsigned (still builds).
import java.util.Properties
import java.io.FileInputStream

val releaseSigning: Map<String, String>? = run {
    val envStore = System.getenv("KEYSTORE_PATH")
    val envPass  = System.getenv("KEYSTORE_PASSWORD")
    val envAlias = System.getenv("KEY_ALIAS")
    val envKey   = System.getenv("KEY_PASSWORD")
    if (!envStore.isNullOrBlank() && !envPass.isNullOrBlank()
        && !envAlias.isNullOrBlank() && !envKey.isNullOrBlank()) {
        mapOf(
            "storeFile"     to envStore,
            "storePassword" to envPass,
            "keyAlias"      to envAlias,
            "keyPassword"   to envKey,
        )
    } else {
        val f = rootProject.file("keystore.properties")
        if (f.exists()) {
            val p = Properties().apply { load(FileInputStream(f)) }
            mapOf(
                "storeFile"     to (p["storeFile"] as String),
                "storePassword" to (p["storePassword"] as String),
                "keyAlias"      to (p["keyAlias"] as String),
                "keyPassword"   to (p["keyPassword"] as String),
            )
        } else null
    }
}

android {
    // INWEB brand identifier — used consistently for Kotlin package,
    // Gradle namespace, and Play Store applicationId.
    namespace = "com.inweb.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.inweb.app"
        minSdk = 26
        targetSdk = 35
        // ── INWEB Beta Versioning Scheme ─────────────────────────
        // versionName format:  MAJOR.MINOR.PATCH-beta.N
        //   → beta builds era: 1.0.0-beta.1, 1.0.0-beta.2, ...
        //   → release candidate: 1.0.0-rc.1, ...
        //   → stable:            1.0.0
        // versionCode formula: MAJOR*10000 + MINOR*100 + PATCH (+ betaNo as offset of first beta)
        //   → 1.0.0-beta.1 = 10000 ... 1.0.0-beta.2 = 10001 ... 1.0.0 stable = 10100
        // See docs/VERSIONING.md for the full policy.
        versionCode = 10009
        versionName = "1.0.0-beta.10"
    }

    signingConfigs {
        releaseSigning?.let { sig ->
            create("release") {
                storeFile     = file(sig["storeFile"]!!)
                storePassword = sig["storePassword"]
                keyAlias      = sig["keyAlias"]
                keyPassword   = sig["keyPassword"]
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Only apply signing if credentials were found; otherwise
            // release APK is built unsigned (must be signed later).
            if (releaseSigning != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    buildFeatures {
        viewBinding = true
        buildConfig = true   // UpdateChecker reads BuildConfig.VERSION_NAME
    }

    // 📦 SIZE FIX — 324.2 MB → ~110 MB (measured on the real beta.8 APK)
    // সমস্যা: AGP 8.5.2 + extractNativeLibs="true" হলেও jniLibs ডিফল্টভাবে
    // STORED (uncompressed) প্যাকেজ হয় → আমাদের 179টা .so (302.6 MB) APK-তে
    // খালি চোখেই 302.6 MB জায়গা নিচ্ছিল। useLegacyPackaging = true দিলে সেগুলো
    // DEFLATE হয় (302.6 MB → 89.1 MB) এবং ইনস্টলে খুলে বসে, exec এখনও legal।
    // বোনাস: compressed libs মানে 16 KB page-size alignment রিকোয়ারমেন্ট এড়ানো
    // (Android 15/16 16KB-page ডিভাইসে uncompressed+unaligned .so ইনস্টল ফেল করে)।
    // ট্রেডঅফ: ইনস্টলের পর ডিভাইসে ডিস্ক ~+303 MB লাগে (extracted কপি)।
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    // Text/config assets: compress করা যাবে, তবে 1:1 কপি করার সুবিধার জন্য
    // কনফিগ এক্সটেনশনগুলো noCompress-এ রাখা হয়েছে। ".so" এখান থেকে সরানো
    // হয়েছে — সেটা jniLibs compression ব্লক করে দিচ্ছিল না, শুধু কনফিউজিং।
    androidResources {
        noCompress += listOf("conf", "template", "ini")
    }

    // Per-app languages: keep only these locales in the APK.
    defaultConfig.resourceConfigurations += listOf("en", "bn", "ar", "hi", "ur")
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.lifecycle:lifecycle-service:2.8.4")
    implementation("androidx.localbroadcastmanager:localbroadcastmanager:1.1.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Preferences (for bind mode, custom port, etc.)
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // QR code generation — ZXing core (pure Java, no Play Services)
    implementation("com.google.zxing:core:3.5.3")

    // WorkManager for periodic DDNS updates (survives process death, Doze-friendly).
    implementation("androidx.work:work-runtime-ktx:2.9.1")
}
