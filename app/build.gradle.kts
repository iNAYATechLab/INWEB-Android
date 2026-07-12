plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
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
        versionCode = 1
        versionName = "1.0.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    buildFeatures { viewBinding = true }

    // Do NOT compress binary/config assets so they can be copied 1:1.
    androidResources {
        noCompress += listOf("conf", "template", "ini", "so")
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
