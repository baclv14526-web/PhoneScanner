package com.dieppham.phonescanner

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
abstract class ScanRecordDao {

    @Insert
    abstract suspend fun insert(record: ScanRecord): Long

    @Insert
    abstract suspend fun insertAll(records: List<ScanRecord>)

    @Query("SELECT * FROM scan_records")
    abstract suspend fun getAllRecords(): List<ScanRecord>

    @Query("SELECT * FROM phone_notes")
    abstract suspend fun getAllNotes(): List<PhoneNote>

    @Query("SELECT * FROM scan_records WHERE phoneNumber = :phone ORDER BY timestamp DESC, id DESC LIMIT 1")
    abstract suspend fun latestFor(phone: String): ScanRecord?

    @Query("UPDATE scan_records SET scanCount = scanCount + 1, timestamp = :ts WHERE id = :id")
    abstract suspend fun bumpScan(id: Long, ts: Long)

    /**
     * Ghi 1 lượt quét. Nếu cùng số đã được quét trong vòng [MERGE_WINDOW_MS] kể từ lần quét
     * gần nhất của số đó thì gộp: tăng scanCount và cập nhật giờ về lần quét mới nhất.
     * Chạy trong transaction để 2 lượt quét sát nhau không tạo 2 dòng.
     */
    @Transaction
    open suspend fun logScan(phone: String, carrier: String, now: Long) {
        val last = latestFor(phone)
        if (last != null && now - last.timestamp in 0..MERGE_WINDOW_MS) {
            bumpScan(last.id, now)
        } else {
            insert(ScanRecord(phoneNumber = phone, carrier = carrier, timestamp = now))
        }
    }

    /** Các bản ghi trong khoảng [from, to] (epoch ms), mới nhất trước. */
    @Query(
        "SELECT * FROM scan_records " +
            "WHERE timestamp BETWEEN :from AND :to " +
            "ORDER BY timestamp DESC, id DESC"
    )
    abstract fun observeRange(from: Long, to: Long): Flow<List<ScanRecord>>

    /** Tổng số lần quét của từng số trong toàn bộ nhật ký (không phụ thuộc bộ lọc thời gian). */
    @Query("SELECT phoneNumber, SUM(scanCount) AS cnt FROM scan_records GROUP BY phoneNumber")
    abstract fun observeCounts(): Flow<List<ScanCount>>

    @Query("DELETE FROM scan_records WHERE id = :id")
    abstract suspend fun deleteById(id: Long)

    @Query("DELETE FROM scan_records")
    abstract suspend fun deleteAll()

    // ---- Ghi chú theo số điện thoại (giữ nguyên khi xóa nhật ký) ----

    @Query("SELECT * FROM phone_notes")
    abstract fun observeNotes(): Flow<List<PhoneNote>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsertNote(note: PhoneNote)

    @Query("DELETE FROM phone_notes WHERE phoneNumber = :phone")
    abstract suspend fun deleteNote(phone: String)

    companion object {
        const val MERGE_WINDOW_MS = 60_000L
    }
}
