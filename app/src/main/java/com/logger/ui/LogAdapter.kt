package com.logger.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.logger.R
import com.logger.data.LogEntry
import com.logger.databinding.ItemLogBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

class LogAdapter : ListAdapter<LogEntry, LogAdapter.LogViewHolder>(LogDiffCallback()) {

    private val dateFormat = SimpleDateFormat("MMM dd, hh:mm:ss a", Locale.getDefault())

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LogViewHolder {
        val binding = ItemLogBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return LogViewHolder(binding)
    }

    override fun onBindViewHolder(holder: LogViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class LogViewHolder(
        private val binding: ItemLogBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(entry: LogEntry) {
            // Icon based on event type
            val iconRes = when (entry.eventType) {
                LogEntry.TYPE_AUTH_UNLOCK -> R.drawable.ic_lock_open
                LogEntry.TYPE_APP_OPENED -> R.drawable.ic_launch
                LogEntry.TYPE_APP_CLOSED -> R.drawable.ic_close
                LogEntry.TYPE_APP_FOCUS -> R.drawable.ic_visibility
                LogEntry.TYPE_CALL_INCOMING -> android.R.drawable.ic_menu_call
                LogEntry.TYPE_SMS_RECEIVED -> android.R.drawable.ic_dialog_email
                LogEntry.TYPE_WHATSAPP_CALL -> android.R.drawable.ic_menu_call
                LogEntry.TYPE_WHATSAPP_MSG -> android.R.drawable.ic_dialog_email
                else -> R.drawable.ic_monitor
            }
            binding.iconEvent.setImageResource(iconRes)

            // Event type label
            val typeLabel = when (entry.eventType) {
                LogEntry.TYPE_AUTH_UNLOCK -> "UNLOCK"
                LogEntry.TYPE_APP_OPENED -> "OPENED"
                LogEntry.TYPE_APP_CLOSED -> "CLOSED"
                LogEntry.TYPE_APP_FOCUS -> "FOCUS"
                LogEntry.TYPE_CALL_INCOMING -> "CALL"
                LogEntry.TYPE_SMS_RECEIVED -> "SMS"
                LogEntry.TYPE_WHATSAPP_CALL -> {
                    val parts = entry.details.split("|", limit = 2)
                    parts[0] // Show full contact name
                }
                LogEntry.TYPE_WHATSAPP_MSG -> {
                    val parts = entry.details.split("|", limit = 2)
                    parts[0] // Show full contact name
                }
                else -> entry.eventType
            }
            binding.textEventType.text = typeLabel

            // Color the event type chip
            val colorRes = when (entry.eventType) {
                LogEntry.TYPE_AUTH_UNLOCK -> R.color.event_unlock
                LogEntry.TYPE_APP_OPENED -> R.color.event_opened
                LogEntry.TYPE_APP_CLOSED -> R.color.event_closed
                LogEntry.TYPE_APP_FOCUS -> R.color.event_focus
                LogEntry.TYPE_CALL_INCOMING -> R.color.event_call
                LogEntry.TYPE_SMS_RECEIVED -> R.color.event_sms
                LogEntry.TYPE_WHATSAPP_CALL -> R.color.event_whatsapp
                LogEntry.TYPE_WHATSAPP_MSG -> R.color.event_whatsapp
                else -> R.color.event_focus
            }
            binding.textEventType.setTextColor(
                binding.root.context.getColor(colorRes)
            )

            // Details — show friendly name if available
            binding.textDetails.text = entry.appName ?: entry.details

            // Package name as subtitle (only for app events)
            if (entry.eventType == LogEntry.TYPE_WHATSAPP_MSG || entry.eventType == LogEntry.TYPE_WHATSAPP_CALL) {
                val parts = entry.details.split("|", limit = 2)
                binding.textPackage.text = parts.getOrNull(1) ?: "WhatsApp"
                binding.textPackage.visibility = android.view.View.VISIBLE
            } else if (entry.appName != null && entry.appName != entry.details) {
                binding.textPackage.text = entry.details
                binding.textPackage.visibility = android.view.View.VISIBLE
            } else {
                binding.textPackage.visibility = android.view.View.GONE
            }

            // Timestamp
            binding.textTimestamp.text = dateFormat.format(Date(entry.timestamp))

            // Duration
            if (entry.durationMillis != null && entry.durationMillis > 0) {
                binding.textDuration.text = formatDuration(entry.durationMillis)
                binding.textDuration.visibility = android.view.View.VISIBLE
            } else {
                binding.textDuration.visibility = android.view.View.GONE
            }
        }

        private fun formatDuration(millis: Long): String {
            val hours = TimeUnit.MILLISECONDS.toHours(millis)
            val minutes = TimeUnit.MILLISECONDS.toMinutes(millis) - TimeUnit.HOURS.toMinutes(hours)
            val seconds = TimeUnit.MILLISECONDS.toSeconds(millis) - TimeUnit.MINUTES.toSeconds(TimeUnit.MILLISECONDS.toMinutes(millis))
            
            return when {
                hours > 0 -> String.format("%dh %02dm %02ds", hours, minutes, seconds)
                minutes > 0 -> String.format("%dm %02ds", minutes, seconds)
                else -> String.format("%ds", seconds)
            }
        }
    }

    private class LogDiffCallback : DiffUtil.ItemCallback<LogEntry>() {
        override fun areItemsTheSame(old: LogEntry, new: LogEntry) = old.id == new.id
        override fun areContentsTheSame(old: LogEntry, new: LogEntry) = old == new
    }
}
