package com.logger

import android.app.AlarmManager
import android.app.Application
import android.os.Build
import com.logger.data.AppDatabase
import com.logger.receiver.DailyExportReceiver

class LoggerApp : Application() {

    override fun onCreate() {
        super.onCreate()
        // Schedule the daily 6 AM export alarm.
        // On Android 12+, this requires SCHEDULE_EXACT_ALARM permission.
        // If not granted, the alarm is skipped — MainActivity will prompt the user.
        if (hasExactAlarmPermission()) {
            DailyExportReceiver.scheduleNext(this)
        }
    }

    /**
     * Returns true if the app can schedule exact alarms.
     * On Android < 12, exact alarms are always allowed.
     * On Android 12+, requires the user to grant SCHEDULE_EXACT_ALARM in Settings.
     */
    fun hasExactAlarmPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val alarmManager = getSystemService(ALARM_SERVICE) as AlarmManager
            alarmManager.canScheduleExactAlarms()
        } else {
            true
        }
    }

    val database: AppDatabase by lazy {
        AppDatabase.getInstance(this)
    }
}
