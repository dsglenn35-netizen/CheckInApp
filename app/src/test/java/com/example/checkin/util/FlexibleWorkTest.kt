package com.example.checkin.util

import com.example.checkin.data.CheckInRecord
import com.example.checkin.data.CheckInRule
import com.example.checkin.data.CheckStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth

/**
 * 自由工时制的达标判定与工时汇总单测。
 *
 * 两条口径必须钉死：
 * 1. **自由工时不做迟到 / 早退判定** —— 几点来不重要，干够工时才重要；
 * 2. **请假 / 放假当天不算"未达标"** —— 那些日子本来就不该上班。
 *
 * 月度用例统一用"仅工作日生效"的规则，并按当月实际工作日数推导期望值，
 * 避免把"某月有多少个工作日"这种日历事实写死在断言里。
 */
class FlexibleWorkTest {

    /** bit0=周一 … bit4=周五 */
    private val WEEKDAYS = 0b0011111

    private fun rule(
        id: Long = 1L,
        name: String = "弹性班",
        flexible: Boolean = true,
        target: Int = 8 * 60,
        startHour: Int = 9,
        endHour: Int = 18,
        daysOfWeek: Int = WEEKDAYS,
        requiredStartMinute: Int = -1,
        requiredEndMinute: Int = -1
    ) = CheckInRule(
        id = id,
        name = name,
        startHour = startHour,
        startMinute = 0,
        endHour = endHour,
        endMinute = 0,
        latitude = 0.0,
        longitude = 0.0,
        radiusMeters = 100.0,
        daysOfWeek = daysOfWeek,
        requiredStartMinute = requiredStartMinute,
        requiredEndMinute = requiredEndMinute,
        flexible = flexible,
        requiredWorkMinutes = target
    )

    private fun record(timestamp: Long, status: String = CheckStatus.SUCCESS.name) =
        CheckInRecord(
            timestamp = timestamp,
            latitude = 0.0,
            longitude = 0.0,
            address = null,
            ruleName = "弹性班",
            ruleId = 1L,
            status = status
        )

    /** 当月工作日数（与规则的"仅工作日生效"一致） */
    private fun weekdaysIn(month: YearMonth): Int =
        (1..month.lengthOfMonth()).count { month.atDay(it).dayOfWeek.value <= 5 }

    /**
     * 某天打一段完整的卡：上班 = 窗口开始，下班 = 窗口开始 + [hours] 小时。
     * 偏移量必须落在窗口内，否则记录会被归到别的考勤日。
     */
    private fun workedDay(
        month: YearMonth,
        day: Int,
        rule: CheckInRule,
        hours: Int = 9
    ): List<CheckInRecord> {
        val start = AttendanceCalculator.windowStartFor(rule, month.atDay(day))
        return listOf(
            record(start),
            record(start + hours * 60 * 60_000L)
        )
    }

    @Test
    fun `目标合法性判定`() {
        assertTrue(FlexibleWork.isFlexibleWithTarget(rule(target = 8 * 60)))
        // 自由工时但没设目标 → 不参与达标考核
        assertFalse(FlexibleWork.isFlexibleWithTarget(rule(target = FlexibleWork.NO_TARGET)))
        // 非自由工时即使有目标也不按自由工时考核
        assertFalse(FlexibleWork.isFlexibleWithTarget(rule(flexible = false, target = 8 * 60)))
        // 目标过小 / 过大都视为未设置
        assertFalse(FlexibleWork.isFlexibleWithTarget(rule(target = 10)))
        assertFalse(FlexibleWork.isFlexibleWithTarget(rule(target = 25 * 60)))
    }

    @Test
    fun `目标规整与默认值`() {
        assertEquals(8 * 60, FlexibleWork.sanitizeTarget(8 * 60))
        assertEquals(FlexibleWork.NO_TARGET, FlexibleWork.sanitizeTarget(0))
        assertEquals(FlexibleWork.NO_TARGET, FlexibleWork.sanitizeTarget(-5))
        assertEquals(FlexibleWork.NO_TARGET, FlexibleWork.sanitizeTarget(24 * 60 + 1))
        assertEquals(8 * 60, FlexibleWork.DEFAULT_TARGET_MINUTES)
    }

    @Test
    fun `当日工时不达标时给出缺口`() {
        val day = FlexibleWork.dayResult(rule(), LocalDate.of(2026, 3, 2), workedMinutes = 6 * 60)!!
        assertFalse(day.met)
        assertEquals(2 * 60, day.shortfallMinutes)
    }

