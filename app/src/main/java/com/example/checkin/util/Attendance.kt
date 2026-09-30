package com.example.checkin.util

import com.example.checkin.data.CheckInRecord
import com.example.checkin.data.CheckInRule
import com.example.checkin.data.CheckStatus
import java.time.LocalDate
import java.time.ZoneId

/**
 * 一个**班次**（某条规则的一次窗口实例）的打卡归集。
 *
 * 存在的意义是把"在岗时长"从"当天首次打卡 → 当天末次打卡"改成**分班次累加**：
 * 上午班 09:00-12:00 与下午班 14:00-18:00 之间的午休不属于在岗时间，
 * 用全天首末相减会把这段空档算进工时（每天虚增 1 小时以上，交给 HR 就是假数据）。
 *
 * 时刻比较统一以**班次窗口起点**为基准换算成偏移分钟，
 * 因此跨午夜班次（22:00-06:00）的凌晨打卡也能正确参与迟到 / 早退判定。
 */
data class ShiftAttendance(
    val rule: CheckInRule,
    /** 班次窗口起点（毫秒），即 rule 的开始时刻在考勤日那一天的具体时刻 */
    val windowStart: Long,
    /** 归属本班次的成功打卡时刻，升序 */
    val punches: List<Long>
) {
    val hasPunch: Boolean get() = punches.isNotEmpty()
    val firstIn: Long? get() = punches.firstOrNull()
    val lastOut: Long? get() = punches.lastOrNull()

    /** 规则开始时刻的当天分钟数 */
    private val startClockMinute: Int get() = rule.startHour * 60 + rule.startMinute

    /** 打卡时刻相对窗口起点的偏移分钟 */
    private fun offset(t: Long): Int = ((t - windowStart) / 60_000L).toInt()

    /** 某个钟点相对窗口起点的偏移分钟；早于窗口起点即视为跨过午夜（+24 小时） */
    private fun clockOffset(clockMinute: Int): Int {
        val d = clockMinute - startClockMinute
        return if (d < 0) d + 24 * 60 else d
    }

    /**
     * 本班次在岗分钟。
     *
     * 只有一次打卡（缺上班卡或下班卡）时区间无法确定，返回 0；
     * 是否缺卡由 [missingPunch] 单独标出，避免"没打下班卡"被静默显示成 0 小时。
     */
    val workMinutes: Int
        get() = if (punches.size >= 2) ((punches.last() - punches.first()) / 60_000L).toInt() else 0

    /** 缺卡：完全没打卡，或只有一次打卡（无法构成完整的上下班） */
    val missingPunch: Boolean get() = punches.size < 2

    /** 迟到分钟：配置了应到时刻，且本班次首次打卡晚于它；跨午夜同样成立 */
    val lateMinutes: Int
        get() {
            // 自由工时制没有"应到时刻"这个概念：几点来不重要，干够工时才重要
            if (rule.flexible) return 0
            if (rule.requiredStartMinute < 0) return 0
            val first = firstIn ?: return 0
            return (offset(first) - clockOffset(rule.requiredStartMinute)).coerceAtLeast(0)
        }

    /** 早退分钟：配置了应离时刻，且本班次末次打卡早于它；跨午夜同样成立 */
    val earlyMinutes: Int
        get() {
            // 同上：自由工时制不判早退
            if (rule.flexible) return 0
            if (rule.requiredEndMinute < 0) return 0
            val last = lastOut ?: return 0
            return (clockOffset(rule.requiredEndMinute) - offset(last)).coerceAtLeast(0)
        }
}

/**
 * 考勤归集：把打卡记录整理成**按考勤日 + 按班次**的结构，供月度考勤表使用。
 *
 * 两个核心概念：
 * 1. **考勤日**不等于自然日 —— 跨午夜班次归属于"窗口开始的那一天"，
 *    否则 22:00-06:00 的夜班会被拆成两天，凌晨段还可能落到非工作日上；
 * 2. **班次**是工时与迟到早退的计算单位 —— 一天多段班时，
 *    用全天首末相减会把班次之间的空档算成在岗时间。
 */
object AttendanceCalculator {

    /** 记录命中的规则：优先按规则主键；ruleId = 0 的旧记录回退按规则名 */
    fun resolveRule(record: CheckInRecord, rules: List<CheckInRule>): CheckInRule? =
        if (record.ruleId > 0L) rules.firstOrNull { it.id == record.ruleId }
        else record.ruleName?.let { name -> rules.firstOrNull { it.name == name } }

    /** 记录是否属于该规则（主键优先，旧记录按名匹配） */
    fun belongsTo(record: CheckInRecord, rule: CheckInRule): Boolean =
        if (record.ruleId > 0L) record.ruleId == rule.id else record.ruleName == rule.name

    /**
     * 记录归属的**考勤日**。
     *
     * 成功记录按其所命中规则的班次窗口起点归属：跨午夜窗口的凌晨段会回到窗口开始那天，
     * 因此夜班不会被拆到第二天、也不会落在非工作日上。
     * 失败记录、未命中规则的记录回退到自然日。
     */
    fun attendanceDate(record: CheckInRecord, rules: List<CheckInRule>): LocalDate {
        val natural = record.timestamp.toLocalDate()
        // 外勤同样是"这个班次的打卡"，必须一起按窗口起点归属考勤日
        if (!CheckStatus.isAttended(record.status)) return natural
        val rule = resolveRule(record, rules) ?: return natural
        // 只有确实落在该规则窗口内时才按窗口起点归属，避免脏数据把记录挪到别的日期
        if (!CheckInValidator.isWithinTime(rule, record.timestamp)) return natural
        return CheckInValidator.windowStartMillis(rule, record.timestamp).toLocalDate()
    }

    /** 按考勤日分组 */
    fun groupByAttendanceDate(
        records: List<CheckInRecord>,
        rules: List<CheckInRule>
    ): Map<LocalDate, List<CheckInRecord>> =
        records.groupBy { attendanceDate(it, rules) }

    /** 某考勤日、某规则的班次窗口起点（该日 + 规则开始时刻） */
    fun windowStartFor(rule: CheckInRule, date: LocalDate): Long =
        date.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli() +
            (rule.startHour * 60 + rule.startMinute) * 60_000L

    /**
     * [date] 这一天各**应打卡班次**的归集（按班制 / 星期判定当天生效的规则，
     * 含轮转班制）。当天没有生效规则时返回空列表。
     *
     * [dayRecords] 应为**该考勤日**的全部记录（跨午夜班次的两个自然日的记录都在里面）。
     */
    fun shiftsFor(
        date: LocalDate,
        dayRecords: List<CheckInRecord>,
        rules: List<CheckInRule>
    ): List<ShiftAttendance> {
        // 外勤打卡也是这个班次的有效打卡：计入 punches，
        // 否则一天只有外勤时会被算成"缺卡"，工时与迟到早退也全丢
        val attended = dayRecords.filter { CheckStatus.isAttended(it.status) }
        return rules
            .filter { CheckInValidator.isActiveOnDate(it, date) }
            .map { rule ->
                ShiftAttendance(
                    rule = rule,
                    windowStart = windowStartFor(rule, date),
                    punches = attended.filter { belongsTo(it, rule) }
                        .map { it.timestamp }
                        .sorted()
                )
            }
    }
}
