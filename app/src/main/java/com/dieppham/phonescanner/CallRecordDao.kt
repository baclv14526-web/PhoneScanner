package com.dieppham.phonescanner

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface CallRecordDao {

    @Insert
    suspend fun insert(record: CallRecord)

    // Sắp xếp: ghim trước (pinnedAt DESC trong nhóm ghim), sau đó theo timestamp DESC
    @Query("SELECT * FROM call_records ORDER BY isPinned DESC, pinnedAt DESC, timestamp DESC")
    fun getAllRecords(): Flow<List<CallRecord>>

    @Query("""
        SELECT phoneNumber, COUNT(*) as callCount, MAX(timestamp) as lastCall
        FROM call_records
        GROUP BY phoneNumber
        ORDER BY lastCall DESC
    """)
    fun getCallStats(): Flow<List<CallStats>>

    @Query("UPDATE call_records SET isPinned = 1, pinnedAt = :pinnedAt WHERE id = :id")
    suspend fun pinRecord(id: Long, pinnedAt: Long)

    @Query("UPDATE call_records SET isPinned = 0, pinnedAt = 0 WHERE id = :id")
    suspend fun unpinRecord(id: Long)

    @Query("DELETE FROM call_records WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM call_records")
    suspend fun deleteAll()
}

/** Kết quả tổng hợp — không phải Entity, chỉ dùng để đọc */
data class CallStats(
    val phoneNumber: String,
    val callCount: Int,
    val lastCall: Long
)
