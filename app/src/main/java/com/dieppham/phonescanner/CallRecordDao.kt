package com.dieppham.phonescanner

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface CallRecordDao {

    @Insert
    suspend fun insert(record: CallRecord)

    /**
     * Trả về toàn bộ lịch sử, mới nhất trước.
     * Flow tự động emit lại mỗi khi DB thay đổi — không cần manual refresh.
     */
    @Query("SELECT * FROM call_records ORDER BY timestamp DESC")
    fun getAllRecords(): Flow<List<CallRecord>>

    /**
     * Đếm số lần gọi cho mỗi số điện thoại (dùng cho badge "x lần").
     */
    @Query("""
        SELECT phoneNumber, COUNT(*) as callCount, MAX(timestamp) as lastCall
        FROM call_records
        GROUP BY phoneNumber
        ORDER BY lastCall DESC
    """)
    fun getCallStats(): Flow<List<CallStats>>

    @Query("DELETE FROM call_records")
    suspend fun deleteAll()
}

/** Kết quả tổng hợp — không phải Entity, chỉ dùng để đọc */
data class CallStats(
    val phoneNumber: String,
    val callCount: Int,
    val lastCall: Long
)
