package com.logger.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
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
        private const val POLL_INTERVAL_MS = 2000L

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
    private var lastForegroundPackage: String? = null
    private var lastEventTime: Long = System.currentTimeMillis()

    // Unlock detection receiver
    private val unlockReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_USER_PRESENT) {
                Log.d(TAG, "Device unlocked!")
                logUnlockEvent()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())

        // Register unlock receiver
        val filter = IntentFilter(Intent.ACTION_USER_PRESENT)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(unlockReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(unlockReceiver, filter)
        }

        // Start polling for app usage
        startPolling()
        Log.d(TAG, "Logger service started")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        pollingJob?.cancel()
        try {
            unregisterReceiver(unlockReceiver)
        } catch (_: Exception) {}
        Log.d(TAG, "Logger service stopped")
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Logger Service",
            NotificationManager.IMPORTANCE_LOW
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
            .setContentText("Monitoring device activity...")
            .setSmallIcon(R.drawable.ic_monitor)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    // ─── Unlock Detection ────────────────────────────────────────────

    private fun logUnlockEvent() {
        serviceScope.launch {
            val authMethod = detectAuthMethod()
            val entry = LogEntry(
                eventType = LogEntry.TYPE_AUTH_UNLOCK,
                details = authMethod,
                timestamp = System.currentTimeMillis()
            )
            getDao().insertLog(entry)
            Log.d(TAG, "Logged unlock: $authMethod")
        }
    }

    private fun detectAuthMethod(): String {
        val biometricManager = BiometricManager.from(this)

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
            else -> "Unknown / None"
        }
    }

    // ─── App Usage Polling ───────────────────────────────────────────

    private fun startPolling() {
        pollingJob = serviceScope.launch {
            while (isActive) {
                try {
                    pollAppUsage()
                } catch (e: Exception) {
                    Log.e(TAG, "Error polling usage", e)
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

        val event = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)

            // Skip our own package
            if (event.packageName == packageName) continue

            val friendlyName = getAppName(event.packageName)

            when (event.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED -> {
                    // App came to foreground (opened / got focus)
                    if (event.packageName != lastForegroundPackage) {
                        // Log focus change
                        val focusEntry = LogEntry(
                            eventType = LogEntry.TYPE_APP_FOCUS,
                            details = event.packageName,
                            appName = friendlyName,
                            timestamp = event.timeStamp
                        )
                        getDao().insertLog(focusEntry)

                        // If it's a new app (not just resuming), log as opened
                        if (lastForegroundPackage != null) {
                            // Previous app lost focus → closed
                            val closedEntry = LogEntry(
                                eventType = LogEntry.TYPE_APP_CLOSED,
                                details = lastForegroundPackage!!,
                                appName = getAppName(lastForegroundPackage!!),
                                timestamp = event.timeStamp
                            )
                            getDao().insertLog(closedEntry)
                        }

                        val openedEntry = LogEntry(
                            eventType = LogEntry.TYPE_APP_OPENED,
                            details = event.packageName,
                            appName = friendlyName,
                            timestamp = event.timeStamp
                        )
                        getDao().insertLog(openedEntry)

                        lastForegroundPackage = event.packageName
                    }
                }

                UsageEvents.Event.ACTIVITY_PAUSED -> {
                    // App went to background
                    if (event.packageName == lastForegroundPackage) {
                        val closedEntry = LogEntry(
                            eventType = LogEntry.TYPE_APP_CLOSED,
                            details = event.packageName,
                            appName = friendlyName,
                            timestamp = event.timeStamp
                        )
                        getDao().insertLog(closedEntry)
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
}
