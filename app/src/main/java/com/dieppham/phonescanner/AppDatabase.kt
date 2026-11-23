package com.dieppham.phonescanner

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [CallRecord::class, ScanRecord::class, PhoneNote::class], version = 5, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {

    abstract fun callRecordDao(): CallRecordDao
    abstract fun scanRecordDao(): ScanRecordDao

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

        // Migration 2→3: thêm index. Tên index phải đúng quy ước tự đặt của Room
        // (index_<bảng>_<cột>_<cột>) nếu không Room báo schema không khớp.
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_call_records_phoneNumber` " +
                        "ON `call_records` (`phoneNumber`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_call_records_isPinned_pinnedAt_timestamp` " +
                        "ON `call_records` (`isPinned`, `pinnedAt`, `timestamp`)"
                )
            }
        }

        // Migration 3→4: thêm bảng nhật ký quét (scan_records). SQL phải khớp đúng schema
        // Room tự sinh cho ScanRecord (kiểu cột, NOT NULL, khóa chính, tên index), nếu không
        // Room sẽ báo "Migration didn't properly handle" khi mở DB.
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `scan_records` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`phoneNumber` TEXT NOT NULL, " +
                        "`carrier` TEXT NOT NULL, " +
                        "`timestamp` INTEGER NOT NULL)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_scan_records_timestamp` " +
                        "ON `scan_records` (`timestamp`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_scan_records_phoneNumber` " +
                        "ON `scan_records` (`phoneNumber`)"
                )
            }
        }

        // Migration 4→5: thêm cột scanCount (gộp lượt quét) và bảng phone_notes (ghi chú theo số).
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `scan_records` ADD COLUMN `scanCount` INTEGER NOT NULL DEFAULT 1")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `phone_notes` (" +
                        "`phoneNumber` TEXT NOT NULL, " +
                        "`note` TEXT NOT NULL, " +
                        "`updatedAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`phoneNumber`))"
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
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
                .build()
                .also { INSTANCE = it }
            }
    }
}
