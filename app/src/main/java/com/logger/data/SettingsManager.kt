package com.logger.data

import android.content.Context
import android.content.SharedPreferences

class SettingsManager(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("logger_settings", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_INCLUDED_PACKAGES = "included_packages"
        private const val KEY_LOGGER_ENABLED = "logger_enabled"
        private const val KEY_POLLING_ENABLED = "polling_enabled"
        private const val KEY_POLL_INTERVAL_SECONDS = "poll_interval_seconds"
        private const val DEFAULT_POLL_INTERVAL_SECONDS = 60

        private val DEFAULT_INCLUDED_PACKAGES = setOf(
            "com.twitter.android",
            "com.facebook.lite",
            "com.facebook.katana",
            "com.instagram.android",
            "com.instagram.lite",
            "com.android.chrome",
            "com.whatsapp.w4b",
            "com.whatsapp",
            "com.samsung.android.messaging",
            "com.samsung.android.dialer",
            "com.zhiliaoapp.musically",
            "com.google.android.youtube",
            "com.google.android.gm",
            "com.snapchat.android"
        )
    }

    var isLoggerEnabled: Boolean
        get() = prefs.getBoolean(KEY_LOGGER_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_LOGGER_ENABLED, value).apply()

    var isPollingEnabled: Boolean
        get() = prefs.getBoolean(KEY_POLLING_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_POLLING_ENABLED, value).apply()

    var pollIntervalSeconds: Int
        get() = prefs.getInt(KEY_POLL_INTERVAL_SECONDS, DEFAULT_POLL_INTERVAL_SECONDS)
        set(value) = prefs.edit().putInt(KEY_POLL_INTERVAL_SECONDS, value.coerceAtLeast(10)).apply()

    fun getIncludedPackages(): Set<String> {
        return prefs.getStringSet(KEY_INCLUDED_PACKAGES, DEFAULT_INCLUDED_PACKAGES)?.toSet() ?: DEFAULT_INCLUDED_PACKAGES
    }

    fun addIncludedPackage(packageName: String) {
        val currentSet = getIncludedPackages().toMutableSet()
        currentSet.add(packageName.trim())
        prefs.edit().putStringSet(KEY_INCLUDED_PACKAGES, currentSet).apply()
    }

    fun removeIncludedPackage(packageName: String) {
        val currentSet = getIncludedPackages().toMutableSet()
        currentSet.remove(packageName.trim())
        prefs.edit().putStringSet(KEY_INCLUDED_PACKAGES, currentSet).apply()
    }
}
