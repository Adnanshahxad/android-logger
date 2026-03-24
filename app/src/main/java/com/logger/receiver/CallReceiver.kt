package com.logger.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.provider.CallLog
import android.telephony.TelephonyManager
import android.util.Log
import com.logger.LoggerApp
import com.logger.data.LogEntry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class CallReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "CallReceiver"
        private var lastState = TelephonyManager.CALL_STATE_IDLE
        private var incomingNumber: String? = null
        private var callStartTime: Long = 0L
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return

        val stateStr = intent.getStringExtra(TelephonyManager.EXTRA_STATE) ?: return

        val state = when (stateStr) {
            TelephonyManager.EXTRA_STATE_IDLE -> TelephonyManager.CALL_STATE_IDLE
            TelephonyManager.EXTRA_STATE_RINGING -> TelephonyManager.CALL_STATE_RINGING
            TelephonyManager.EXTRA_STATE_OFFHOOK -> TelephonyManager.CALL_STATE_OFFHOOK
            else -> return
        }

        onCallStateChanged(context, state, intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER))
    }

    private fun onCallStateChanged(context: Context, state: Int, number: String?) {
        if (state == lastState) return

        when (state) {
            TelephonyManager.CALL_STATE_RINGING -> {
                // Phone is ringing with an incoming call
                incomingNumber = number
                callStartTime = System.currentTimeMillis()
                Log.d(TAG, "Incoming call from: ${number ?: "Unknown"}")
            }

            TelephonyManager.CALL_STATE_IDLE -> {
                // Call ended (hung up, missed, or rejected)
                if (lastState == TelephonyManager.CALL_STATE_RINGING || lastState == TelephonyManager.CALL_STATE_OFFHOOK) {
                    // Wait for the system call log to be updated, then query it
                    val savedNumber = incomingNumber
                    CoroutineScope(Dispatchers.IO).launch {
                        delay(3000) // Wait 3s for Android to flush its own call log
                        logCallFromSystemDB(context, savedNumber)
                    }
                }
                incomingNumber = null
                callStartTime = 0L
            }

            TelephonyManager.CALL_STATE_OFFHOOK -> {
                // Call was answered
                Log.d(TAG, "Call answered")
            }
        }

        lastState = state
    }

    private suspend fun logCallFromSystemDB(context: Context, phoneNumber: String?) {
        try {
            val app = context.applicationContext as? LoggerApp ?: return
            val dao = app.database.logDao()

            // Query the most recent incoming call from the system call log
            var duration: Long = 0
            var callerNumber: String = phoneNumber ?: "Unknown"
            var callType = "Missed"

            val cursor: Cursor? = context.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(
                    CallLog.Calls.NUMBER,
                    CallLog.Calls.DURATION,
                    CallLog.Calls.TYPE
                ),
                null,
                null,
                "${CallLog.Calls.DATE} DESC LIMIT 1"
            )

            cursor?.use {
                if (it.moveToFirst()) {
                    callerNumber = it.getString(it.getColumnIndexOrThrow(CallLog.Calls.NUMBER)) ?: callerNumber
                    duration = it.getLong(it.getColumnIndexOrThrow(CallLog.Calls.DURATION))
                    val type = it.getInt(it.getColumnIndexOrThrow(CallLog.Calls.TYPE))
                    callType = when (type) {
                        CallLog.Calls.INCOMING_TYPE -> "Answered"
                        CallLog.Calls.MISSED_TYPE -> "Missed"
                        CallLog.Calls.REJECTED_TYPE -> "Rejected"
                        CallLog.Calls.OUTGOING_TYPE -> "Outgoing"
                        else -> "Unknown"
                    }
                }
            }

            val entry = LogEntry(
                eventType = LogEntry.TYPE_CALL_INCOMING,
                details = callerNumber,
                appName = "$callType Call",
                durationMillis = if (duration > 0) duration * 1000 else null,
                timestamp = System.currentTimeMillis()
            )

            dao.insertLog(entry)
            Log.d(TAG, "Logged call: $callType from $callerNumber, duration: ${duration}s")

        } catch (e: Exception) {
            Log.e(TAG, "Error logging call", e)
        }
    }
}
