package com.joyodigitama.mactivestravasync

import android.content.Context
import android.content.SharedPreferences

class SyncConfig(context: Context) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("mactive_strava_config", Context.MODE_PRIVATE)

    var microwearToken: String
        get() = prefs.getString("microwear_token", "") ?: ""
        set(v) = prefs.edit().putString("microwear_token", v.trim()).apply()

    var watchMac: String
        get() = prefs.getString("watch_mac", "") ?: ""
        set(v) = prefs.edit().putString("watch_mac", v.trim()).apply()

    var stravaClientId: String
        get() = prefs.getString("strava_client_id", "") ?: ""
        set(v) = prefs.edit().putString("strava_client_id", v.trim()).apply()

    var stravaClientSecret: String
        get() = prefs.getString("strava_client_secret", "") ?: ""
        set(v) = prefs.edit().putString("strava_client_secret", v.trim()).apply()

    var stravaRefreshToken: String
        get() = prefs.getString("strava_refresh_token", "") ?: ""
        set(v) = prefs.edit().putString("strava_refresh_token", v.trim()).apply()

    var stravaAccessToken: String
        get() = prefs.getString("strava_access_token", "") ?: ""
        set(v) = prefs.edit().putString("strava_access_token", v.trim()).apply()

    var stravaTokenExpiresAt: Long
        get() = prefs.getLong("strava_token_expires_at", 0L)
        set(v) = prefs.edit().putLong("strava_token_expires_at", v).apply()

    var autoSyncEnabled: Boolean
        get() = prefs.getBoolean("auto_sync_enabled", false)
        set(v) = prefs.edit().putBoolean("auto_sync_enabled", v).apply()

    var lastLog: String
        get() = prefs.getString("last_log", "") ?: ""
        set(v) = prefs.edit().putString("last_log", v).apply()

    fun isUploaded(key: String): Boolean {
        return prefs.getBoolean("up_$key", false)
    }

    fun markUploaded(key: String, activityId: Long) {
        prefs.edit().putBoolean("up_$key", true).putLong("act_$key", activityId).apply()
    }
}
