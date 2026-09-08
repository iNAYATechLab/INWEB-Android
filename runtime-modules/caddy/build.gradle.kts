plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

/*
 * INWEB runtime module · caddy
 *
 * ভারী optional বাইনারি (`libexec_caddy.so`) এই ছোট APK-তে বসে; মূল অ্যাপ সেটার
 * nativeLibraryDir থেকে exec করে (Android 10+ W^X: app-data dir-এ execve ব্লকড,
 * মাপা error=13)। শেয়ার্ড লাইব্রেরি মূল অ্যাপের lib dir থেকে LD_LIBRARY_PATH
 * দিয়ে resolve হয়, তাই এখানে ডুপ্লিকেট লাগে না।
 *
 * বিল্ড: scripts/fetch_binaries.sh --split-modules → scripts/split_modules.sh
 *        ./gradlew :runtime-modules:caddy:assembleRelease (MODULE_VERSION* env)
 */
val modVersion = System.getenv("MODULE_VERSION") ?: "0.0.0-dev"
val modCode    = (System.getenv("MODULE_VERSION_CODE") ?: "1").toInt()

// রিলিজ সাইনিং — মূল অ্যাপের keystore-ই reuse হয় (CI secret থেকে env-এ আসে)
val keystorePath = System.getenv("KEYSTORE_PATH")

android {
    namespace = "com.inweb.app.runtime.caddy"

    // module-র নিজস্ব SDK সেটিং দরকার — :app থেকে inherit হয় না
    compileSdk = 35

    defaultConfig {
        applicationId = "com.inweb.app.runtime.caddy"
        minSdk = 26
        targetSdk = 35
        versionCode = modCode
        versionName = modVersion
    }

    if (!keystorePath.isNullOrBlank()) {
        signingConfigs {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (!keystorePath.isNullOrBlank()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    lint {
        // UI/icon/launchable activity নেই — lint warning স্বাভাবিক
        abortOnError = false
        warningsAsErrors = false
    }

    packaging {
        jniLibs { useLegacyPackaging = true }   // compressed → module APK ছোট
    }
}

dependencies {
}
