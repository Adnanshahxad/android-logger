package com.logger.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(
    tableName = "log_entries",
    indices = [
        androidx.room.Index(value = ["timestamp"]),
        androidx.room.Index(value = ["eventType", "timestamp"]),
        androidx.room.Index(value = ["details"])
    ]
)
data class LogEntry(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    /** Event type: AUTH_UNLOCK, APP_CLOSED, CALL_INCOMING, SMS_RECEIVED, etc. */
    val eventType: String,

    /** Details: e.g. "Fingerprint", "com.whatsapp", package name */
    val details: String,

    /** Friendly app name if available, null otherwise */
    val appName: String? = null,

    /** Optional session duration in milliseconds (typically set on APP_CLOSED events) */
    val durationMillis: Long? = null,

    /** Epoch millis timestamp */
    val timestamp: Long = System.currentTimeMillis()
) {
    companion object {
        const val TYPE_AUTH_UNLOCK = "AUTH_UNLOCK"
        const val TYPE_APP_CLOSED = "APP_CLOSED"
        const val TYPE_CALL_INCOMING = "CALL_INCOMING"
        const val TYPE_SMS_RECEIVED = "SMS_RECEIVED"
        const val TYPE_WHATSAPP_CALL = "WHATSAPP_CALL"
        const val TYPE_WHATSAPP_MSG = "WHATSAPP_MSG"
        const val TYPE_TIKTOK_MSG = "TIKTOK_MSG"
        const val TYPE_INSTAGRAM_MSG = "INSTAGRAM_MSG"
    }
}
