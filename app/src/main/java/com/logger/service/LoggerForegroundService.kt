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
import android.database.ContentObserver
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.provider.CallLog
import android.provider.ContactsContract
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
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class LoggerForegroundService : Service() {

    companion object {
        private const val TAG = "LoggerService"
        private const val CHANNEL_ID = "logger_channel"
        private const val NOTIFICATION_ID = 1
        private const val POLL_INTERVAL_MS = 5000L // Increased to 5s to save battery
        private val NON_DIGIT_REGEX = Regex("[^0-9]")

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
                    Log.d(TAG, "Phone state broadcast received (backup)")
                }
                Telephony.Sms.Intents.SMS_RECEIVED_ACTION -> {
                    handleSmsReceived(context, intent)
                }
            }
        }
    }
    
    // App name cache to avoid repeated PackageManager queries
    private val appNameCache = mutableMapOf<String, String>()

    // State tracking
    private var lastForegroundPackage: String? = null
    private var lastEventTime: Long = System.currentTimeMillis()
    private var lastForegroundStartTime: Long = 0L
    private var wasDeviceLocked: Boolean = true

    // Call and SMS state tracking
    private var lastCallLogId: Long = 0L
    private var lastSmsId: Long = 0L

    // Database observers for real-time tracking
    private var callLogObserver: ContentObserver? = null
    private var smsLogObserver: ContentObserver? = null

    // Synchronization locks to prevent observer & polling loop collisions
    private val callLogMutex = Mutex()
    private val smsLogMutex = Mutex()

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
        // RECEIVER_EXPORTED required for system broadcasts on Android 13+
        registerReceiver(screenStateReceiver, filter, Context.RECEIVER_EXPORTED)

        val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
        if (powerManager?.isInteractive != false) {
            startPolling()
        }

        // Setup real-time database observers
        val handler = Handler(Looper.getMainLooper())
        
        callLogObserver = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean) {
                Log.d(TAG, "CallLog changed! Running instant poll...")
                serviceScope.launch { pollCallLog() }
            }
        }
        
        smsLogObserver = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean) {
                Log.d(TAG, "SmsLog changed! Running instant poll...")
                serviceScope.launch { pollSmsLog() }
            }
        }

        try {
            contentResolver.registerContentObserver(CallLog.Calls.CONTENT_URI, true, callLogObserver!!)
            contentResolver.registerContentObserver(Telephony.Sms.CONTENT_URI, true, smsLogObserver!!)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register observers", e)
        }
        
        Log.d(TAG, "Logger service started (Battery Optimized + Observers)")

        Log.d(TAG, "Logger service started (Battery Optimized + Observers)")

        serviceScope.launch {
            // Initialize last known IDs to avoid re-logging old entries
            initializeLastIds()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        pollingJob?.cancel()
        try {
            unregisterReceiver(screenStateReceiver)
        } catch (e: Exception) {
            // Ignored
        }
        try {
            callLogObserver?.let { contentResolver.unregisterContentObserver(it) }
            smsLogObserver?.let { contentResolver.unregisterContentObserver(it) }
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
        pollingJob?.cancel() // Prevent duplicate polling loops
        pollingJob = serviceScope.launch {
            while (isActive) {
                try {
                    pollAppUsage()
                    pollCallLog()
                    pollSmsLog()
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
        val includedPackages = settingsManager.getIncludedPackages()

        val event = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)

            val pkg = event.packageName

            // Only log events for packages in the include list
            val isLoggedApp = includedPackages.contains(pkg)

            val friendlyName = getAppName(pkg)

            when (event.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED -> {
                    // App came to foreground (opened / got focus)
                    if (pkg != lastForegroundPackage) {

                        // 1. Log previous app closing IF it was a logged app
                        if (lastForegroundPackage != null && includedPackages.contains(lastForegroundPackage!!)) {
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
        return appNameCache.getOrPut(packageName) {
            try {
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

    private fun resolveContactName(context: Context, phoneNumber: String): String? {
        if (phoneNumber.isBlank() || phoneNumber == "Unknown") return null
        // Check for READ_CONTACTS permission
        if (context.checkSelfPermission(android.Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            return null
        }
        
        var contactName: String? = null
        try {
            val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(phoneNumber))
            val projection = arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME)
            context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    contactName = cursor.getString(0)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error resolving contact name for $phoneNumber", e)
        }
        return contactName
    }

    // ─── Call Detection (Polling-based) ───────────────────────────────

    private suspend fun initializeLastIds() {
        try {
            // Get the latest call log ID
            contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(CallLog.Calls._ID),
                null, null,
                "${CallLog.Calls._ID} DESC LIMIT 1"
            )?.use {
                if (it.moveToFirst()) {
                    lastCallLogId = it.getLong(0)
                    Log.d(TAG, "Initialized lastCallLogId=$lastCallLogId")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing call log ID", e)
        }

        try {
            // Get the latest SMS ID
            contentResolver.query(
                Telephony.Sms.CONTENT_URI,
                arrayOf(Telephony.Sms._ID),
                null, null,
                "${Telephony.Sms._ID} DESC LIMIT 1"
            )?.use {
                if (it.moveToFirst()) {
                    lastSmsId = it.getLong(0)
                    Log.d(TAG, "Initialized lastSmsId=$lastSmsId")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing SMS ID", e)
        }
    }

    private suspend fun pollCallLog() = callLogMutex.withLock {
        try {
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
                "${CallLog.Calls._ID} > ?",
                arrayOf(lastCallLogId.toString()),
                "${CallLog.Calls._ID} ASC"
            )

            cursor?.use {
                while (it.moveToNext()) {
                    val id = it.getLong(it.getColumnIndexOrThrow(CallLog.Calls._ID))
                    val number = it.getString(it.getColumnIndexOrThrow(CallLog.Calls.NUMBER)) ?: "Unknown"
                    val duration = it.getLong(it.getColumnIndexOrThrow(CallLog.Calls.DURATION))
                    val type = it.getInt(it.getColumnIndexOrThrow(CallLog.Calls.TYPE))
                    val date = it.getLong(it.getColumnIndexOrThrow(CallLog.Calls.DATE))
                    val phoneAccountId = it.getString(it.getColumnIndexOrThrow(CallLog.Calls.PHONE_ACCOUNT_ID))

                    val callTypeStr = when (type) {
                        CallLog.Calls.INCOMING_TYPE -> "Answered Call"
                        CallLog.Calls.MISSED_TYPE -> "Missed Call"
                        CallLog.Calls.REJECTED_TYPE -> "Rejected Call"
                        CallLog.Calls.OUTGOING_TYPE -> "Call_Outgoing"
                        else -> "Unknown Call"
                    }

                    val simInfo = resolveSimSlot(this@LoggerForegroundService, phoneAccountId)
                    val contactName = resolveContactName(this@LoggerForegroundService, number)
                    val displayDetails = if (contactName != null) "$contactName ($number)" else number

                    val entry = LogEntry(
                        eventType = LogEntry.TYPE_CALL_INCOMING,
                        details = displayDetails,
                        appName = "$callTypeStr \u2022 $simInfo",
                        durationMillis = if (duration > 0) duration * 1000 else null,
                        timestamp = date
                    )

                    getDao().insertLog(entry)
                    lastCallLogId = id
                    Log.d(TAG, "Polled call: $callTypeStr from $number on $simInfo, duration: ${duration}s")
                }
            }

        } catch (e: SecurityException) {
            Log.w(TAG, "No READ_CALL_LOG permission, skipping call polling")
        } catch (e: Exception) {
            Log.e(TAG, "Error polling call log", e)
        }
    }

    // ─── SMS Detection (Polling-based) ───────────────────────────────

    private suspend fun pollSmsLog() = smsLogMutex.withLock {
        try {
            val cursor: Cursor? = contentResolver.query(
                Telephony.Sms.CONTENT_URI,
                arrayOf(
                    Telephony.Sms._ID,
                    Telephony.Sms.ADDRESS,
                    Telephony.Sms.BODY,
                    Telephony.Sms.DATE,
                    Telephony.Sms.TYPE,
                    Telephony.Sms.SUBSCRIPTION_ID
                ),
                "${Telephony.Sms._ID} > ?",
                arrayOf(lastSmsId.toString()),
                "${Telephony.Sms._ID} ASC"
            )

            cursor?.use {
                while (it.moveToNext()) {
                    val id = it.getLong(it.getColumnIndexOrThrow(Telephony.Sms._ID))
                    val address = it.getString(it.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)) ?: "Unknown"
                    val body = it.getString(it.getColumnIndexOrThrow(Telephony.Sms.BODY)) ?: ""
                    val date = it.getLong(it.getColumnIndexOrThrow(Telephony.Sms.DATE))
                    val type = it.getInt(it.getColumnIndexOrThrow(Telephony.Sms.TYPE))
                    val subId = it.getInt(it.getColumnIndexOrThrow(Telephony.Sms.SUBSCRIPTION_ID))

                    val direction = when (type) {
                        Telephony.Sms.MESSAGE_TYPE_INBOX -> "Received"
                        Telephony.Sms.MESSAGE_TYPE_SENT -> "Sent"
                        Telephony.Sms.MESSAGE_TYPE_OUTBOX -> "Sending"
                        Telephony.Sms.MESSAGE_TYPE_DRAFT -> "Draft"
                        else -> "Unknown"
                    }

                    // Skip drafts
                    if (type == Telephony.Sms.MESSAGE_TYPE_DRAFT) {
                        lastSmsId = id
                        continue
                    }

                    val digitCount = address.let { NON_DIGIT_REGEX.replace(it, "") }.length
                    val storedBody = if (digitCount > 9) body else body.take(20)
                    val simInfo = resolveSimSlotFromSubId(this@LoggerForegroundService, subId)
                    val contactName = resolveContactName(this@LoggerForegroundService, address)
                    val displayDetails = if (contactName != null) "$contactName ($address)" else address

                    val entry = LogEntry(
                        eventType = LogEntry.TYPE_SMS_RECEIVED,
                        details = displayDetails,
                        appName = "[$direction] $storedBody \u2022 $simInfo",
                        timestamp = date
                    )

                    getDao().insertLog(entry)
                    lastSmsId = id
                    Log.d(TAG, "Polled SMS: $direction from/to $address on $simInfo")
                }
            }

        } catch (e: SecurityException) {
            Log.w(TAG, "No READ_SMS permission, skipping SMS polling")
        } catch (e: Exception) {
            Log.e(TAG, "Error polling SMS log", e)
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
                val digitCount = sender.let { NON_DIGIT_REGEX.replace(it, "") }.length

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
