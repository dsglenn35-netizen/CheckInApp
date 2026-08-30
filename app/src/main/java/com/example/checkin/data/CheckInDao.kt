package com.example.checkin.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface CheckInDao {

    @Insert
    suspend fun insertRecord(record: CheckInRecord): Long

    @Insert
    suspend fun insertRule(rule: CheckInRule): Long

    @Update
    suspend fun updateRule(rule: CheckInRule)

    @Delete
    suspend fun deleteRule(rule: CheckInRule)

    @Query("SELECT * FROM check_in_records ORDER BY timestamp DESC")
    fun observeRecords(): Flow<List<CheckInRecord>>

    @Query("SELECT * FROM check_in_rules ORDER BY id ASC")
    fun observeRules(): Flow<List<CheckInRule>>

    @Query("SELECT * FROM check_in_rules WHERE enabled = 1")
    suspend fun enabledRules(): List<CheckInRule>

    @Query(
        "SELECT * FROM check_in_records WHERE status = 'SUCCESS' " +
            "AND ruleName = :ruleName AND timestamp >= :since ORDER BY timestamp DESC LIMIT 1"
    )
    suspend fun lastSuccess(ruleName: String, since: Long): CheckInRecord?

    @Query(
        "SELECT * FROM check_in_records WHERE ruleName = :ruleName " +
            "AND timestamp > :since ORDER BY timestamp DESC LIMIT 1"
    )
    suspend fun lastRecord(ruleName: String, since: Long): CheckInRecord?

    @Query("SELECT * FROM check_in_records ORDER BY timestamp ASC")
    suspend fun allRecords(): List<CheckInRecord>

    @Query("SELECT * FROM check_in_rules ORDER BY id ASC")
    suspend fun allRules(): List<CheckInRule>

    @Delete
    suspend fun deleteRecord(record: CheckInRecord)

    @Query("UPDATE check_in_records SET note = :note WHERE id = :id")
    suspend fun updateRecordNote(id: Long, note: String?)

    /** 修正打卡记录（时间/地点等，主键定位，整行更新） */
    @Update
    suspend fun updateRecord(record: CheckInRecord)

    @Query("DELETE FROM check_in_records")
    suspend fun clearRecords()

    @Query("DELETE FROM check_in_rules")
    suspend fun clearRules()

    @Insert
    suspend fun insertRules(rules: List<CheckInRule>)

    @Insert
    suspend fun insertRecords(records: List<CheckInRecord>)

    // ---------- 请假模式 ----------

    @Query("SELECT * FROM leave_days ORDER BY date ASC")
    fun observeLeaveDays(): Flow<List<LeaveDay>>

    @Query("SELECT * FROM leave_days WHERE date = :date LIMIT 1")
    suspend fun leaveDay(date: String): LeaveDay?

    @Query("SELECT * FROM leave_days ORDER BY date ASC")
    suspend fun allLeaveDays(): List<LeaveDay>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLeaveDay(day: LeaveDay)

    @Insert
    suspend fun insertLeaveDays(days: List<LeaveDay>)

    @Query("DELETE FROM leave_days")
    suspend fun clearLeaveDays()

    @Delete
    suspend fun deleteLeaveDay(day: LeaveDay)

    // ---------- 时间段标注（请假/加班） ----------

    @Query("SELECT * FROM time_entries ORDER BY date ASC, startMinute ASC")
    fun observeTimeEntries(): Flow<List<TimeEntry>>

    @Query("SELECT * FROM time_entries WHERE date = :date ORDER BY startMinute ASC")
    suspend fun timeEntriesForDate(date: String): List<TimeEntry>

    @Query("SELECT * FROM time_entries ORDER BY date ASC, startMinute ASC")
    suspend fun allTimeEntries(): List<TimeEntry>

    @Insert
    suspend fun insertTimeEntry(entry: TimeEntry)

    @Insert
    suspend fun insertTimeEntries(entries: List<TimeEntry>)

    @Query("DELETE FROM time_entries")
    suspend fun clearTimeEntries()

    @Delete
    suspend fun deleteTimeEntry(entry: TimeEntry)
}
