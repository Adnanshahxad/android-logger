package com.logger.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.logger.LoggerApp
import com.logger.service.LoggerForegroundService

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Log.d("BootReceiver", "Boot completed — starting logger service")
            LoggerForegroundService.start(context)

            // Reschedule the daily 6 AM export alarm (AlarmManager alarms don't survive reboots)
            val app = context.applicationContext as LoggerApp
            if (app.hasExactAlarmPermission()) {
                DailyExportReceiver.scheduleNext(context)
                Log.d("BootReceiver", "Daily export alarm rescheduled after boot")
            } else {
                Log.w("BootReceiver", "Exact alarm permission not granted — skipping reschedule")
            }
        }
    }
}
