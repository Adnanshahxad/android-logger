package com.logger.service

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.logger.data.AppDatabase
import com.logger.utils.CloudUploadHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class DailyExportWorker(
    private val context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    companion object {
        private const val TAG = "DailyExportWorker"
        private const val PREFS_NAME = "daily_export_prefs"
        private const val KEY_LAST_EXPORTED_DAY = "last_exported_day_midnight"
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

            // --- Compute yesterday's midnight (end of export window) ---
            val yesterdayMidnight = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }.timeInMillis  // today 00:00:00 = yesterday end boundary

            // --- Compute start of export window ---
            // Default: if never exported, start from exactly 24h before yesterday midnight
            val defaultStart = yesterdayMidnight - (24 * 60 * 60 * 1000L)
            val startTimestamp = prefs.getLong(KEY_LAST_EXPORTED_DAY, defaultStart)
            val endTimestamp = yesterdayMidnight - 1  // yesterday 23:59:59.999

            if (startTimestamp >= yesterdayMidnight) {
                Log.d(TAG, "Already exported up to yesterday. Nothing to do.")
                return@withContext Result.success()
            }

            // Calculate how many days are being caught up
            val daysDiff = ((yesterdayMidnight - startTimestamp) / (24 * 60 * 60 * 1000L)).toInt()
            Log.d(TAG, "Starting export: covering $daysDiff day(s) from ${Date(startTimestamp)} to ${Date(endTimestamp)}")

            val db = AppDatabase.getInstance(context)
            val logs = db.logDao().getAllLogsInRange(startTimestamp, endTimestamp)

            if (logs.isEmpty()) {
                Log.d(TAG, "No logs in range. Marking as exported and skipping upload.")
                // Still advance the pointer so we don't retry empty ranges forever
                prefs.edit().putLong(KEY_LAST_EXPORTED_DAY, yesterdayMidnight).apply()
                return@withContext Result.success()
            }

            // --- Build filename ---
            val sdf = SimpleDateFormat("yyyy_MM_dd", Locale.getDefault())
            val manufacturer = android.os.Build.MANUFACTURER
                .replace(" ", "_")
                .replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
            val model = android.os.Build.MODEL.replace(" ", "_")

            val baseFileName = if (daysDiff == 1) {
                // Single day: Logger_Samsung_SM-A266B_2026_05_31.xls
                val dayStr = sdf.format(Date(startTimestamp))
                "Logger_${manufacturer}_${model}_${dayStr}.xls"
            } else {
                // Catch-up: Logger_Samsung_SM-A266B_2026_05_29_to_2026_05_31.xls
                val fromStr = sdf.format(Date(startTimestamp))
                val toStr = sdf.format(Date(endTimestamp))
                "Logger_${manufacturer}_${model}_${fromStr}_to_${toStr}.xls"
            }

            val tempFile = File(context.cacheDir, "Temp_$baseFileName")

            CloudUploadHelper.buildExcelFile(tempFile, logs)
            CloudUploadHelper.uploadToDropbox(context, tempFile, baseFileName)

            tempFile.delete()

            // --- Advance the last-exported pointer to yesterday midnight ---
            prefs.edit().putLong(KEY_LAST_EXPORTED_DAY, yesterdayMidnight).apply()
            Log.d(TAG, "Export successful! Uploaded: $baseFileName")

            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Export failed: ${e.message}", e)
            if (runAttemptCount >= 3) {
                Log.e(TAG, "Max retries (3) reached. Will retry at next scheduled alarm.")
                return@withContext Result.failure()
            }
            Result.retry()
            // NOTE: We do NOT advance last_exported_day on failure,
            // so the next run will retry the same (or wider) window.
        }
    }
}
