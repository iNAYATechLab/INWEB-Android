plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

/*
 * INWEB runtime module · node
 *
 * এই ছোট APK-তে শুধু `libexec_node.so` + তার unique shared libs থাকে
 * (scripts/fetch_binaries.sh --split-modules → scripts/split_modules.sh দিয়ে বানানো)।
 * ইনস্টল হলে অ্যাপ সেটার nativeLibraryDir থেকে exec করে — Android 10+ W^X
 * restriction এড়াতে এই ট্রিকই দরকার (app data dir-এ execve ব্লকড, মাপা:
 * error=13)। shared libs (libssl/libicu/…) মূল অ্যাপের lib dir থেকে
 * LD_LIBRARY_PATH দিয়ে resolve হয়, তাই এখানে ডুপ্লিকেট লাগে না।
 *
 * কোনো UI নেই: launcher icon/activity ছাড়াই একটা "resource/native-only APK"।
 */
val modVersion = System.getenv("MODULE_VERSION") ?: "1.0.0-beta.9"
val modCode    = (System.getenv("MODULE_VERSION_CODE") ?: "1").toInt()

android {
    namespace = "com.inweb.app.runtime.node"

    // প্রতিটা module-এর নিজস্ব SDK সেটিং লাগে — :app থেকে inherit হয় না
    compileSdk = 35

    defaultConfig {
        applicationId = "com.inweb.app.runtime.node"
        minSdk = 26
        targetSdk = 35
        versionCode = modCode
        versionName = modVersion
    }

    buildTypes {
        release { isMinifyEnabled = false }
    }

    lint {
        // module APK-র কোনো UI/icon/launchable-activity নেই → lint warning স্বাভাবিক
        abortOnError = false
        warningsAsErrors = false
    }

    packaging {
        jniLibs { useLegacyPackaging = true }   // compressed → module APK ছোট
    }
}

dependencies {
}
