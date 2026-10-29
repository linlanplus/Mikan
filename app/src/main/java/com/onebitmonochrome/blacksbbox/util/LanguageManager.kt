package com.onebitmonochrome.blacksbbox.util

import android.content.Context
import android.content.res.Configuration
import androidx.preference.PreferenceManager
import java.util.Locale

/**
 * Language of this app's own screens. Only the host UI is localized here: the per-app locale API
 * would also change the language of every cloned app, since they run under this package.
 */
object LanguageManager {
    const val KEY = "app_language"
    const val SYSTEM = "system"
    const val CHINESE = "zh"
    const val ENGLISH = "en"

    private var cachedMode: String? = null
    private var cachedContext: Context? = null

    fun current(context: Context): String =
            PreferenceManager.getDefaultSharedPreferences(context).getString(KEY, SYSTEM) ?: SYSTEM

    fun set(context: Context, mode: String) {
        PreferenceManager.getDefaultSharedPreferences(context).edit().putString(KEY, mode).apply()
        cachedMode = null
        cachedContext = null
    }

    private fun locale(mode: String): Locale? = when (mode) {
        CHINESE -> Locale.SIMPLIFIED_CHINESE
        ENGLISH -> Locale.ENGLISH
        else -> null
    }

    /** Returns [base] with the chosen language applied; unchanged when following the system. */
    fun wrap(base: Context, mode: String = current(base)): Context {
        val locale = locale(mode) ?: return base
        val config = Configuration(base.resources.configuration)
        config.setLocale(locale)
        config.setLayoutDirection(locale)
        return base.createConfigurationContext(config)
    }

    /** Localized form of the application context, for toasts and strings outside activities. */
    fun localized(app: Context): Context {
        val mode = current(app)
        cachedContext?.let { if (cachedMode == mode) return it }
        return wrap(app, mode).also {
            cachedMode = mode
            cachedContext = it
        }
    }
}
