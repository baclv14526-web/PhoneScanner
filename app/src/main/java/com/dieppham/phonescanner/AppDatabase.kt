package com.dieppham.phonescanner

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [CallRecord::class], version = 3, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {

    abstract fun callRecordDao(): CallRecordDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null

        // Migration 1→2: thêm 2 cột mới, giá trị mặc định 0
        // (SQLite không hỗ trợ ALTER TABLE ADD COLUMN với DEFAULT trên
        //  cột NOT NULL, nên để nullable với default 0 là an toàn nhất)
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE call_records ADD COLUMN isPinned INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE call_records ADD COLUMN pinnedAt INTEGER NOT NULL DEFAULT 0")
            }
        }

        // Migration 2→3: thêm index để tối ưu tốc độ GROUP BY / ORDER BY
        // khi lịch sử phát triển lớn dần. Tên index PHẢI khớp chính xác
        // với quy ước đặt tên tự động của Room (index_<table>_<cột nối bằng "_">)
        // — nếu không, Room sẽ báo lỗi schema mismatch ở lần mở app tiếp theo.
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_call_records_phoneNumber " +
                    "ON call_records(phoneNumber)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_call_records_isPinned_pinnedAt_timestamp " +
                    "ON call_records(isPinned, pinnedAt, timestamp)"
                )
            }
        }

        fun get(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "phonescanner.db"
                )
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                .build()
                .also { INSTANCE = it }
            }
    }
}
