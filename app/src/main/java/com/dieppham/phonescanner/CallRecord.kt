package com.dieppham.phonescanner

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Mỗi lần bấm "Gọi" sau khi quét số sẽ tạo 1 bản ghi CallRecord.
 * timestamp: epoch milliseconds (System.currentTimeMillis())
 *
 * Index:
 *  - phoneNumber: dùng cho GROUP BY phoneNumber trong getCallStats() và
 *    getDailyStatsList() — 2 query chạy lại mỗi khi có insert/delete/pin
 *  - (isPinned, pinnedAt, timestamp): khớp CHÍNH XÁC thứ tự ORDER BY của
 *    getAllRecords() — query chính hiện toàn bộ lịch sử, chạy mỗi lần mở
 *    màn hình History và mỗi lần DB thay đổi
 */
@Entity(
    tableName = "call_records",
    indices = [
        Index(value = ["phoneNumber"]),
        Index(value = ["isPinned", "pinnedAt", "timestamp"])
    ]
)
data class CallRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val phoneNumber: String,
    val displayNumber: String,
    val timestamp: Long,
    val isPinned: Boolean = false,
    val pinnedAt: Long = 0L      // epoch ms lúc ghim, dùng để sort trong section ghim
)
