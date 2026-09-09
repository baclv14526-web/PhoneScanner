package com.dieppham.phonescanner

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface CallRecordDao {

    @Insert
    suspend fun insert(record: CallRecord)

    @Query("SELECT * FROM call_records ORDER BY isPinned DESC, pinnedAt DESC, timestamp DESC")
    fun getAllRecords(): Flow<List<CallRecord>>

    // Tổng số lần gọi mỗi số (dùng cho badge section ghim + tooltip)
    @Query("""
        SELECT phoneNumber, COUNT(*) as callCount, MAX(timestamp) as lastCall
        FROM call_records
        GROUP BY phoneNumber
        ORDER BY lastCall DESC
    """)
    fun getCallStats(): Flow<List<CallStats>>

    // Flow version — dùng trong combine
    @Query("""
        SELECT phoneNumber, COUNT(*) as callCount, MAX(timestamp) as lastCall,
               strftime('%Y-%m-%d', timestamp/1000, 'unixepoch') as dayKey
        FROM call_records
        WHERE isPinned = 0
        GROUP BY phoneNumber, dayKey
        ORDER BY lastCall DESC
    """)
    fun getDailyStats(): Flow<List<DailyCallStats>>

    // Suspend one-shot — dùng trong nested coroutine (không cần Flow)
    @Query("""
        SELECT phoneNumber, COUNT(*) as callCount, MAX(timestamp) as lastCall,
               strftime('%Y-%m-%d', timestamp/1000, 'unixepoch') as dayKey
        FROM call_records
        WHERE isPinned = 0
        GROUP BY phoneNumber, dayKey
        ORDER BY lastCall DESC
    """)
    suspend fun getDailyStatsList(): List<DailyCallStats>

    @Query("UPDATE call_records SET isPinned = 1, pinnedAt = :pinnedAt WHERE id = :id")
    suspend fun pinRecord(id: Long, pinnedAt: Long)

    @Query("UPDATE call_records SET isPinned = 0, pinnedAt = 0 WHERE id = :id")
    suspend fun unpinRecord(id: Long)

    @Query("DELETE FROM call_records WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM call_records")
    suspend fun deleteAll()
}

data class CallStats(
    val phoneNumber: String,
    val callCount: Int,
    val lastCall: Long
)

/** Số lần gọi mỗi số trong từng ngày cụ thể */
data class DailyCallStats(
    val phoneNumber: String,
    val callCount: Int,
    val lastCall: Long,
    val dayKey: String      // format "yyyy-MM-dd"
)
