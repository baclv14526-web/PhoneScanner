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

    // Số lần gọi mỗi số trong từng ngày — dùng để gom nhóm lịch sử theo ngày.
    //
    // QUAN TRỌNG: modifier 'localtime' bắt buộc phải có — strftime() mặc
    // định tính theo UTC. Nếu thiếu 'localtime', các cuộc gọi từ ~17h-24h
    // giờ Việt Nam (UTC+7) sẽ bị tính nhầm sang NGÀY HÔM SAU theo UTC,
    // trong khi phía Kotlin (SimpleDateFormat dùng giờ máy = local) lại
    // tính đúng ngày hôm đó — gây lệch dayKey giữa 2 bên, khiến
    // "x lần trong ngày" hiển thị sai gần nửa đêm.
    @Query("""
        SELECT phoneNumber, COUNT(*) as callCount, MAX(timestamp) as lastCall,
               strftime('%Y-%m-%d', timestamp/1000, 'unixepoch', 'localtime') as dayKey
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
