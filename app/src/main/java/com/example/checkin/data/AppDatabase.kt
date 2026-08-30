package com.example.checkin.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [CheckInRecord::class, CheckInRule::class, LeaveDay::class, TimeEntry::class],
    version = 4,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun checkInDao(): CheckInDao

    companion object {
        /** v1 → v2：记录增加备注/照片列，规则增加生效星期列 */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE check_in_records ADD COLUMN note TEXT")
                db.execSQL("ALTER TABLE check_in_records ADD COLUMN photoPath TEXT")
                db.execSQL("ALTER TABLE check_in_rules ADD COLUMN daysOfWeek INTEGER NOT NULL DEFAULT 127")
            }
        }

        /** v2 → v3：新增请假模式表 */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS leave_days (date TEXT NOT NULL PRIMARY KEY)")
            }
        }

        /** v3 → v4：新增时间段标注表（按时间段请假/加班） */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS time_entries (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "date TEXT NOT NULL, " +
                        "type TEXT NOT NULL, " +
                        "startMinute INTEGER NOT NULL, " +
                        "endMinute INTEGER NOT NULL, " +
                        "note TEXT)"
                )
            }
        }

        @Volatile
        private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "checkin.db"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                    .build()
                    .also { instance = it }
            }
    }
}
