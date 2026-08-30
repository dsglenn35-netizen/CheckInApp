package com.example.checkin.data

import kotlinx.coroutines.flow.Flow

class CheckInRepository(private val dao: CheckInDao) {

    val records: Flow<List<CheckInRecord>> = dao.observeRecords()
    val rules: Flow<List<CheckInRule>> = dao.observeRules()

    suspend fun insertRecord(record: CheckInRecord) {
        dao.insertRecord(record)
    }

    suspend fun insertRule(rule: CheckInRule) {
        dao.insertRule(rule)
    }

    suspend fun updateRule(rule: CheckInRule) {
        dao.updateRule(rule)
    }

    suspend fun deleteRule(rule: CheckInRule) {
        dao.deleteRule(rule)
    }

    suspend fun enabledRules(): List<CheckInRule> = dao.enabledRules()

    /** 查询某规则在 since 之后最近一次成功打卡记录（用于自动打卡成功冷却） */
    suspend fun lastSuccess(ruleName: String, since: Long): CheckInRecord? =
        dao.lastSuccess(ruleName, since)

    /** 查询某规则在 since 之后最近一次任意打卡记录（用于自动打卡失败冷却） */
    suspend fun lastRecord(ruleName: String, since: Long): CheckInRecord? =
        dao.lastRecord(ruleName, since)

    /** 全部记录快照（用于导出） */
    suspend fun allRecords(): List<CheckInRecord> = dao.allRecords()

    /** 全部规则快照（用于导出备份） */
    suspend fun allRules(): List<CheckInRule> = dao.allRules()

    suspend fun deleteRecord(record: CheckInRecord) {
        dao.deleteRecord(record)
    }

    suspend fun updateRecordNote(id: Long, note: String?) {
        dao.updateRecordNote(id, note)
    }

    /** 修正打卡记录（整行更新，用于修正打卡时间/地点） */
    suspend fun updateRecord(record: CheckInRecord) {
        dao.updateRecord(record)
    }

    suspend fun clearRecords() {
        dao.clearRecords()
    }

    suspend fun clearRules() {
        dao.clearRules()
    }

    /** 批量插入（用于恢复备份） */
    suspend fun insertRules(rules: List<CheckInRule>) {
        if (rules.isNotEmpty()) dao.insertRules(rules)
    }

    /** 批量插入（用于恢复备份） */
    suspend fun insertRecords(records: List<CheckInRecord>) {
        if (records.isNotEmpty()) dao.insertRecords(records)
    }

    // ---------- 请假模式 ----------

    val leaveDays: Flow<List<LeaveDay>> = dao.observeLeaveDays()

    suspend fun leaveDay(date: String): LeaveDay? = dao.leaveDay(date)

    suspend fun allLeaveDays(): List<LeaveDay> = dao.allLeaveDays()

    suspend fun insertLeaveDay(day: LeaveDay) {
        dao.insertLeaveDay(day)
    }

    suspend fun insertLeaveDays(days: List<LeaveDay>) {
        if (days.isNotEmpty()) dao.insertLeaveDays(days)
    }

    suspend fun clearLeaveDays() {
        dao.clearLeaveDays()
    }

    suspend fun deleteLeaveDay(day: LeaveDay) {
        dao.deleteLeaveDay(day)
    }

    // ---------- 时间段标注（请假/加班） ----------

    val timeEntries: Flow<List<TimeEntry>> = dao.observeTimeEntries()

    suspend fun timeEntriesForDate(date: String): List<TimeEntry> =
        dao.timeEntriesForDate(date)

    suspend fun allTimeEntries(): List<TimeEntry> = dao.allTimeEntries()

    suspend fun insertTimeEntry(entry: TimeEntry) {
        dao.insertTimeEntry(entry)
    }

    suspend fun insertTimeEntries(entries: List<TimeEntry>) {
        if (entries.isNotEmpty()) dao.insertTimeEntries(entries)
    }

    suspend fun clearTimeEntries() {
        dao.clearTimeEntries()
    }

    suspend fun deleteTimeEntry(entry: TimeEntry) {
        dao.deleteTimeEntry(entry)
    }
}
