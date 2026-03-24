package com.logger.service

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.logger.LoggerApp
import com.logger.data.LogEntry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class WhatsappLoggerService : NotificationListenerService() {

    private val TAG = "WhatsappLoggerService"
    private val serviceScope = CoroutineScope(Dispatchers.IO + Job())
    
    // Map of notification ID to Call Info (ContactName, StartTime)
    private data class CallInfo(val contactName: String, val startTime: Long)
    private val activeTracker = mutableMapOf<Int, CallInfo>()

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val packageName = sbn.packageName
        if (packageName != "com.whatsapp" && packageName != "com.whatsapp.w4b") return

        val notification = sbn.notification
        val title = notification.extras.getString(Notification.EXTRA_TITLE) ?: return
        val text = notification.extras.getString(Notification.EXTRA_TEXT) ?: return

        val isCall = text.contains("voice call", ignoreCase = true) || 
                     text.contains("video call", ignoreCase = true) ||
                     text.contains("Ongoing call", ignoreCase = true) ||
                     text.contains("Incoming", ignoreCase = true) ||
                     text.contains("Ringing", ignoreCase = true) ||
                     text.contains("Missed", ignoreCase = true)

        val now = System.currentTimeMillis()

        if (isCall) {
            if (text.contains("Ongoing", ignoreCase = true)) {
                // Ongoing call started
                if (!activeTracker.containsKey(sbn.id)) {
                    activeTracker[sbn.id] = CallInfo(title, now)
                    Log.d(TAG, "Started tracking WA call: $title")
                }
            } else if (text.contains("Missed", ignoreCase = true)) {
                 // Log missed call instantly
                 val entry = LogEntry(
                     eventType = LogEntry.TYPE_WHATSAPP_CALL,
                     details = title,
                     appName = "Missed Call",
                     timestamp = now
                 )
                 insertLog(entry)
                 Log.d(TAG, "Logged missed WA call: $title")
            }
            // "Incoming" or "Ringing" are ignored until they become "Ongoing" or "Missed"
        } else {
             // Standard text messages
             if (text.contains("new messages", ignoreCase = true) || 
                 text.contains("Checking for new messages", ignoreCase = true) ||
                 title == "WhatsApp") {
                 return // Generic summary notification
             }
             
             // Deduplicate message triggers (WhatsApp updates the same notification ID multiple times per message)
             val keyId = "msg_$title".hashCode()
             val lastTime = activeTracker.getOrDefault(keyId, CallInfo("", 0L)).startTime
             
             // 2 second debounce per contact
             if (now - lastTime > 2000) {
                 activeTracker[keyId] = CallInfo(title, now)
                 val entry = LogEntry(
                     eventType = LogEntry.TYPE_WHATSAPP_MSG,
                     details = title,
                     appName = text.take(50), // Truncate to prevent giant log entries
                     timestamp = now
                 )
                 insertLog(entry)
                 Log.d(TAG, "Logged WA message from $title")
             }
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        val packageName = sbn.packageName
        if (packageName != "com.whatsapp" && packageName != "com.whatsapp.w4b") return

        // If a tracked call notification is dismissed, it means the call ended
        val callInfo = activeTracker.remove(sbn.id)
        if (callInfo != null) {
            val duration = System.currentTimeMillis() - callInfo.startTime
            // Only log if it's a meaningful duration (at least 1 second)
            if (duration > 1000) {
                val entry = LogEntry(
                    eventType = LogEntry.TYPE_WHATSAPP_CALL,
                    details = callInfo.contactName,
                    appName = "Answered Call",
                    durationMillis = duration,
                    timestamp = System.currentTimeMillis()
                )
                insertLog(entry)
                Log.d(TAG, "Ended tracked WA call: ${callInfo.contactName}, duration: ${duration}ms")
            }
        }
    }

    private fun insertLog(entry: LogEntry) {
        serviceScope.launch {
            try {
                val dao = (applicationContext as LoggerApp).database.logDao()
                dao.insertLog(entry)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to insert WA log", e)
            }
        }
    }
}
