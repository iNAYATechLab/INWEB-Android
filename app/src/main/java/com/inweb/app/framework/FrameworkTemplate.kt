package com.inweb.app.framework

/**
 * Descriptor for a one-click framework install.
 *
 * INWEB downloads [downloadUrl] (a ZIP), unpacks it into the web root under
 * [targetSubdir], then optionally runs [postInstall] hooks to render config
 * files (like WordPress `wp-config.php`).
 */
data class FrameworkTemplate(
    val id: String,
    val displayName: String,
    val tagline: String,
    val downloadUrl: String,
    val downloadSizeMB: Int,      // approximate; shown in UI
    val targetSubdir: String,     // relative to docRoot; e.g. "wordpress"
    val needsDatabase: Boolean,
    val requiresPhp: Boolean,
    val iconRes: Int,             // R.drawable.*
    val postInstall: PostInstall = PostInstall.NONE
) {
    enum class PostInstall {
        NONE,
        WORDPRESS_WP_CONFIG,      // render wp-config.php from wp-config-sample.php
        LARAVEL_ENV_KEY,          // rename .env.example → .env, generate APP_KEY
    }

    companion object {
        val ALL: List<FrameworkTemplate> = listOf(
            FrameworkTemplate(
                id             = "wordpress",
                displayName    = "WordPress",
                tagline        = "The world's most popular CMS. Install, log in, blog.",
                downloadUrl    = "https://wordpress.org/latest.zip",
                downloadSizeMB = 22,
                targetSubdir   = "wordpress",
                needsDatabase  = true,
                requiresPhp    = true,
                iconRes        = com.inweb.app.R.drawable.ic_fw_wordpress,
                postInstall    = PostInstall.WORDPRESS_WP_CONFIG
            ),
            FrameworkTemplate(
                id             = "laravel_starter",
                displayName    = "Laravel Starter",
                tagline        = "Bare Laravel 11 skeleton, no DB required to boot.",
                // A slim skeleton snapshot; user runs composer install later if needed.
                downloadUrl    = "https://github.com/laravel/laravel/archive/refs/heads/11.x.zip",
                downloadSizeMB = 3,
                targetSubdir   = "laravel",
                needsDatabase  = false,
                requiresPhp    = true,
                iconRes        = com.inweb.app.R.drawable.ic_fw_laravel,
                postInstall    = PostInstall.LARAVEL_ENV_KEY
            ),
            FrameworkTemplate(
                id             = "bootstrap_starter",
                displayName    = "Bootstrap Starter",
                tagline        = "A polished landing-page HTML template with Bootstrap 5.",
                downloadUrl    = "https://github.com/StartBootstrap/startbootstrap-landing-page/archive/refs/heads/master.zip",
                downloadSizeMB = 1,
                targetSubdir   = "landing",
                needsDatabase  = false,
                requiresPhp    = false,
                iconRes        = com.inweb.app.R.drawable.ic_fw_bootstrap
            ),
            FrameworkTemplate(
                id             = "phpinfo_sandbox",
                displayName    = "PHP Playground",
                tagline        = "A tiny PHP sandbox — index.php, info.php, and a form demo.",
                downloadUrl    = "",     // built entirely from local strings; no download
                downloadSizeMB = 0,
                targetSubdir   = "php-playground",
                needsDatabase  = false,
                requiresPhp    = true,
                iconRes        = com.inweb.app.R.drawable.ic_fw_php
            ),
        )

        fun byId(id: String?): FrameworkTemplate? = ALL.firstOrNull { it.id == id }
    }
}
