package com.logger.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface LogDao {

    @Insert
    suspend fun insertLog(entry: LogEntry)

    @Query("SELECT * FROM log_entries ORDER BY timestamp DESC")
    fun getAllLogs(): Flow<List<LogEntry>>

    @Query("SELECT * FROM log_entries WHERE eventType = :type ORDER BY timestamp DESC")
    fun getLogsByType(type: String): Flow<List<LogEntry>>

    @Query("DELETE FROM log_entries")
    suspend fun clearAllLogs()

    @Query("SELECT COUNT(*) FROM log_entries")
    suspend fun getLogCount(): Int
}
