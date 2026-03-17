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
          AND timestamp BETWEEN :startTimestamp AND :endTimestamp 
        ORDER BY timestamp DESC
    """)
    fun getFilteredLogs(type: String?, pkg: String?, startTimestamp: Long, endTimestamp: Long): Flow<List<LogEntry>>

    @Query("SELECT DISTINCT details FROM log_entries WHERE eventType IN ('APP_OPENED', 'APP_CLOSED', 'APP_FOCUS') ORDER BY details ASC")
    fun getDistinctPackages(): Flow<List<String>>

    @Query("DELETE FROM log_entries")
    suspend fun clearAllLogs()

    @Query("SELECT COUNT(*) FROM log_entries")
    suspend fun getLogCount(): Int
}
