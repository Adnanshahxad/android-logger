package com.logger.service

import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.provider.CallLog
import android.provider.Telephony
import android.telephony.SmsMessage
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import android.util.Log
import androidx.biometric.BiometricManager
import androidx.core.app.NotificationCompat
import com.logger.LoggerApp
import com.logger.R
import com.logger.data.LogEntry
import com.logger.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class LoggerForegroundService : Service() {

    companion object {
        private const val TAG = "LoggerService"
        private const val CHANNEL_ID = "logger_channel"
        private const val NOTIFICATION_ID = 1
        private const val POLL_INTERVAL_MS = 5000L // Increased to 5s to save battery

        fun start(context: Context) {
            val intent = Intent(context, LoggerForegroundService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, LoggerForegroundService::class.java))
        }
    }

    private val serviceScope = CoroutineScope(Dispatchers.IO + Job())
    private var pollingJob: Job? = null
    
    private val screenStateReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    Log.d(TAG, "Screen off, pausing tracking loop to save battery")
                    pollingJob?.cancel()
                }
                Intent.ACTION_SCREEN_ON -> {
                    Log.d(TAG, "Screen on, resuming tracking loop")
                    startPolling()
                }
                Intent.ACTION_USER_PRESENT -> {
                    Log.d(TAG, "Device unlocked detected via OS broadcast!")
                    logUnlockEvent(context)
                }
                TelephonyManager.ACTION_PHONE_STATE_CHANGED -> {
                    // Kept for backup but primary detection is via polling
                    Log.d(TAG, "Phone state changed (broadcast)")
                }
                Telephony.Sms.Intents.SMS_RECEIVED_ACTION -> {
                    handleSmsReceived(context, intent)
                }
            }
        }
    }
    
    // State tracking
    private var lastForegroundPackage: String? = null
    private var lastEventTime: Long = System.currentTimeMillis()
    private var lastForegroundStartTime: Long = 0L
    private var wasDeviceLocked: Boolean = true

    // Call state tracking
    private var lastCallLogCheckTime: Long = System.currentTimeMillis()

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())

        val filter = android.content.IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(TelephonyManager.ACTION_PHONE_STATE_CHANGED)
            addAction(Telephony.Sms.Intents.SMS_RECEIVED_ACTION)
        }
        registerReceiver(screenStateReceiver, filter)

        val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
        if (powerManager?.isInteractive != false) {
            startPolling()
        }
        
        Log.d(TAG, "Logger service started (Battery Optimized)")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        pollingJob?.cancel()
        try {
            unregisterReceiver(screenStateReceiver)
        } catch (e: Exception) {
            // Ignored
        }
        Log.d(TAG, "Logger service stopped")
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Logger Service",
            NotificationManager.IMPORTANCE_MIN
        ).apply {
            description = "Keeps the logger running in the background"
            setShowBadge(false)
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Logger Active")
            .setContentText("Monitoring targeted apps and device unlock...")
            .setSmallIcon(R.drawable.ic_monitor)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    // ─── App Usage & Unlock Polling ───────────────────────────────────

    private fun startPolling() {
        pollingJob = serviceScope.launch {
            while (isActive) {
                try {
                    pollAppUsage()
                    pollCallLog()
                } catch (e: Exception) {
                    Log.e(TAG, "Error during polling", e)
                }
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    private suspend fun pollAppUsage() {
        val usageStatsManager = getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
            ?: return

        val now = System.currentTimeMillis()
        val events = usageStatsManager.queryEvents(lastEventTime, now)
        lastEventTime = now

        val settingsManager = com.logger.data.SettingsManager(this)
        val excludedPackages = settingsManager.getExcludedPackages()

        val event = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)

            val pkg = event.packageName
            
            // We ignore ALL events that are in our exclusion list.
            val isLoggedApp = !excludedPackages.contains(pkg)

            val friendlyName = getAppName(pkg)

            when (event.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED -> {
                    // App came to foreground (opened / got focus)
                    if (pkg != lastForegroundPackage) {
                        
                        // 1. Log previous app closing IF it was a logged app
                        if (lastForegroundPackage != null && !excludedPackages.contains(lastForegroundPackage!!)) {
                            val sessionDuration = event.timeStamp - lastForegroundStartTime
                            val closedEntry = LogEntry(
                                eventType = LogEntry.TYPE_APP_CLOSED,
                                details = lastForegroundPackage!!,
                                appName = getAppName(lastForegroundPackage!!),
                                durationMillis = if (sessionDuration > 0) sessionDuration else null,
                                timestamp = event.timeStamp
                            )
                            getDao().insertLog(closedEntry)
                        }

                        // 2. Log new app opening & focus IF it is a logged app
                        if (isLoggedApp) {
                            val openedEntry = LogEntry(
                                eventType = LogEntry.TYPE_APP_OPENED,
                                details = pkg,
                                appName = friendlyName,
                                timestamp = event.timeStamp
                            )
                            getDao().insertLog(openedEntry)

                            val focusEntry = LogEntry(
                                eventType = LogEntry.TYPE_APP_FOCUS,
                                details = pkg,
                                appName = friendlyName,
                                timestamp = event.timeStamp
                            )
                            getDao().insertLog(focusEntry)
                        }

                        lastForegroundPackage = pkg
                        lastForegroundStartTime = event.timeStamp
                    }
                }

                UsageEvents.Event.ACTIVITY_PAUSED -> {
                    // App went to background
                    if (pkg == lastForegroundPackage) {
                        // Only log if it's one of our logged apps
                        if (isLoggedApp) {
                            val sessionDuration = event.timeStamp - lastForegroundStartTime
                            val closedEntry = LogEntry(
                                eventType = LogEntry.TYPE_APP_CLOSED,
                                details = pkg,
                                appName = friendlyName,
                                durationMillis = if (sessionDuration > 0) sessionDuration else null,
                                timestamp = event.timeStamp
                            )
                            getDao().insertLog(closedEntry)
                        }
                        lastForegroundPackage = null
                    }
                }
            }
        }
    }

    private fun getAppName(packageName: String): String {
        return try {
            val pm = packageManager
            val appInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getApplicationInfo(packageName, PackageManager.ApplicationInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.getApplicationInfo(packageName, 0)
            }
            pm.getApplicationLabel(appInfo).toString()
        } catch (_: Exception) {
            packageName
        }
    }

    private fun getDao() = (application as LoggerApp).database.logDao()

    // ─── Unlock Detection ───────────────────────────────────────────

    private fun logUnlockEvent(context: Context) {
        serviceScope.launch {
            val authMethod = detectAuthMethod(context)
            val entry = LogEntry(
                eventType = LogEntry.TYPE_AUTH_UNLOCK,
                details = authMethod,
                timestamp = System.currentTimeMillis()
            )
            getDao().insertLog(entry)
            Log.d(TAG, "Logged unlock: $authMethod")
        }
    }

    private fun detectAuthMethod(context: Context): String {
        val biometricManager = BiometricManager.from(context)

        val hasBiometricStrong = biometricManager.canAuthenticate(
            BiometricManager.Authenticators.BIOMETRIC_STRONG
        ) == BiometricManager.BIOMETRIC_SUCCESS

        val hasBiometricWeak = biometricManager.canAuthenticate(
            BiometricManager.Authenticators.BIOMETRIC_WEAK
        ) == BiometricManager.BIOMETRIC_SUCCESS

        val hasDeviceCredential = biometricManager.canAuthenticate(
            BiometricManager.Authenticators.DEVICE_CREDENTIAL
        ) == BiometricManager.BIOMETRIC_SUCCESS

        return when {
            hasBiometricStrong -> "Biometric (Fingerprint/Face - Strong)"
            hasBiometricWeak -> "Biometric (Weak)"
            hasDeviceCredential -> "Device Credential (PIN/Pattern/Password)"
            else -> "Screen Unlocked"
        }
    }

    // ─── Call Detection (Polling-based) ───────────────────────────────

    private suspend fun pollCallLog() {
        try {
            val now = System.currentTimeMillis()

            val cursor: Cursor? = contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(
                    CallLog.Calls._ID,
                    CallLog.Calls.NUMBER,
                    CallLog.Calls.DURATION,
                    CallLog.Calls.TYPE,
                    CallLog.Calls.DATE,
                    CallLog.Calls.PHONE_ACCOUNT_ID
                ),
                "${CallLog.Calls.DATE} > ?",
                arrayOf(lastCallLogCheckTime.toString()),
                "${CallLog.Calls.DATE} ASC"
            )

            cursor?.use {
                while (it.moveToNext()) {
                    val number = it.getString(it.getColumnIndexOrThrow(CallLog.Calls.NUMBER)) ?: "Unknown"
                    val duration = it.getLong(it.getColumnIndexOrThrow(CallLog.Calls.DURATION))
                    val type = it.getInt(it.getColumnIndexOrThrow(CallLog.Calls.TYPE))
                    val date = it.getLong(it.getColumnIndexOrThrow(CallLog.Calls.DATE))
                    val phoneAccountId = it.getString(it.getColumnIndexOrThrow(CallLog.Calls.PHONE_ACCOUNT_ID))

                    val callType = when (type) {
                        CallLog.Calls.INCOMING_TYPE -> "Answered"
                        CallLog.Calls.MISSED_TYPE -> "Missed"
                        CallLog.Calls.REJECTED_TYPE -> "Rejected"
                        CallLog.Calls.OUTGOING_TYPE -> "Outgoing"
                        else -> "Unknown"
                    }

                    val simInfo = resolveSimSlot(this@LoggerForegroundService, phoneAccountId)

                    val entry = LogEntry(
                        eventType = LogEntry.TYPE_CALL_INCOMING,
                        details = number,
                        appName = "$callType Call \u2022 $simInfo",
                        durationMillis = if (duration > 0) duration * 1000 else null,
                        timestamp = date
                    )

                    getDao().insertLog(entry)
                    Log.d(TAG, "Polled call: $callType from $number on $simInfo, duration: ${duration}s")
                }
            }

            lastCallLogCheckTime = now

        } catch (e: SecurityException) {
            Log.w(TAG, "No READ_CALL_LOG permission, skipping call polling")
        } catch (e: Exception) {
            Log.e(TAG, "Error polling call log", e)
        }
    }

    // ─── SMS Detection ───────────────────────────────────────────────

    private fun handleSmsReceived(context: Context, intent: Intent) {
        try {
            val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
            if (messages.isNullOrEmpty()) return

            // Resolve SIM slot from the subscription extra
            val subscriptionId = intent.getIntExtra("subscription", -1)
            val simSlot = resolveSimSlotFromSubId(context, subscriptionId)

            // Group message parts by sender (multi-part SMS)
            val smsMap = mutableMapOf<String, StringBuilder>()
            for (sms in messages) {
                val sender = sms.originatingAddress ?: "Unknown"
                smsMap.getOrPut(sender) { StringBuilder() }.append(sms.messageBody ?: "")
            }

            for ((sender, bodyBuilder) in smsMap) {
                val fullBody = bodyBuilder.toString()
                // Strip non-digit characters for length check
                val digitCount = sender.replace(Regex("[^0-9]"), "").length

                // Full message for real phone numbers (>9 digits), 20 chars for short codes
                val storedBody = if (digitCount > 9) {
                    fullBody
                } else {
                    fullBody.take(20)
                }

                serviceScope.launch {
                    val entry = LogEntry(
                        eventType = LogEntry.TYPE_SMS_RECEIVED,
                        details = sender,
                        appName = "$storedBody \u2022 $simSlot",
                        timestamp = System.currentTimeMillis()
                    )
                    getDao().insertLog(entry)
                    Log.d(TAG, "Logged SMS from $sender on $simSlot (digits: $digitCount)")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling SMS", e)
        }
    }

    // ─── SIM Slot Resolution ─────────────────────────────────────────

    private fun resolveSimSlot(context: Context, phoneAccountId: String?): String {
        if (phoneAccountId == null) return "Unknown SIM"
        try {
            val subscriptionManager = context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as? SubscriptionManager
                ?: return "Unknown SIM"
            @Suppress("MissingPermission")
            val subscriptions = subscriptionManager.activeSubscriptionInfoList ?: return "Unknown SIM"
            for (info in subscriptions) {
                // PHONE_ACCOUNT_ID is usually the ICCID or subscription ID
                if (info.iccId == phoneAccountId || info.subscriptionId.toString() == phoneAccountId) {
                    return getSimNumber(info)
                }
            }
            // Fallback: try to match by index if phoneAccountId is a simple number
            val subId = phoneAccountId.toIntOrNull()
            if (subId != null) {
                for (info in subscriptions) {
                    if (info.subscriptionId == subId) {
                        return getSimNumber(info)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error resolving SIM slot", e)
        }
        return "Unknown SIM"
    }

    private fun resolveSimSlotFromSubId(context: Context, subscriptionId: Int): String {
        if (subscriptionId < 0) return "Unknown SIM"
        try {
            val subscriptionManager = context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as? SubscriptionManager
                ?: return "Unknown SIM"
            @Suppress("MissingPermission")
            val subscriptions = subscriptionManager.activeSubscriptionInfoList ?: return "Unknown SIM"
            for (info in subscriptions) {
                if (info.subscriptionId == subscriptionId) {
                    return getSimNumber(info)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error resolving SIM slot from subId", e)
        }
        return "Unknown SIM"
    }

    @Suppress("MissingPermission", "DEPRECATION")
    private fun getSimNumber(info: android.telephony.SubscriptionInfo): String {
        // Try to get the phone number from SubscriptionInfo
        val number = info.number
        if (!number.isNullOrBlank()) return number
        // Fallback to SIM slot label
        return "SIM ${info.simSlotIndex + 1}"
    }
}
