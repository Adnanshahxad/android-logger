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

    private val TAG = "NotificationLoggerService"
    private val serviceScope = CoroutineScope(Dispatchers.IO + Job())
    
    companion object {
        private val WHATSAPP_PACKAGES = setOf("com.whatsapp", "com.whatsapp.w4b")
        private val TIKTOK_PACKAGES = setOf("com.zhiliaoapp.musically", "com.ss.android.ugc.trill")
        private val INSTAGRAM_PACKAGES = setOf("com.instagram.android")
        private val TRACKED_PACKAGES = WHATSAPP_PACKAGES + TIKTOK_PACKAGES + INSTAGRAM_PACKAGES
    }
    
    // Map of notification ID to Call Info (ContactName, StartTime)
    private data class CallInfo(val contactName: String, val startTime: Long)
    private val activeTracker = mutableMapOf<Int, CallInfo>()

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val packageName = sbn.packageName
        Log.d(TAG, "Raw notification posted from: $packageName")

        if (packageName !in TRACKED_PACKAGES) return

        val notification = sbn.notification
        val title = notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
        val text = notification.extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString() 
                   ?: notification.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() 
                   ?: notification.tickerText?.toString()
                   ?: ""

        if (title.isBlank() && text.isBlank()) return

        // Route to the appropriate handler
        when (packageName) {
            in WHATSAPP_PACKAGES -> handleWhatsApp(sbn, packageName, title, text)
            in TIKTOK_PACKAGES -> handleSocialNotification(sbn, title, text, "TikTok", LogEntry.TYPE_TIKTOK_MSG)
            in INSTAGRAM_PACKAGES -> handleSocialNotification(sbn, title, text, "Instagram", LogEntry.TYPE_INSTAGRAM_MSG)
        }
    }

    private fun handleSocialNotification(sbn: StatusBarNotification, title: String, text: String, appLabel: String, eventType: String) {
        Log.d(TAG, "Social notification from $appLabel — title: '$title', text: '${text.take(80)}'")

        // Skip only truly empty notifications
        if (title.isBlank() && text.isBlank()) return

        val now = System.currentTimeMillis()
        val detailsString = "$title|$appLabel"

        // Deduplicate (2-second debounce)
        val keyId = "${eventType}_$detailsString".hashCode()
        val lastTime = activeTracker.getOrDefault(keyId, CallInfo("", 0L)).startTime
        if (now - lastTime > 2000) {
            activeTracker[keyId] = CallInfo(title, now)
            val entry = LogEntry(
                eventType = eventType,
                details = detailsString,
                appName = text.take(50),
                timestamp = now
            )
            insertLog(entry)
            Log.d(TAG, "Logged $appLabel notification from $title")
        }
    }

    private fun handleWhatsApp(sbn: StatusBarNotification, packageName: String, title: String, text: String) {
        val isBusiness = packageName == "com.whatsapp.w4b"
        val isCall = text.contains("voice call", ignoreCase = true) || 
                     text.contains("video call", ignoreCase = true) ||
                     text.contains("Ongoing call", ignoreCase = true) ||
                     text.contains("Incoming", ignoreCase = true) ||
                     text.contains("Ringing", ignoreCase = true) ||
                     text.contains("Missed", ignoreCase = true)

        val isSubProfile = sbn.user != android.os.Process.myUserHandle()
        
        val sourceApp = buildString {
            if (isBusiness) append("WA Business") else append("WhatsApp")
            if (isSubProfile) append(" (Dual/SIM2)")
        }
        val detailsString = "$title|$sourceApp"

        val now = System.currentTimeMillis()

        // Prevent memory leak: Garbage collect old pending notifications
        activeTracker.entries.removeIf { now - it.value.startTime > 12 * 60 * 60 * 1000L }

        if (isCall) {
            if (text.contains("Ongoing", ignoreCase = true)) {
                // Ongoing call started
                if (!activeTracker.containsKey(sbn.id)) {
                    activeTracker[sbn.id] = CallInfo(detailsString, now)
                    Log.d(TAG, "Started tracking WA call: $title")
                }
            } else if (text.contains("Missed", ignoreCase = true)) {
                 // Log missed call instantly
                 val entry = LogEntry(
                     eventType = LogEntry.TYPE_WHATSAPP_CALL,
                     details = detailsString,
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
             val keyId = "msg_$detailsString".hashCode()
             val lastTime = activeTracker.getOrDefault(keyId, CallInfo("", 0L)).startTime
             
             // 2 second debounce per contact
             if (now - lastTime > 2000) {
                 activeTracker[keyId] = CallInfo(title, now)
                 val entry = LogEntry(
                     eventType = LogEntry.TYPE_WHATSAPP_MSG,
                     details = detailsString,
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
        if (packageName !in WHATSAPP_PACKAGES) return

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
