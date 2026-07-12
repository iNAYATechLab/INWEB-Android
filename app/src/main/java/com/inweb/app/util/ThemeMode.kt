package com.inweb.app.util

import androidx.appcompat.app.AppCompatDelegate

/**
 * User's preferred UI theme.
 *
 * Applied globally in [com.inweb.app.ServerApp.onCreate] and re-applied from
 * the Settings screen when the user changes it.
 */
enum class ThemeMode(val id: String, val displayName: String, val nightMode: Int) {
    SYSTEM("system", "Follow system",  AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM),
    LIGHT ("light",  "Light",          AppCompatDelegate.MODE_NIGHT_NO),
    DARK  ("dark",   "Dark",           AppCompatDelegate.MODE_NIGHT_YES);

    companion object {
        fun fromId(id: String?): ThemeMode = entries.firstOrNull { it.id == id } ?: SYSTEM

        /** Apply the given theme immediately (safe to call from anywhere). */
        fun apply(mode: ThemeMode) {
            AppCompatDelegate.setDefaultNightMode(mode.nightMode)
        }
    }
}
