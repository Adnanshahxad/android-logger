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
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
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

        // Only log events from these specific packages
        private val TARGET_PACKAGES = setOf(
            "com.zhiliaoapp.musically",      // TikTok
            "com.ss.android.ugc.trill",      // TikTok (Global)
            "com.facebook.katana",           // Facebook
            "com.facebook.lite",             // Facebook Lite
            "com.whatsapp",                  // WhatsApp
            "com.whatsapp.w4b",              // WhatsApp Business
            "com.instagram.android",         // Instagram
            "com.android.chrome"             // Google Chrome
        )

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
            }
        }
    }
    
    // State tracking
    private var lastForegroundPackage: String? = null
    private var lastEventTime: Long = System.currentTimeMillis()
    private var wasDeviceLocked: Boolean = true

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())

        val filter = android.content.IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
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

        val event = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)

            val pkg = event.packageName
            
            // Focus events trigger frequently, we still want to track when our target app loses focus
            // But we ignore ALL events that are not related to our target packages, except when
            // our tracked target app is losing focus to a non-tracked app.
            val isTargetApp = TARGET_PACKAGES.contains(pkg)

            val friendlyName = getAppName(pkg)

            when (event.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED -> {
                    // App came to foreground (opened / got focus)
                    if (pkg != lastForegroundPackage) {
                        
                        // 1. Log previous app closing IF it was a target app
                        if (lastForegroundPackage != null && TARGET_PACKAGES.contains(lastForegroundPackage!!)) {
                            val closedEntry = LogEntry(
                                eventType = LogEntry.TYPE_APP_CLOSED,
                                details = lastForegroundPackage!!,
                                appName = getAppName(lastForegroundPackage!!),
                                timestamp = event.timeStamp
                            )
                            getDao().insertLog(closedEntry)
                        }

                        // 2. Log new app opening & focus IF it is a target app
                        if (isTargetApp) {
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
                    }
                }

                UsageEvents.Event.ACTIVITY_PAUSED -> {
                    // App went to background
                    if (pkg == lastForegroundPackage) {
                        // Only log if it's one of our target apps
                        if (isTargetApp) {
                            val closedEntry = LogEntry(
                                eventType = LogEntry.TYPE_APP_CLOSED,
                                details = pkg,
                                appName = friendlyName,
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
        // Hardcode friendly names for the target packages to avoid System API overhead if possible
        return when (packageName) {
            "com.zhiliaoapp.musically", "com.ss.android.ugc.trill" -> "TikTok"
            "com.facebook.katana" -> "Facebook"
            "com.facebook.lite" -> "Facebook Lite"
            "com.whatsapp" -> "WhatsApp"
            "com.whatsapp.w4b" -> "WhatsApp Business"
            "com.instagram.android" -> "Instagram"
            "com.android.chrome" -> "Google Chrome"
            else -> {
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
    }

    private fun getDao() = (application as LoggerApp).database.logDao()
}
