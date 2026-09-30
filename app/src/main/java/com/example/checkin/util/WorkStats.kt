package com.example.checkin.util

import com.example.checkin.data.CheckInRecord
import com.example.checkin.data.CheckInRule
import java.time.LocalDate
import java.time.YearMonth

/** 某一天的工作量（图表与趋势用） */
data class DayWork(
    val date: LocalDate,
    /** 当日在岗分钟数（各应打卡班次的在岗时长之和，班次之间的空档不计） */
    val workMinutes: Int,
    /** 当日应打卡班次数（受班制与调班表影响） */
    val dueShifts: Int,
    /** 当日有效出勤的班次数（正常 + 外勤） */
    val attendedShifts: Int
) {
    val hasDue: Boolean get() = dueShifts > 0

    /** 当日是否全部应打卡班次都出勤了 */
    val complete: Boolean get() = hasDue && attendedShifts >= dueShifts
}

/** 一个月的工时与出勤汇总 */
data class WorkMonthSummary(
    val dueShifts: Int = 0,
    val attendedShifts: Int = 0,
    val workMinutes: Int = 0,
    val missingShifts: Int = 0,
    val absentShifts: Int = 0
) {
    /** 班次出勤率 0..1 */
    val attendanceRate: Float
        get() = if (dueShifts == 0) 0f else attendedShifts.toFloat() / dueShifts
}

/**
 * 工时与出勤的统计（纯逻辑，可单测）。
 *
 * 与 [Stats] 的分工：[Stats] 面向"今日 / 本月按时率 / 连续打卡"这类**打卡次数**口径，
 * 这里面向**工时**口径（在岗分钟数）—— 图表要画的是"每天干了多久"，
 * 而"打了几次卡"画不出趋势。
 *
 * 工时的口径与考勤报表完全一致：**按班次分别计算再累加**，
 * 因此上午班 09:00-12:00 + 下午班 14:00-18:00 记 7 小时，而不是全天首末相减的 9 小时。
 */
object WorkStats {

    /** 最近 [days] 天（含 [today]）的每日工时，按日期升序 */
    fun recentDays(
        records: List<CheckInRecord>,
        rules: List<CheckInRule>,
        today: LocalDate,
        days: Int = 7,
        overrides: Map<String, Boolean> = emptyMap()
    ): List<DayWork> {
        if (days <= 0) return emptyList()
        val byDate = AttendanceCalculator.groupByAttendanceDate(records, rules, overrides)
        return (days - 1 downTo 0).map { back ->
            val date = today.minusDays(back.toLong())
            val shifts = AttendanceCalculator.shiftsFor(
                date, byDate[date].orEmpty(), rules, overrides
            )
            DayWork(
                date = date,
                workMinutes = shifts.sumOf { it.workMinutes },
                dueShifts = shifts.size,
                attendedShifts = shifts.count { it.hasPunch }
            )
        }
    }

    /** 某个月的工时与出勤汇总 */
    fun monthSummary(
        records: List<CheckInRecord>,
        rules: List<CheckInRule>,
        month: YearMonth,
        overrides: Map<String, Boolean> = emptyMap()
    ): WorkMonthSummary {
        val byDate = AttendanceCalculator.groupByAttendanceDate(records, rules, overrides)
        var due = 0
        var attended = 0
        var work = 0
        var missing = 0
        var absent = 0
        for (dayOfMonth in 1..month.lengthOfMonth()) {
            val date = month.atDay(dayOfMonth)
            val shifts = AttendanceCalculator.shiftsFor(
                date, byDate[date].orEmpty(), rules, overrides
            )
            due += shifts.size
            attended += shifts.count { it.hasPunch }
            work += shifts.sumOf { it.workMinutes }
            val absence = AbsencePolicy.summarize(shifts)
            missing += absence.missingShifts
            absent += absence.absentShifts
        }
        return WorkMonthSummary(
            dueShifts = due,
            attendedShifts = attended,
            workMinutes = work,
            missingShifts = missing,
            absentShifts = absent
        )
    }

    /** 期间累计工时 */
    fun totalMinutes(days: List<DayWork>): Int = days.sumOf { it.workMinutes }

    /**
     * 图表纵轴上限：留 10% 余量，且至少为 1 分钟。
     * 全为 0 时返回 1 而不是 0 —— 否则柱高计算会除零。
     */
    fun chartMax(days: List<DayWork>): Int {
        val max = days.maxOfOrNull { it.workMinutes } ?: 0
        return if (max <= 0) 1 else max + max / 10
    }
}
