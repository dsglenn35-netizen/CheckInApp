package com.example.checkin.data

import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow

class CheckInRepository(private val db: AppDatabase) {

    private val dao: CheckInDao = db.checkInDao()

    /**
     * 把多步写入包进单个事务：中途失败整体回滚，不会留下
     * "附加地点已删、规则未更新"或"已清空旧数据、新数据未写入"这类半套状态。
     * [androidx.room.RoomDatabase.withTransaction] 可重入，嵌套调用安全。
     */
    suspend fun <T> inTransaction(block: suspend () -> T): T = db.withTransaction { block() }

    val records: Flow<List<CheckInRecord>> = dao.observeRecords()
    val rules: Flow<List<CheckInRule>> = dao.observeRules()
    val sites: Flow<List<CheckInSite>> = dao.observeSites()

    suspend fun insertRecord(record: CheckInRecord) {
        dao.insertRecord(record)
    }

    suspend fun insertRule(rule: CheckInRule): Long = dao.insertRule(rule)

    suspend fun updateRule(rule: CheckInRule) {
        dao.updateRule(rule)
    }

    suspend fun deleteRule(rule: CheckInRule) = db.withTransaction {
        dao.deleteRule(rule)
        dao.deleteSitesForRule(rule.id)
    }

    suspend fun enabledRules(): List<CheckInRule> = dao.enabledRules()

    /**
     * 同一规则的某个**打卡槽位** [from, to) 内是否已有成功记录（自动打卡去重）。
     * 按规则主键匹配；旧记录 ruleId=0 时回退按规则名匹配。
     */
    suspend fun lastSuccessForRuleInRange(
        ruleId: Long,
        ruleName: String,
        from: Long,
        to: Long
    ): CheckInRecord? = dao.lastSuccessForRuleInRange(ruleId, ruleName, from, to)

    /** 失败冷却查询（按规则主键匹配，旧记录回退按名匹配） */
    suspend fun lastRecordForRule(ruleId: Long, ruleName: String, since: Long): CheckInRecord? =
        dao.lastRecordForRule(ruleId, ruleName, since)

    // ---------- 附加打卡地点 ----------

    /** 按规则主键分组的附加地点（打卡校验用） */
    suspend fun sitesGroupedByRule(): Map<Long, List<CheckInSite>> =
        dao.allSites().groupBy { it.ruleId }

    suspend fun sitesForRule(ruleId: Long): List<CheckInSite> = dao.sitesForRule(ruleId)

    suspend fun allSites(): List<CheckInSite> = dao.allSites()

    suspend fun insertSite(site: CheckInSite): Long = dao.insertSite(site)

    suspend fun insertSites(sites: List<CheckInSite>) {
        if (sites.isNotEmpty()) dao.insertSites(sites)
    }

    suspend fun deleteSite(site: CheckInSite) {
        dao.deleteSite(site)
    }

    suspend fun deleteSitesForRule(ruleId: Long) {
        dao.deleteSitesForRule(ruleId)
    }

    suspend fun clearSites() {
        dao.clearSites()
    }

    /** 全部记录快照（用于导出） */
    suspend fun allRecords(): List<CheckInRecord> = dao.allRecords()

    /** 按主键取单条（修正时读取原值，用于审计留痕） */
    suspend fun recordById(id: Long): CheckInRecord? = dao.recordById(id)

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

    /** 清空全部规则（同时清理规则下挂的附加地点，同一事务） */
    suspend fun clearRulesAndSites() = db.withTransaction {
        dao.clearSites()
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

    // ---------- 调班 / 调休（按日期覆盖规则默认生效日） ----------

    val shiftOverrides: Flow<List<ShiftOverride>> = dao.observeShiftOverrides()

    /** 打卡判定用的查找表（key -> 当天是否上班） */
    suspend fun shiftOverrideTable(): Map<String, Boolean> =
        dao.allShiftOverrides().associate {
            ShiftOverride.key(it.date, it.ruleId) to it.working
        }

    suspend fun allShiftOverrides(): List<ShiftOverride> = dao.allShiftOverrides()

    suspend fun insertShiftOverride(override: ShiftOverride) {
        dao.insertShiftOverride(override)
    }

    suspend fun insertShiftOverrides(overrides: List<ShiftOverride>) {
        if (overrides.isNotEmpty()) dao.insertShiftOverrides(overrides)
    }

    suspend fun deleteShiftOverride(date: String, ruleId: Long) {
        dao.deleteShiftOverride(date, ruleId)
    }

    suspend fun clearShiftOverrides() {
        dao.clearShiftOverrides()
    }
}
