package com.logger

import android.app.Application
import com.logger.data.AppDatabase

class LoggerApp : Application() {

    override fun onCreate() {
        super.onCreate()
        // Removed DynamicColors to enforce strict Green/White color scheme
    }

    val database: AppDatabase by lazy {
        AppDatabase.getInstance(this)
    }
}