    @Test
    fun `当日工时达标时缺口为零`() {
        val day = FlexibleWork.dayResult(rule(), LocalDate.of(2026, 3, 2), workedMinutes = 9 * 60)!!
        assertTrue(day.met)
        assertEquals(0, day.shortfallMinutes)
    }

    @Test
    fun `非自由工时规则不产生达标结果`() {
        val date = LocalDate.of(2026, 3, 2)
        assertNull(FlexibleWork.dayResult(rule(flexible = false), date, 9 * 60))
        assertNull(FlexibleWork.dayResult(rule(target = FlexibleWork.NO_TARGET), date, 9 * 60))
    }

    @Test
    fun `月度汇总统计应工作与实际工时`() {
        val month = YearMonth.of(2026, 3)
        val r = rule()
        // 3 月 2 日、3 日各打满 9 小时；其余工作日没有记录
        val records = workedDay(month, 2, r) + workedDay(month, 3, r)
        val summary = FlexibleWork.summarize(records, listOf(r), month)
        val workdays = weekdaysIn(month)
        assertEquals(workdays, summary.requiredDays)
        assertEquals(2, summary.metDays)
        assertEquals(workdays - 2, summary.shortfallDays)
        assertEquals(workdays * 8 * 60, summary.requiredMinutes)
        assertEquals(2 * 9 * 60, summary.workedMinutes)
    }

    @Test
    fun `没有打卡的工作日算未达标`() {
        val month = YearMonth.of(2026, 3)
        val r = rule()
        val records = workedDay(month, 2, r)
        val summary = FlexibleWork.summarize(records, listOf(r), month)
        val workdays = weekdaysIn(month)
        assertEquals(workdays, summary.requiredDays)
        assertEquals(1, summary.metDays)
        assertEquals(workdays - 1, summary.shortfallDays)
        // 缺口 = 应工作总时长 − 实际累计
        assertEquals(workdays * 8 * 60 - 9 * 60, summary.shortfallMinutes)
    }

    @Test
    fun `只有一次打卡的当天算未达标`() {
        val month = YearMonth.of(2026, 3)
        val r = rule()
        // 只打了上班卡：工时无法确定 → 记 0 分钟，宁可判未达标也不猜他干满了
        val start = AttendanceCalculator.windowStartFor(r, month.atDay(2))
        val summary = FlexibleWork.summarize(listOf(record(start)), listOf(r), month)
        assertEquals(0, summary.metDays)
        assertEquals(0, summary.workedMinutes)
        assertEquals(weekdaysIn(month), summary.shortfallDays)
    }

    @Test
    fun `请假放假日不计入应工作天数`() {
        val month = YearMonth.of(2026, 3)
        val r = rule()
        val records = workedDay(month, 2, r) + workedDay(month, 3, r)
        val summary = FlexibleWork.summarize(
            records = records,
            rules = listOf(r),
            month = month,
            excludedDates = setOf(month.atDay(3))
        )
        // 3 月 3 日被排除后，应工作天数少一天，且它原本是达标的
        assertEquals(weekdaysIn(month) - 1, summary.requiredDays)
        assertEquals(1, summary.metDays)
    }

    @Test
    fun `没有自由工时规则时汇总为空`() {
        val month = YearMonth.of(2026, 3)
        val summary = FlexibleWork.summarize(emptyList(), listOf(rule(flexible = false)), month)
        assertEquals(0, summary.requiredDays)
        assertEquals(0f, summary.completionRate, 0.001f)
    }

    @Test
    fun `自由工时规则不判迟到早退`() {
        val date = LocalDate.of(2026, 3, 2)
        // 配了应到 09:00 / 应离 18:00，但因为是自由工时，两者都必须被忽略
        val r = rule(
            requiredStartMinute = 9 * 60,
            requiredEndMinute = 18 * 60
        )
        val start = AttendanceCalculator.windowStartFor(r, date)
        // 11:00 才来、15:00 就走：固定班次下会算迟到 2 小时 + 早退 3 小时
        val shifts = AttendanceCalculator.shiftsFor(
            date,
            listOf(
                record(start + 2 * 60 * 60_000L),
                record(start + 6 * 60 * 60_000L)
            ),
            listOf(r)
        )
        assertEquals(0, shifts[0].lateMinutes)
        assertEquals(0, shifts[0].earlyMinutes)
        assertEquals(4 * 60, shifts[0].workMinutes)
    }
}
