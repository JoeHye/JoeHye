package com.blackcloudgroup.binaural

import android.content.Context

/** Small persisted user settings shared by the UI and the service. */
class AppSettings(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var logToHealthConnect: Boolean
        get() = prefs.getBoolean(KEY_LOG_TO_HEALTH_CONNECT, false)
        set(value) {
            prefs.edit().putBoolean(KEY_LOG_TO_HEALTH_CONNECT, value).apply()
        }

    private companion object {
        const val PREFS_NAME = "black_cloud_binaural_settings"
        const val KEY_LOG_TO_HEALTH_CONNECT = "log_to_health_connect"
    }
}
