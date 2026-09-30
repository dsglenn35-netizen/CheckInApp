package com.example.checkin.util

import com.example.checkin.data.CheckInRecord
import com.example.checkin.data.CheckInRule
import com.example.checkin.data.CheckStatus
import com.example.checkin.data.ShiftOverride
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth

/**
 * 工时统计的单测。
 *
 * 核心口径：**工时按班次分别计算再累加**。
 * 上午班 3 小时 + 下午班 4 小时 = 7 小时；而"全天首末相减"会算成 9 小时（把午休也算成在岗）。
 */
class WorkStatsTest {

    private val WEEKDAYS = 0b0011111
    private val monday: LocalDate = LocalDate.of(2026, 3, 2)

    private fun rule(id: Long, startHour: Int, endHour: Int) = CheckInRule(
        id = id,
        name = "班" + id.toString(),
        startHour = startHour,
        startMinute = 0,
        endHour = endHour,
        endMinute = 0,
        latitude = 0.0,
        longitude = 0.0,
        radiusMeters = 100.0,
        daysOfWeek = WEEKDAYS,
        requireCheckOut = true
    )

    private fun rec(rule: CheckInRule, ts: Long) = CheckInRecord(
        timestamp = ts,
        latitude = 0.0,
        longitude = 0.0,
        address = null,
        ruleName = rule.name,
        ruleId = rule.id,
        status = CheckStatus.SUCCESS.name
    )

    /** 某天打满两段班：上午 3 小时 + 下午 4 小时 */
    private fun fullDay(date: LocalDate, morning: CheckInRule, afternoon: CheckInRule) =
        listOf(
            rec(morning, AttendanceCalculator.windowStartFor(morning, date)),
            rec(morning, AttendanceCalculator.windowStartFor(morning, date) + 3 * 3_600_000L),
            rec(afternoon, AttendanceCalculator.windowStartFor(afternoon, date)),
            rec(afternoon, AttendanceCalculator.windowStartFor(afternoon, date) + 4 * 3_600_000L)
        )

    private fun weekdaysIn(month: YearMonth): Int =
        (1..month.lengthOfMonth()).count { month.atDay(it).dayOfWeek.value <= 5 }

    @Test
    fun `两段班工时累加而不是首末相减`() {
        val morning = rule(1L, 9, 12)
        val afternoon = rule(2L, 14, 18)
        val days = WorkStats.recentDays(
            records = fullDay(monday, morning, afternoon),
            rules = listOf(morning, afternoon),
            today = monday,
            days = 1
        )
        assertEquals(1, days.size)
        // 3 小时 + 4 小时 = 7 小时；首末相减会得到 9 小时（把午休算进去了）
        assertEquals(7 * 60, days[0].workMinutes)
        assertEquals(2, days[0].dueShifts)
        assertEquals(2, days[0].attendedShifts)
        assertTrue(days[0].complete)
    }

    @Test
    fun `取最近若干天且按日期升序`() {
        val morning = rule(1L, 9, 12)
        val afternoon = rule(2L, 14, 18)
        val days = WorkStats.recentDays(
            records = fullDay(monday, morning, afternoon),
            rules = listOf(morning, afternoon),
            today = monday,
            days = 7
        )
        assertEquals(7, days.size)
        assertEquals(monday.minusDays(6), days.first().date)
        assertEquals(monday, days.last().date)
        // 只有周一有记录
        assertEquals(7 * 60, WorkStats.totalMinutes(days))
    }

    @Test
    fun `调休当天没有应打卡班次`() {
        val morning = rule(1L, 9, 12)
        val afternoon = rule(2L, 14, 18)
        val table = ShiftSchedule.table(
            listOf(
                ShiftOverride(monday.toString(), 1L, false),
                ShiftOverride(monday.toString(), 2L, false)
            )
        )
        val days = WorkStats.recentDays(
            records = fullDay(monday, morning, afternoon),
            rules = listOf(morning, afternoon),
            today = monday,
            days = 1,
            overrides = table
        )
        assertEquals(0, days[0].dueShifts)
        assertEquals(0, days[0].workMinutes)
        assertTrue(!days[0].complete)
    }

    @Test
    fun `图表上限留余量且不会为零`() {
        // 全 0 时返回 1，避免画图时除零
        assertEquals(1, WorkStats.chartMax(listOf(DayWork(monday, 0, 1, 0))))
        assertEquals(1, WorkStats.chartMax(emptyList()))
        // 有值时留 10% 余量
        assertEquals(660, WorkStats.chartMax(listOf(DayWork(monday, 600, 1, 1))))
    }

    @Test
    fun `月份汇总统计应打卡班次与缺卡旷工`() {
        val morning = rule(1L, 9, 12)
        val afternoon = rule(2L, 14, 18)
        val month = YearMonth.of(2026, 3)
        val summary = WorkStats.monthSummary(
            records = fullDay(monday, morning, afternoon),
            rules = listOf(morning, afternoon),
            month = month
        )
        val workdays = weekdaysIn(month)
        assertEquals(workdays * 2, summary.dueShifts)
        assertEquals(2, summary.attendedShifts)
        assertEquals(7 * 60, summary.workMinutes)
        // 其余工作日两个班次都没打卡 → 全部记缺卡
        assertEquals((workdays - 1) * 2, summary.missingShifts)
        assertEquals(0, summary.absentShifts)
        assertEquals(2f / (workdays * 2), summary.attendanceRate, 0.0001f)
    }

    @Test
    fun `没有规则时统计全为零`() {
        val days = WorkStats.recentDays(emptyList(), emptyList(), monday, days = 3)
        assertEquals(3, days.size)
        assertEquals(0, WorkStats.totalMinutes(days))
        assertEquals(0, days[0].dueShifts)
    }
}
