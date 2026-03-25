package com.logger.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface LogDao {

    @Insert
    suspend fun insertLog(entry: LogEntry)

    @Query("""
        SELECT * FROM log_entries 
        WHERE (:type IS NULL OR eventType = :type) 
          AND (:pkg IS NULL OR details = :pkg) 
          AND eventType NOT IN ('CALL_INCOMING', 'SMS_RECEIVED')
          AND timestamp BETWEEN :startTimestamp AND :endTimestamp 
        ORDER BY timestamp DESC
    """)
    fun getFilteredLogs(type: String?, pkg: String?, startTimestamp: Long, endTimestamp: Long): Flow<List<LogEntry>>

    @Query("SELECT DISTINCT details FROM log_entries WHERE eventType IN ('APP_OPENED', 'APP_CLOSED', 'APP_FOCUS') ORDER BY details ASC")
    fun getDistinctPackages(): Flow<List<String>>

    @Query("DELETE FROM log_entries")
    suspend fun clearAllLogs()

    @Query("SELECT * FROM log_entries WHERE eventType = 'CALL_INCOMING' AND timestamp BETWEEN :startTimestamp AND :endTimestamp ORDER BY timestamp DESC")
    fun getCallLogs(startTimestamp: Long, endTimestamp: Long): Flow<List<LogEntry>>

    @Query("SELECT * FROM log_entries WHERE eventType = 'SMS_RECEIVED' AND timestamp BETWEEN :startTimestamp AND :endTimestamp ORDER BY timestamp DESC")
    fun getSmsLogs(startTimestamp: Long, endTimestamp: Long): Flow<List<LogEntry>>

    @Query("SELECT * FROM log_entries WHERE eventType IN ('WHATSAPP_CALL', 'WHATSAPP_MSG') AND timestamp BETWEEN :startTimestamp AND :endTimestamp ORDER BY timestamp DESC")
    fun getWhatsappLogs(startTimestamp: Long, endTimestamp: Long): Flow<List<LogEntry>>

    @Query("SELECT * FROM log_entries WHERE timestamp BETWEEN :startTimestamp AND :endTimestamp ORDER BY timestamp DESC")
    suspend fun getAllLogsInRange(startTimestamp: Long, endTimestamp: Long): List<LogEntry>

    @Query("SELECT COUNT(*) FROM log_entries")
    suspend fun getLogCount(): Int
}
