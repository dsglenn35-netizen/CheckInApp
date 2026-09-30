package com.example.checkin.util

import com.example.checkin.data.CheckInRule
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 缺卡 / 旷工判定的单测。
 *
 * 两条不变量：
 * 1. **没配应到时刻就没有旷工** —— 连"迟到"都不判定，判旷工就是拿错了尺子
 *    （自由工时制同理，它的口径是当日工时够不够）；
 * 2. **旷工优先于缺卡** —— 一条班次既晚到又缺下班卡时，更严重的是"人没按时来"。
 */
class AbsenceTest {

    private val base = 1_700_000_000_000L

    private fun rule(
        requireCheckOut: Boolean = false,
        requiredStartMinute: Int = -1,
        requiredEndMinute: Int = -1,
        flexible: Boolean = false
    ) = CheckInRule(
        id = 1L,
        name = "早班",
        startHour = 9,
        startMinute = 0,
        endHour = 18,
        endMinute = 0,
        latitude = 0.0,
        longitude = 0.0,
        radiusMeters = 100.0,
        requiredStartMinute = requiredStartMinute,
        requiredEndMinute = requiredEndMinute,
        requireCheckOut = requireCheckOut,
        flexible = flexible
    )

    private fun shift(
        r: CheckInRule = rule(),
        punches: List<Long> = emptyList()
    ) = ShiftAttendance(rule = r, windowStart = base, punches = punches)

    @Test
    fun `完全没打卡算缺卡`() {
        assertEquals(AbsenceKind.MISSING, AbsencePolicy.kindFor(shift()))
    }

    @Test
    fun `需要下班卡却只打一次算缺卡`() {
        val r = rule(requireCheckOut = true)
        assertEquals(AbsenceKind.MISSING, AbsencePolicy.kindFor(shift(r, listOf(base))))
    }

    @Test
    fun `不需要下班卡时打一次就算完整`() {
        assertEquals(AbsenceKind.NONE, AbsencePolicy.kindFor(shift(rule(), listOf(base))))
    }

    @Test
    fun `没配应到时刻就不判旷工`() {
        // 干了 10 小时才来也算"正常"：规则没有应到基准，本就不该有迟到/旷工概念
        val r = rule()
        val shift = ShiftAttendance(rule = r, windowStart = base, punches = listOf(base + 10 * 3_600_000L))
        assertEquals(AbsenceKind.NONE, AbsencePolicy.kindFor(shift))
    }

    @Test
    fun `迟到未达阈值不算旷工`() {
        val r = rule(requiredStartMinute = 9 * 60)
        // 窗口 09:00 起，10:00 打卡 = 迟到 60 分钟（基准也是 09:00）
        val s = ShiftAttendance(rule = r, windowStart = base, punches = listOf(base + 59 * 60_000L))
        assertEquals(59, s.lateMinutes)
        assertEquals(AbsenceKind.NONE, AbsencePolicy.kindFor(s))
    }

    @Test
    fun `迟到达到阈值算旷工`() {
        val r = rule(requiredStartMinute = 9 * 60)
        val s = ShiftAttendance(rule = r, windowStart = base, punches = listOf(base + 60 * 60_000L))
        assertEquals(60, s.lateMinutes)
        assertEquals(AbsenceKind.ABSENT, AbsencePolicy.kindFor(s))
    }

    @Test
    fun `阈值可调且下限被夹到一分钟`() {
        val r = rule(requiredStartMinute = 9 * 60)
        val s = ShiftAttendance(rule = r, windowStart = base, punches = listOf(base + 30 * 60_000L))
        // 阈值 15 分钟 → 迟到 30 分钟即旷工
        assertEquals(AbsenceKind.ABSENT, AbsencePolicy.kindFor(s, absentAfterMinutes = 15))
        // 阈值 0 会被夹到 1 分钟，不会变成"只要迟到就算旷工"以外的怪行为
        assertEquals(AbsenceKind.ABSENT, AbsencePolicy.kindFor(s, absentAfterMinutes = 0))
        // 大阈值下不算
        assertEquals(AbsenceKind.NONE, AbsencePolicy.kindFor(s, absentAfterMinutes = 120))
    }

    @Test
    fun `自由工时制不判旷工`() {
        // 即使配了应到时刻，自由工时的 lateMinutes 也恒为 0（口径是当日工时是否达标）
        val r = rule(requiredStartMinute = 9 * 60, flexible = true)
        val s = ShiftAttendance(rule = r, windowStart = base, punches = listOf(base + 5 * 3_600_000L))
        assertEquals(0, s.lateMinutes)
        assertEquals(AbsenceKind.NONE, AbsencePolicy.kindFor(s))
    }

    @Test
    fun `旷工优先于缺卡`() {
        // 需要下班卡、只打了一次、且这次还迟到 90 分钟 → 更严重的是旷工
        val r = rule(requireCheckOut = true, requiredStartMinute = 9 * 60)
        val s = ShiftAttendance(rule = r, windowStart = base, punches = listOf(base + 90 * 60_000L))
        assertEquals(AbsenceKind.ABSENT, AbsencePolicy.kindFor(s))
    }

    @Test
    fun `汇总按班次计数`() {
        val late = rule(requiredStartMinute = 9 * 60)
        val normal = rule()
        val shifts = listOf(
            shift(normal, listOf(base)),                          // 正常
            shift(normal),                                        // 缺卡
            shift(late, listOf(base + 120 * 60_000L))             // 旷工
        )
        val summary = AbsencePolicy.summarize(shifts)
        assertEquals(1, summary.missingShifts)
        assertEquals(1, summary.absentShifts)
        assertEquals(2, summary.total)
    }

    @Test
    fun `文案与枚举对应`() {
        assertEquals("缺卡", AbsencePolicy.label(AbsenceKind.MISSING))
        assertEquals("旷工", AbsencePolicy.label(AbsenceKind.ABSENT))
        assertEquals("正常", AbsencePolicy.label(AbsenceKind.NONE))
    }
}
