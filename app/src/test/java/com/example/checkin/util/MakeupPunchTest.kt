package com.example.checkin.util

import com.example.checkin.data.CheckInRule
import com.example.checkin.data.CheckStatus
import com.example.checkin.data.RecordOrigin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * 补卡的单测。
 *
 * 两条不变量：
 * 1. **必须填原因**，且来源标为 [RecordOrigin.MAKEUP]，与自动 / 手动打卡区分；
 * 2. **不伪造地点** —— 补卡时没有取证定位，坐标留 0,0（未记录），
 *    拿规则坐标冒充"当时就在那儿"是伪造证据。
 */
class MakeupPunchTest {

    private val monday: LocalDate = LocalDate.of(2026, 3, 2)

    private fun rule(requireCheckOut: Boolean = false, endMinute: Int = -1) = CheckInRule(
        id = 1L,
        name = "早班",
        startHour = 9,
        startMinute = 0,
        endHour = 18,
        endMinute = 0,
        latitude = 31.2304,
        longitude = 121.4737,
        radiusMeters = 100.0,
        requiredEndMinute = endMinute,
        requireCheckOut = requireCheckOut
    )

    private fun shift(rule: CheckInRule, punches: List<Long>) =
        ShiftAttendance(rule = rule, windowStart = AttendanceCalculator.windowStartFor(rule, monday), punches = punches)

    @Test
    fun `原因必填且不能只有一两个字`() {
        assertFalse(MakeupPunch.isReasonValid(null))
        assertFalse(MakeupPunch.isReasonValid(""))
        assertFalse(MakeupPunch.isReasonValid("  "))
        assertFalse(MakeupPunch.isReasonValid("忘"))
        assertTrue(MakeupPunch.isReasonValid("忘记打卡"))
    }

    @Test
    fun `原因不合规时不构造记录`() {
        assertNull(MakeupPunch.build(rule(), monday, PunchSlot.IN, ""))
        assertNull(MakeupPunch.build(rule(), monday, PunchSlot.IN, "忘"))
        assertNull(MakeupPunch.build(rule(), monday, PunchSlot.IN, null))
    }

    @Test
    fun `补上班卡落在窗口开始时刻`() {
        val r = rule()
        val record = MakeupPunch.build(r, monday, PunchSlot.IN, "忘记打卡")!!
        assertEquals(AttendanceCalculator.windowStartFor(r, monday), record.timestamp)
        assertEquals(CheckStatus.SUCCESS.name, record.status)
        assertEquals(RecordOrigin.MAKEUP.name, record.origin)
        assertEquals("补卡：忘记打卡", record.note)
        assertTrue(CheckInValidator.isWithinTime(r, record.timestamp))
    }

    @Test
    fun `补下班卡落在窗口内且不越界`() {
        val r = rule(requireCheckOut = true)
        val record = MakeupPunch.build(r, monday, PunchSlot.OUT, "走时忘了打")!!
        // 窗口是 [开始, 结束)，正好取结束点会被判成窗口外，因此取结束前 1 分钟
        assertTrue(CheckInValidator.isWithinTime(r, record.timestamp))
        assertEquals(
            AttendanceCalculator.windowStartFor(r, monday) + 9 * 3_600_000L - 60_000L,
            record.timestamp
        )
    }

    @Test
    fun `补卡不伪造地点`() {
        val record = MakeupPunch.build(rule(), monday, PunchSlot.IN, "忘记打卡")!!
        assertEquals(0.0, record.latitude, 0.0)
        assertEquals(0.0, record.longitude, 0.0)
        assertNull(record.address)
        assertNull(record.clockSkewMs)
        assertFalse(record.mockLocation)
        // 也不该被当成"人工修正过"的记录（那是另一回事）
        assertFalse(record.isEdited)
    }

    @Test
    fun `补卡备注识别与原因回读`() {
        assertTrue(MakeupPunch.isMakeupNote("补卡：忘记打卡"))
        assertFalse(MakeupPunch.isMakeupNote("外勤：客户现场"))
        assertFalse(MakeupPunch.isMakeupNote(null))
        assertEquals("忘记打卡", MakeupPunch.reasonOf("补卡：忘记打卡"))
        assertNull(MakeupPunch.reasonOf("请假"))
    }

    @Test
    fun `缺卡班次提示要补哪个槽位`() {
        val plain = rule()
        val twoPunch = rule(requireCheckOut = true)
        val start = AttendanceCalculator.windowStartFor(plain, monday)
        // 完全没打 → 先补上班卡
        assertEquals(PunchSlot.IN, MakeupPunch.missingSlot(shift(plain, emptyList())))
        // 不需要下班卡、已打一次 → 不缺
        assertNull(MakeupPunch.missingSlot(shift(plain, listOf(start))))
        // 需要下班卡、只打一次 → 补下班卡
        assertEquals(PunchSlot.OUT, MakeupPunch.missingSlot(shift(twoPunch, listOf(start))))
        // 打满 → 不缺
        assertNull(MakeupPunch.missingSlot(shift(twoPunch, listOf(start, start + 9 * 3_600_000L))))
    }

    @Test
    fun `补卡记录能被考勤报表计入出勤`() {
        val r = rule()
        val record = MakeupPunch.build(r, monday, PunchSlot.IN, "忘记打卡")!!
        val shifts = AttendanceCalculator.shiftsFor(monday, listOf(record), listOf(r))
        assertEquals(1, shifts.size)
        assertTrue(shifts[0].hasPunch)
        assertEquals(monday, AttendanceCalculator.attendanceDate(record, listOf(r)))
    }
}
