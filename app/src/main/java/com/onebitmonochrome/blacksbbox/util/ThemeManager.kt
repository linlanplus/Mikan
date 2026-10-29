package com.onebitmonochrome.blacksbbox.util

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import androidx.preference.PreferenceManager

/** App theme choice. More themes can be added as new values here and in R.array.theme_values. */
object ThemeManager {
    const val KEY = "theme_mode"
    const val SYSTEM = "system"
    const val LIGHT = "light"
    const val DARK = "dark"

    // Older builds stored a dark mode switch instead.
    private const val LEGACY_DARK_KEY = "dark_mode"

    fun current(context: Context): String {
        val sp = PreferenceManager.getDefaultSharedPreferences(context)
        sp.getString(KEY, null)?.let { return it }
        return when {
            !sp.contains(LEGACY_DARK_KEY) -> SYSTEM
            sp.getBoolean(LEGACY_DARK_KEY, false) -> DARK
            else -> LIGHT
        }
    }

    fun apply(mode: String) {
        AppCompatDelegate.setDefaultNightMode(
                when (mode) {
                    LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
                    DARK -> AppCompatDelegate.MODE_NIGHT_YES
                    else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                }
        )
    }
}
