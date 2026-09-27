package com.example.checkin.util

import com.example.checkin.data.CheckInRecord
import com.example.checkin.data.CheckInRule
import com.example.checkin.data.CheckStatus
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 考勤归集单测：覆盖"交给 HR 的报表"最容易算错的两处口径 ——
 * 跨午夜班次的**考勤日归属**与一天多段班的**分班次工时**。
 *
 * 时间基准：2026-08-31 是周一，2026-09-01 是周二。
 */
class AttendanceTest {

    private fun millis(y: Int, mo: Int, d: Int, h: Int, mi: Int) =
        LocalDateTime.of(y, mo, d, h, mi).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private fun rule(
        id: Long = 1L,
        name: String = "夜班",
        startHour: Int = 22,
        startMinute: Int = 0,
        endHour: Int = 6,
        endMinute: Int = 0,
        daysOfWeek: Int = 127,
        requiredStartMinute: Int = -1,
        requiredEndMinute: Int = -1
    ) = CheckInRule(
        id = id, name = name,
        startHour = startHour, startMinute = startMinute,
        endHour = endHour, endMinute = endMinute,
        latitude = 30.0, longitude = 120.0, radiusMeters = 100.0,
        enabled = true, daysOfWeek = daysOfWeek,
        requiredStartMinute = requiredStartMinute,
        requiredEndMinute = requiredEndMinute
    )

    private fun rec(
        timestamp: Long,
        ruleId: Long = 1L,
        ruleName: String? = "夜班",
        status: String = CheckStatus.SUCCESS.name
    ) = CheckInRecord(
        timestamp = timestamp,
        latitude = 30.0, longitude = 120.0,
        address = null, ruleName = ruleName,
        status = status, ruleId = ruleId
    )

    // ---------- 考勤日归属（跨午夜） ----------

    @Test
    fun `跨午夜夜班的凌晨段归属前一天`() {
        val night = rule(daysOfWeek = 1 shl 0) // 仅周一
        val rules = listOf(night)
        assertEquals(
            LocalDate.of(2026, 8, 31),
            AttendanceCalculator.attendanceDate(rec(millis(2026, 8, 31, 23, 0)), rules)
        )
        // 周二 02:00 是"周一这一班"的后半段，必须回到 8/31
        assertEquals(
            LocalDate.of(2026, 8, 31),
            AttendanceCalculator.attendanceDate(rec(millis(2026, 9, 1, 2, 0)), rules)
        )
    }

    @Test
    fun `按考勤日分组把夜班两段合到一起`() {
        val night = rule(daysOfWeek = 1 shl 0)
        val grouped = AttendanceCalculator.groupByAttendanceDate(
            listOf(rec(millis(2026, 8, 31, 23, 0)), rec(millis(2026, 9, 1, 2, 0))),
            listOf(night)
        )
        assertEquals(1, grouped.size)
        assertEquals(2, grouped[LocalDate.of(2026, 8, 31)]?.size)
    }

    @Test
    fun `普通班次归属当天`() {
        val day = rule(name = "白班", startHour = 9, startMinute = 0, endHour = 18, endMinute = 0)
        val r = rec(millis(2026, 8, 31, 10, 0), ruleName = "白班")
        assertEquals(LocalDate.of(2026, 8, 31), AttendanceCalculator.attendanceDate(r, listOf(day)))
    }

    @Test
    fun `失败记录回退自然日`() {
        val night = rule(daysOfWeek = 1 shl 0)
        val fail = rec(millis(2026, 9, 1, 2, 0), status = CheckStatus.OUT_OF_RANGE.name)
        assertEquals(LocalDate.of(2026, 9, 1), AttendanceCalculator.attendanceDate(fail, listOf(night)))
    }

    // ---------- 分班次工时 ----------

    @Test
    fun `一天两个班次的工时不含班次之间的空档`() {
        val morning = rule(id = 1, name = "上午班", startHour = 9, startMinute = 0, endHour = 12, endMinute = 0)
        val afternoon = rule(id = 2, name = "下午班", startHour = 14, startMinute = 0, endHour = 18, endMinute = 0)
        val rules = listOf(morning, afternoon)
        val recs = listOf(
            rec(millis(2026, 8, 31, 9, 0), ruleId = 1, ruleName = "上午班"),
            rec(millis(2026, 8, 31, 12, 0), ruleId = 1, ruleName = "上午班"),
            rec(millis(2026, 8, 31, 14, 0), ruleId = 2, ruleName = "下午班"),
            rec(millis(2026, 8, 31, 18, 0), ruleId = 2, ruleName = "下午班")
        )
        val shifts = AttendanceCalculator.shiftsFor(LocalDate.of(2026, 8, 31), recs, rules)
        assertEquals(2, shifts.size)
        // 3h + 4h = 7h；若沿用"全天首末相减"会算成 09:00→18:00 的 9h
        assertEquals(7 * 60, shifts.sumOf { it.workMinutes })
    }

