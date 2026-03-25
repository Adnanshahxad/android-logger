package com.logger

import android.app.Application
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.logger.data.AppDatabase
import com.logger.service.DailyExportWorker
import java.util.Calendar
import java.util.concurrent.TimeUnit

class LoggerApp : Application() {

    override fun onCreate() {
        super.onCreate()
        scheduleDailyExport()
    }

    private fun scheduleDailyExport() {
        val currentDate = Calendar.getInstance()
        val dueDate = Calendar.getInstance()
        dueDate.set(Calendar.HOUR_OF_DAY, 6)
        dueDate.set(Calendar.MINUTE, 0)
        dueDate.set(Calendar.SECOND, 0)

        if (dueDate.before(currentDate)) {
            dueDate.add(Calendar.HOUR_OF_DAY, 24)
        }

        val timeDiff = dueDate.timeInMillis - currentDate.timeInMillis
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val dailyWorkRequest = PeriodicWorkRequestBuilder<DailyExportWorker>(24, TimeUnit.HOURS)
            .setInitialDelay(timeDiff, TimeUnit.MILLISECONDS)
            .setConstraints(constraints)
            .setBackoffCriteria(
                androidx.work.BackoffPolicy.EXPONENTIAL,
                1,
                TimeUnit.HOURS
            )
            .build()

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "DailyExportJob",
            ExistingPeriodicWorkPolicy.UPDATE,
            dailyWorkRequest
        )
    }

    val database: AppDatabase by lazy {
        AppDatabase.getInstance(this)
    }
}
