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
    val phoneNumber: String,          // dạng chuẩn "0912345678"
    val displayNumber: String,        // dạng hiển thị "0912 345 678"
    val timestamp: Long               // epoch ms
)
