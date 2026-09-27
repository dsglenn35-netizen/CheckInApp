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

    /**
     * 同一规则在 [since] 之后是否已有成功记录（自动打卡"同一时段只记一次"去重）。
     *
     * 优先按规则主键匹配；[ruleId] 为 0（v2.4 及更早的旧记录）时回退按规则名匹配，
     * 保证升级后历史去重语义不丢失，同时避免两条同名规则互相污染冷却窗口。
     */
    @Query(
        "SELECT * FROM check_in_records WHERE status = 'SUCCESS' " +
            "AND ((:ruleId > 0 AND ruleId = :ruleId) " +
            "OR (:ruleId = 0 AND ruleName = :ruleName)) " +
            "AND timestamp >= :since ORDER BY timestamp DESC LIMIT 1"
    )
    suspend fun lastSuccessForRule(ruleId: Long, ruleName: String, since: Long): CheckInRecord?

    /** 同一规则在 [since] 之后的任意记录（自动打卡失败冷却），匹配规则同 [lastSuccessForRule] */
    @Query(
        "SELECT * FROM check_in_records WHERE " +
            "((:ruleId > 0 AND ruleId = :ruleId) " +
            "OR (:ruleId = 0 AND ruleName = :ruleName)) " +
            "AND timestamp > :since ORDER BY timestamp DESC LIMIT 1"
    )
    suspend fun lastRecordForRule(ruleId: Long, ruleName: String, since: Long): CheckInRecord?

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

    /** 批量写入特殊日标记：按区间标记时需要覆盖已有标记，故用 REPLACE */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
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

    // ---------- 附加打卡地点（一个规则多点位） ----------

    @Query("SELECT * FROM check_in_sites ORDER BY ruleId ASC, id ASC")
    fun observeSites(): Flow<List<CheckInSite>>

    @Query("SELECT * FROM check_in_sites ORDER BY ruleId ASC, id ASC")
    suspend fun allSites(): List<CheckInSite>

    @Query("SELECT * FROM check_in_sites WHERE ruleId = :ruleId ORDER BY id ASC")
    suspend fun sitesForRule(ruleId: Long): List<CheckInSite>

    @Insert
    suspend fun insertSite(site: CheckInSite): Long

    @Insert
    suspend fun insertSites(sites: List<CheckInSite>)

    @Delete
    suspend fun deleteSite(site: CheckInSite)

    @Query("DELETE FROM check_in_sites WHERE ruleId = :ruleId")
    suspend fun deleteSitesForRule(ruleId: Long)

    @Query("DELETE FROM check_in_sites")
    suspend fun clearSites()
}
