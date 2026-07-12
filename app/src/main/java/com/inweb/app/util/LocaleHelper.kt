package com.inweb.app.util

import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat

/**
 * Manages the per-app locale using androidx's [AppCompatDelegate.setApplicationLocales]
 * (backport of Android 13's LocaleManager).
 *
 * Supported languages ship as `values-<lang>/strings.xml`.
 */
enum class AppLocale(val tag: String, val displayName: String) {
    SYSTEM ("",       "Follow system"),
    ENGLISH("en",     "English"),
    BANGLA ("bn",     "বাংলা (Bangla)"),
    ARABIC ("ar",     "العربية (Arabic)"),
    HINDI  ("hi",     "हिन्दी (Hindi)"),
    URDU   ("ur",     "اردو (Urdu)");

    companion object {
        fun fromTag(tag: String?): AppLocale =
            entries.firstOrNull { it.tag == (tag ?: "") } ?: SYSTEM

        fun apply(locale: AppLocale) {
            val list = if (locale.tag.isEmpty()) LocaleListCompat.getEmptyLocaleList()
                       else LocaleListCompat.forLanguageTags(locale.tag)
            AppCompatDelegate.setApplicationLocales(list)
        }

        fun current(): AppLocale {
            val tag = AppCompatDelegate.getApplicationLocales()
                .toLanguageTags().substringBefore(',').ifEmpty { "" }
            return fromTag(tag)
        }
    }
}
