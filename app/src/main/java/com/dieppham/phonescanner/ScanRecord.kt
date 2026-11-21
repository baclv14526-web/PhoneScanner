package com.dieppham.phonescanner

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Nhật ký quét: mỗi số điện thoại được nhận diện (camera hoặc ảnh thư viện) tạo 1 bản ghi,
 * không phụ thuộc việc người dùng có bấm gọi hay không.
 * Tách biệt hoàn toàn với CallRecord (lịch sử cuộc gọi).
 *
 * carrier:   tên nhà mạng theo đầu số tại thời điểm quét (xem CarrierDetector)
 * timestamp: epoch milliseconds lúc quét
 */
@Entity(
    tableName = "scan_records",
    indices = [
        Index(value = ["timestamp"]),
        Index(value = ["phoneNumber"])
    ]
)
data class ScanRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val phoneNumber: String,
    val carrier: String,
    val timestamp: Long
)

/** Kết quả gom nhóm: mỗi số đã được quét tổng cộng bao nhiêu lần. */
data class ScanCount(
    val phoneNumber: String,
    val cnt: Int
)
