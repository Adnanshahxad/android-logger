package com.logger.data

import android.content.Context
import android.content.SharedPreferences

class SettingsManager(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("logger_settings", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_EXCLUDED_PACKAGES = "excluded_packages"
        private const val KEY_LOGGER_ENABLED = "logger_enabled"
    }

    var isLoggerEnabled: Boolean
        get() = prefs.getBoolean(KEY_LOGGER_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_LOGGER_ENABLED, value).apply()

    fun getExcludedPackages(): Set<String> {
        return prefs.getStringSet(KEY_EXCLUDED_PACKAGES, emptySet())?.toSet() ?: emptySet()
    }

    fun addExcludedPackage(packageName: String) {
        val currentSet = getExcludedPackages().toMutableSet()
        currentSet.add(packageName.trim())
        prefs.edit().putStringSet(KEY_EXCLUDED_PACKAGES, currentSet).apply()
    }

    fun removeExcludedPackage(packageName: String) {
        val currentSet = getExcludedPackages().toMutableSet()
        currentSet.remove(packageName.trim())
        prefs.edit().putStringSet(KEY_EXCLUDED_PACKAGES, currentSet).apply()
    }
}
