package com.dieppham.phonescanner

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Mỗi lần bấm "Gọi" sau khi quét số sẽ tạo 1 bản ghi CallRecord.
 * timestamp: epoch milliseconds (System.currentTimeMillis())
 */
@Entity(tableName = "call_records")
data class CallRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val phoneNumber: String,
    val displayNumber: String,
    val timestamp: Long,
    val isPinned: Boolean = false,
    val pinnedAt: Long = 0L      // epoch ms lúc ghim, dùng để sort trong section ghim
)
