package com.logger

import android.app.Application
import com.google.android.material.color.DynamicColors
import com.logger.data.AppDatabase

class LoggerApp : Application() {

    override fun onCreate() {
        super.onCreate()
        DynamicColors.applyToActivitiesIfAvailable(this)
    }

    val database: AppDatabase by lazy {
        AppDatabase.getInstance(this)
    }
}
