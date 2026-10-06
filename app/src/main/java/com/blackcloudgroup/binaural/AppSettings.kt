package com.blackcloudgroup.binaural

import android.content.Context
import android.util.Log

enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** Small persisted user settings shared by the UI and the service. */
class AppSettings(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var logToHealthConnect: Boolean
        get() = prefs.getBoolean(KEY_LOG_TO_HEALTH_CONNECT, false)
        set(value) {
            prefs.edit().putBoolean(KEY_LOG_TO_HEALTH_CONNECT, value).apply()
        }

    var themeMode: ThemeMode
        get() {
            val raw = prefs.getString(KEY_THEME_MODE, null) ?: return ThemeMode.SYSTEM
            return ThemeMode.values().firstOrNull { it.name == raw } ?: run {
                Log.w("AppSettings", "Unknown theme mode '$raw'; using SYSTEM")
                ThemeMode.SYSTEM
            }
        }
        set(value) {
            prefs.edit().putString(KEY_THEME_MODE, value.name).apply()
        }

    private companion object {
        const val PREFS_NAME = "black_cloud_binaural_settings"
        const val KEY_LOG_TO_HEALTH_CONNECT = "log_to_health_connect"
        const val KEY_THEME_MODE = "theme_mode"
    }
}