    @Test
    fun `缺下班卡时工时为0并标记缺卡`() {
        val day = rule(name = "白班", startHour = 9, startMinute = 0, endHour = 18, endMinute = 0)
        val recs = listOf(rec(millis(2026, 8, 31, 9, 0), ruleName = "白班"))
        val s = AttendanceCalculator.shiftsFor(LocalDate.of(2026, 8, 31), recs, listOf(day)).single()
        assertTrue(s.missingPunch)
        assertEquals(0, s.workMinutes)
    }

    @Test
    fun `完整打卡不算缺卡`() {
        val day = rule(name = "白班", startHour = 9, startMinute = 0, endHour = 18, endMinute = 0)
        val recs = listOf(
            rec(millis(2026, 8, 31, 9, 0), ruleName = "白班"),
            rec(millis(2026, 8, 31, 18, 0), ruleName = "白班")
        )
        val s = AttendanceCalculator.shiftsFor(LocalDate.of(2026, 8, 31), recs, listOf(day)).single()
        assertFalse(s.missingPunch)
        assertEquals(9 * 60, s.workMinutes)
    }

    // ---------- 迟到 / 早退（跨午夜） ----------

    @Test
    fun `跨午夜班次的迟到判定`() {
        val night = rule(requiredStartMinute = 22 * 60)
        val recs = listOf(rec(millis(2026, 8, 31, 22, 30)))
        val s = AttendanceCalculator.shiftsFor(LocalDate.of(2026, 8, 31), recs, listOf(night)).single()
        assertEquals(30, s.lateMinutes)
    }

    @Test
    fun `跨午夜班次的早退判定`() {
        val night = rule(requiredEndMinute = 6 * 60)
        // 22:00 上班、次日 05:00 下班 → 早退 60 分钟，在岗 7 小时
        val recs = listOf(rec(millis(2026, 8, 31, 22, 0)), rec(millis(2026, 9, 1, 5, 0)))
        val s = AttendanceCalculator.shiftsFor(LocalDate.of(2026, 8, 31), recs, listOf(night)).single()
        assertEquals(0, s.lateMinutes)
        assertEquals(60, s.earlyMinutes)
        assertEquals(7 * 60, s.workMinutes)
    }

    @Test
    fun `普通班次的迟到早退`() {
        val day = rule(
            name = "白班", startHour = 9, startMinute = 0, endHour = 18, endMinute = 0,
            requiredStartMinute = 9 * 60, requiredEndMinute = 18 * 60
        )
        val recs = listOf(
            rec(millis(2026, 8, 31, 9, 15), ruleName = "白班"),
            rec(millis(2026, 8, 31, 17, 30), ruleName = "白班")
        )
        val s = AttendanceCalculator.shiftsFor(LocalDate.of(2026, 8, 31), recs, listOf(day)).single()
        assertEquals(15, s.lateMinutes)
        assertEquals(30, s.earlyMinutes)
    }

    @Test
    fun `未配置应到应离时不判定迟到早退`() {
        val night = rule()
        val recs = listOf(rec(millis(2026, 8, 31, 23, 0)))
        val s = AttendanceCalculator.shiftsFor(LocalDate.of(2026, 8, 31), recs, listOf(night)).single()
        assertEquals(0, s.lateMinutes)
        assertEquals(0, s.earlyMinutes)
    }

    // ---------- 班制 ----------

    @Test
    fun `轮转班制休息日不产生班次`() {
        val rot = rule(daysOfWeek = 0).copy(
            shiftPattern = ShiftPattern.rotation(1, 1, LocalDate.of(2026, 8, 31)).serialize()
        )
        // 8/31 是周期第 1 天（上班），9/1 是第 2 天（休息）
        assertEquals(
            1, AttendanceCalculator.shiftsFor(LocalDate.of(2026, 8, 31), emptyList(), listOf(rot)).size
        )
        assertEquals(
            0, AttendanceCalculator.shiftsFor(LocalDate.of(2026, 9, 1), emptyList(), listOf(rot)).size
        )
    }

    @Test
    fun `无打卡时班次仍列出但标记缺卡`() {
        val day = rule(name = "白班", startHour = 9, startMinute = 0, endHour = 18, endMinute = 0)
        val s = AttendanceCalculator.shiftsFor(LocalDate.of(2026, 8, 31), emptyList(), listOf(day)).single()
        assertFalse(s.hasPunch)
        assertTrue(s.missingPunch)
        assertEquals(0, s.workMinutes)
    }
}
