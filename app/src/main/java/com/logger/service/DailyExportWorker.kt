package com.logger.service

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.logger.data.AppDatabase
import com.logger.utils.GoogleDriveHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class DailyExportWorker(
    private val context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    companion object {
        private const val TAG = "DailyExportWorker"
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Starting automated Daily Google Drive Sync...")
            
            val endTimestamp = System.currentTimeMillis()
            val startTimestamp = endTimestamp - (24 * 60 * 60 * 1000L) // Last 24 hours
            
            val db = AppDatabase.getInstance(context)
            val logs = db.logDao().getAllLogsInRange(startTimestamp, endTimestamp)
            
            if (logs.isEmpty()) {
                Log.d(TAG, "No logs recorded in the last 24 hours. Skipping upload.")
                return@withContext Result.success()
            }
            
            val sdf = SimpleDateFormat("yyyy_MM_dd_HH_mm_ss", Locale.getDefault())
            val dateStr = sdf.format(Date())
            
            val manufacturer = android.os.Build.MANUFACTURER.replace(" ", "_").replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
            val model = android.os.Build.MODEL.replace(" ", "_")
            val baseFileName = "Logger_${manufacturer}_${model}_$dateStr.xls"
            
            val tempFile = File(context.cacheDir, "Temp_$baseFileName")
            
            GoogleDriveHelper.buildExcelFile(tempFile, logs)
            
            GoogleDriveHelper.uploadToGoogleDrive(context, tempFile, baseFileName)
            
            tempFile.delete()
            Log.d(TAG, "Google Drive Sync successful!")
            
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Failed daily sync: ${e.message}", e)
            if (runAttemptCount >= 3) {
                Log.e(TAG, "Max retries (3) reached. Halting until tomorrow at 6:00 AM.")
                return@withContext Result.failure()
            }
            Result.retry() // Reschedules automatically following exponential backoff
        }
    }
}
