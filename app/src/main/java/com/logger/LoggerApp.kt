package com.logger

import android.app.Application
import com.logger.data.AppDatabase

class LoggerApp : Application() {

    val database: AppDatabase by lazy {
        AppDatabase.getInstance(this)
    }
}
