pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "INWEB-Android"
include(":app")

// 📦 Optional runtime modules (downloadable APKs) — ভারী বাইনারি মূল অ্যাপ থেকে আলাদা
// রাখার জন্য এগুলো আলাদা ছোট APK হিসেবে বিল্ড হয়; ইনস্টল করছে ইউজার অ্যাপ থেকেই।
listOf("node", "caddy", "tunnel").forEach { id ->
    include(":runtime-modules:$id")
}
