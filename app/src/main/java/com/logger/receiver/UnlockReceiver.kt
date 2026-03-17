package com.logger.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.biometric.BiometricManager
import com.logger.LoggerApp
import com.logger.data.LogEntry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class UnlockReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_USER_PRESENT) {
            Log.d("UnlockReceiver", "Device unlocked detected via OS broadcast!")
            logUnlockEvent(context)
        }
    }

    private fun logUnlockEvent(context: Context) {
        // Need a coroutine scope since we are in a BroadcastReceiver (on main thread natively)
        // and Room insertion is a suspend function
        CoroutineScope(Dispatchers.IO).launch {
            val authMethod = detectAuthMethod(context)
            val entry = LogEntry(
                eventType = LogEntry.TYPE_AUTH_UNLOCK,
                details = authMethod,
                timestamp = System.currentTimeMillis()
            )
            val dao = (context.applicationContext as LoggerApp).database.logDao()
            dao.insertLog(entry)
            Log.d("UnlockReceiver", "Logged unlock: $authMethod")
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
}
