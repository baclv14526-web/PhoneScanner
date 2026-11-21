package com.dieppham.phonescanner

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ScanRecordDao {

    @Insert
    suspend fun insertAll(records: List<ScanRecord>)

    /** Các lượt quét trong khoảng [from, to] (epoch ms), mới nhất trước. */
    @Query(
        "SELECT * FROM scan_records " +
            "WHERE timestamp BETWEEN :from AND :to " +
            "ORDER BY timestamp DESC, id DESC"
    )
    fun observeRange(from: Long, to: Long): Flow<List<ScanRecord>>

    /** Tổng số lần quét của từng số trong toàn bộ nhật ký (không phụ thuộc bộ lọc thời gian). */
    @Query("SELECT phoneNumber, COUNT(*) AS cnt FROM scan_records GROUP BY phoneNumber")
    fun observeCounts(): Flow<List<ScanCount>>

    @Query("DELETE FROM scan_records WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM scan_records")
    suspend fun deleteAll()
}
