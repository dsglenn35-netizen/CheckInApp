package com.example.checkin.util

import com.example.checkin.data.CheckInRecord
import com.example.checkin.data.CheckInRule
import com.example.checkin.data.CheckStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * 出勤口径的单测。
 *
 * 加入"外勤"后，全项目"这天算不算出勤"必须统一走 [CheckStatus.isAttended]。
 * 这里同时钉住**考勤归集**层面的行为：外勤要像正常打卡一样计入班次，
 * 否则"一天只有外勤"会被算成缺卡、工时与迟到早退全部丢失。
 */
class CheckStatusSemanticsTest {

    private fun rule(startHour: Int = 9, endHour: Int = 18) = CheckInRule(
        id = 1L,
        name = "早班",
        startHour = startHour,
        startMinute = 0,
        endHour = endHour,
        endMinute = 0,
        latitude = 0.0,
        longitude = 0.0,
        radiusMeters = 100.0
    )

    private fun record(status: String, timestamp: Long) = CheckInRecord(
        timestamp = timestamp,
        latitude = 31.2304,
        longitude = 121.4737,
        address = "上海市",
        ruleName = "早班",
        ruleId = 1L,
        status = status
    )

    @Test
    fun `只有成功与外勤算有效出勤`() {
        assertTrue(CheckStatus.isAttended(CheckStatus.SUCCESS.name))
        assertTrue(CheckStatus.isAttended(CheckStatus.FIELD_WORK.name))
        listOf(
            CheckStatus.OUT_OF_TIME,
            CheckStatus.OUT_OF_RANGE,
            CheckStatus.OUT_OF_TIME_AND_RANGE,
            CheckStatus.NO_LOCATION,
            CheckStatus.NO_RULE
        ).forEach { assertFalse(it.name, CheckStatus.isAttended(it.name)) }
    }

    @Test
    fun `外勤不算正常出勤但也不算缺勤`() {
        assertFalse(CheckStatus.isNormal(CheckStatus.FIELD_WORK.name))
        assertFalse(CheckStatus.isAbsent(CheckStatus.FIELD_WORK.name))
        // 未知/空状态按未出勤处理，避免脏数据被算成出勤
        assertTrue(CheckStatus.isAbsent(null))
        assertTrue(CheckStatus.isAbsent("NOPE"))
    }

    @Test
    fun `外勤计入班次打卡而不是缺卡`() {
        val r = rule()
        val date = LocalDate.of(2026, 3, 2)
        val windowStart = AttendanceCalculator.windowStartFor(r, date)
        val records = listOf(
            record(CheckStatus.FIELD_WORK.name, windowStart + 30 * 60_000L)
        )
        val shifts = AttendanceCalculator.shiftsFor(date, records, listOf(r))
        assertEquals(1, shifts.size)
        assertTrue(shifts[0].hasPunch)
        // 只有一次打卡，仍在岗时长无法确定（0），但"缺卡"里的"完全没打"已不成立
        assertEquals(1, shifts[0].punches.size)
    }

    @Test
    fun `地点外的失败记录不计入班次打卡`() {
        val r = rule()
        val date = LocalDate.of(2026, 3, 2)
        val windowStart = AttendanceCalculator.windowStartFor(r, date)
        val records = listOf(
            record(CheckStatus.OUT_OF_RANGE.name, windowStart + 30 * 60_000L)
        )
        val shifts = AttendanceCalculator.shiftsFor(date, records, listOf(r))
        assertFalse(shifts[0].hasPunch)
    }

    @Test
    fun `跨午夜班次的外勤记录归属窗口开始那一天`() {
        // 22:00-06:00 的夜班：次日凌晨的外勤仍属于"窗口开始的那一天"
        val night = rule(startHour = 22, endHour = 6)
        val date = LocalDate.of(2026, 3, 2)
        val windowStart = AttendanceCalculator.windowStartFor(night, date)
        val atNextDay0200 = windowStart + 4 * 60 * 60_000L
        val rec = record(CheckStatus.FIELD_WORK.name, atNextDay0200)
        assertEquals(date, AttendanceCalculator.attendanceDate(rec, listOf(night)))
    }

    @Test
    fun `统计把外勤计为出勤且单列次数`() {
        val today = LocalDate.of(2026, 3, 2)
        val base = AttendanceCalculator.windowStartFor(rule(), today)
        val records = listOf(
            record(CheckStatus.FIELD_WORK.name, base + 10 * 60_000L),
            record(CheckStatus.OUT_OF_RANGE.name, base + 20 * 60_000L)
        )
        val stats = computeStats(records, today)
        assertEquals(1, stats.todaySuccess)
        assertEquals(1, stats.todayFieldWork)
        // 外勤不再被算成"失败"，失败只剩那条真正没打上的
        assertEquals(1, stats.todayFail)
    }
}
