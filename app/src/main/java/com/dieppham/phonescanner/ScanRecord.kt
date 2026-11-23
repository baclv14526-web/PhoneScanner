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
 * timestamp: epoch milliseconds của LẦN QUÉT GẦN NHẤT trong bản ghi
 * scanCount: số lần quét đã gộp — cùng số quét lại trong vòng 1 phút (so với lần quét trước đó)
 *            thì cộng vào bản ghi cũ thay vì tạo dòng mới
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
    val timestamp: Long,
    val scanCount: Int = 1
)

/** Ghi chú của một số điện thoại (mỗi số tối đa 1 ghi chú, dùng chung cho mọi lượt quét). */
@Entity(tableName = "phone_notes")
data class PhoneNote(
    @PrimaryKey val phoneNumber: String,
    val note: String,
    val updatedAt: Long
)

/** Kết quả gom nhóm: mỗi số đã được quét tổng cộng bao nhiêu lần (cộng scanCount). */
data class ScanCount(
    val phoneNumber: String,
    val cnt: Int
)
