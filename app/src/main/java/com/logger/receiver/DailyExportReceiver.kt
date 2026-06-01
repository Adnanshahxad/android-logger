package com.logger.receiver

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.work.Constraints
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.logger.service.DailyExportWorker
import java.util.Calendar

class DailyExportReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "DailyExportReceiver"
        const val ACTION_DAILY_EXPORT = "com.logger.action.DAILY_EXPORT"

        /**
         * Schedules the next midnight alarm using AlarmManager.setAlarmClock().
         * Safe to call multiple times — always targets the next future midnight.
         */
        fun scheduleNext(context: Context) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

            // Build the midnight target time
            val target = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
                // If midnight already passed (i.e. it's past 00:00), schedule for tomorrow midnight
                if (timeInMillis <= System.currentTimeMillis()) {
                    add(Calendar.DAY_OF_YEAR, 1)
                }
            }

            val intent = Intent(context, DailyExportReceiver::class.java).apply {
                action = ACTION_DAILY_EXPORT
            }
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            // setAlarmClock fires exactly even in Doze mode
            alarmManager.setAlarmClock(
                AlarmManager.AlarmClockInfo(target.timeInMillis, pendingIntent),
                pendingIntent
            )

            Log.d(TAG, "Next daily export alarm scheduled for: ${target.time}")
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_DAILY_EXPORT) return

        Log.d(TAG, "Daily export alarm fired — enqueuing WorkManager job")

        // Enqueue the actual export work (WorkManager handles network constraint + retries)
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val workRequest = OneTimeWorkRequestBuilder<DailyExportWorker>()
            .setConstraints(constraints)
            .setBackoffCriteria(
                androidx.work.BackoffPolicy.EXPONENTIAL,
                1,
                java.util.concurrent.TimeUnit.HOURS
            )
            .build()

        WorkManager.getInstance(context).enqueue(workRequest)

        // Reschedule next alarm for tomorrow's midnight
        scheduleNext(context)
    }
}
