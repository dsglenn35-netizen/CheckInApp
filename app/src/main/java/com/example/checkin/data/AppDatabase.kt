package com.example.checkin.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        CheckInRecord::class,
        CheckInRule::class,
        LeaveDay::class,
        TimeEntry::class,
        CheckInSite::class
    ],
    version = 8,
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

        /** v4 → v5：多地点打卡点表 + 记录按规则主键去重 + 班制与考勤时刻字段 */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 附加打卡地点（一个规则可挂多个点位）
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS check_in_sites (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "ruleId INTEGER NOT NULL, " +
                        "name TEXT NOT NULL, " +
                        "latitude REAL NOT NULL, " +
                        "longitude REAL NOT NULL, " +
                        "radiusMeters REAL NOT NULL, " +
                        "wifiSsid TEXT)"
                )
                // 记录增加命中规则主键 / 验证方式 / 时钟偏差
                // 旧记录 ruleId 置 0（未知），查询侧按 ruleName 回退匹配
                db.execSQL("ALTER TABLE check_in_records ADD COLUMN ruleId INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE check_in_records ADD COLUMN matchSource TEXT NOT NULL DEFAULT 'GPS'")
                db.execSQL("ALTER TABLE check_in_records ADD COLUMN clockSkewMs INTEGER")
                // 规则增加班制、应到/应离时刻与主地点 WiFi
                db.execSQL("ALTER TABLE check_in_rules ADD COLUMN shiftPattern TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE check_in_rules ADD COLUMN requiredStartMinute INTEGER NOT NULL DEFAULT -1")
                db.execSQL("ALTER TABLE check_in_rules ADD COLUMN requiredEndMinute INTEGER NOT NULL DEFAULT -1")
                db.execSQL("ALTER TABLE check_in_rules ADD COLUMN wifiSsid TEXT")
            }
        }

        /** v5 → v6：特殊日标记区分请假 / 放假，旧数据一律按请假处理 */
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE leave_days ADD COLUMN kind TEXT NOT NULL DEFAULT 'LEAVE'"
                )
            }
        }

        /** v6 → v7：规则增加"需要下班卡"，旧规则保持"打卡一次即完成" */
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE check_in_rules " +
                        "ADD COLUMN requireCheckOut INTEGER NOT NULL DEFAULT 0"
                )
            }
        }

        /** v7 → v8：记录增加审计留痕（来源 / 首次修正时间 / 原始打卡时刻） */
        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 旧记录一律视为自动打卡；审计字段留空表示"未被修正过"
                db.execSQL(
                    "ALTER TABLE check_in_records " +
                        "ADD COLUMN origin TEXT NOT NULL DEFAULT 'AUTO'"
                )
                db.execSQL("ALTER TABLE check_in_records ADD COLUMN editedAt INTEGER")
                db.execSQL("ALTER TABLE check_in_records ADD COLUMN originalTimestamp INTEGER")
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
                    .addMigrations(
                        MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5,
                        MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8
                    )
                    .build()
                    .also { instance = it }
            }
    }
}
