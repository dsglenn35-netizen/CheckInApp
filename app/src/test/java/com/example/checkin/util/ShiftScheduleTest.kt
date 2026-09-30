package com.example.checkin.util

import com.example.checkin.data.CheckInRule
import com.example.checkin.data.ShiftOverride
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/**
 * 调班 / 调休的单测。
 *
 * 核心不变量：**例外优先于周期**。有覆盖记录的那一天以覆盖为准，
 * 其余日期一律回落到规则自身的星期 / 轮转班制。
 *
 * 日期锚点：2026-03-02 是周一、2026-03-07 是周六（[周一与周六锚点成立] 里钉住）。
 * 规则默认"仅工作日生效"（bit0=周一 … bit4=周五）。
 */
class ShiftScheduleTest {

    private val WEEKDAYS = 0b0011111
    private val monday: LocalDate = LocalDate.of(2026, 3, 2)
    private val saturday: LocalDate = LocalDate.of(2026, 3, 7)

    private fun rule(id: Long = 1L, name: String = "早班") = CheckInRule(
        id = id,
        name = name,
        startHour = 9,
        startMinute = 0,
        endHour = 18,
        endMinute = 0,
        latitude = 0.0,
        longitude = 0.0,
        radiusMeters = 100.0,
        daysOfWeek = WEEKDAYS
    )

    private fun ov(date: LocalDate, ruleId: Long, working: Boolean) =
        ShiftOverride(date = date.toString(), ruleId = ruleId, working = working)

    private fun at(date: LocalDate, hour: Int, minute: Int = 0): Long =
        date.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli() +
            (hour * 60 + minute) * 60_000L

    @Test
    fun `周一与周六锚点成立`() {
        assertEquals(java.time.DayOfWeek.MONDAY, monday.dayOfWeek)
        assertEquals(java.time.DayOfWeek.SATURDAY, saturday.dayOfWeek)
    }

    @Test
    fun `没有覆盖时按规则自身的生效日判定`() {
        val r = rule()
        assertTrue(ShiftSchedule.isWorkDay(r, monday))
        assertFalse(ShiftSchedule.isWorkDay(r, saturday))
        assertNull(ShiftSchedule.overrideFor(r, monday, emptyMap()))
    }

    @Test
    fun `调班把休息日变成上班日`() {
        val r = rule()
        val table = ShiftSchedule.table(listOf(ov(saturday, 1L, working = true)))
        assertTrue(ShiftSchedule.isWorkDay(r, saturday, table))
        // 只影响这一天
        assertFalse(ShiftSchedule.isWorkDay(r, LocalDate.of(2026, 3, 14), table))
    }

    @Test
    fun `调休把上班日变成休息日`() {
        val r = rule()
        val table = ShiftSchedule.table(listOf(ov(monday, 1L, working = false)))
        assertFalse(ShiftSchedule.isWorkDay(r, monday, table))
        // 相邻的工作日不受影响
        assertTrue(ShiftSchedule.isWorkDay(r, LocalDate.of(2026, 3, 3), table))
    }

    @Test
    fun `覆盖只作用于指定的规则`() {
        val first = rule(id = 1L, name = "早班")
        val second = rule(id = 2L, name = "晚班")
        val table = ShiftSchedule.table(listOf(ov(saturday, 1L, working = true)))
        assertTrue(ShiftSchedule.isWorkDay(first, saturday, table))
        assertFalse(ShiftSchedule.isWorkDay(second, saturday, table))
    }

    @Test
    fun `组合键由实体统一定义`() {
        // 数据层建表与纯逻辑查表共用这一个格式，避免"调休设了却不生效"
        assertEquals("2026-03-07#1", ShiftOverride.key("2026-03-07", 1L))
        val table = ShiftSchedule.table(listOf(ov(saturday, 1L, working = true)))
        assertEquals(true, table[ShiftOverride.key("2026-03-07", 1L)])
    }

    @Test
    fun `窗口判定同样尊重调班调休`() {
        val r = rule()
        // 周六 10:00 在窗口内，但周六默认不上班
        assertFalse(CheckInValidator.isWithinTime(r, at(saturday, 10), emptyMap()))
        // 调班后成立
        val onSaturday = ShiftSchedule.table(listOf(ov(saturday, 1L, working = true)))
        assertTrue(CheckInValidator.isWithinTime(r, at(saturday, 10), onSaturday))
        // 调休后周一 10:00 反而不在时段内
        val offMonday = ShiftSchedule.table(listOf(ov(monday, 1L, working = false)))
        assertFalse(CheckInValidator.isWithinTime(r, at(monday, 10), offMonday))
    }

    @Test
    fun `调休当天不再产生应打卡班次`() {
        val r = rule()
        val table = ShiftSchedule.table(listOf(ov(monday, 1L, working = false)))
        val shifts = AttendanceCalculator.shiftsFor(monday, emptyList(), listOf(r), table)
        assertTrue(shifts.isEmpty())
        // 不加覆盖则是一个应打卡班次
        assertEquals(1, AttendanceCalculator.shiftsFor(monday, emptyList(), listOf(r)).size)
    }

    @Test
    fun `调班当天产生应打卡班次`() {
        val r = rule()
        val table = ShiftSchedule.table(listOf(ov(saturday, 1L, working = true)))
        val shifts = AttendanceCalculator.shiftsFor(saturday, emptyList(), listOf(r), table)
        assertEquals(1, shifts.size)
        assertEquals(r.name, shifts[0].rule.name)
    }

    @Test
    fun `调休后不再安排提醒`() {
        val r = rule()
        // 周一 08:00 看下一次提醒：默认是当天 08:45
        val normal = ReminderPlanner.nextReminder(
            rules = listOf(r), leadMillis = 15 * 60_000L, from = at(monday, 8)
        )!!
        assertEquals(at(monday, 8, 45), normal.triggerAt)

        // 把周一调休后，下一次提醒顺延到周二
        val table = ShiftSchedule.table(listOf(ov(monday, 1L, working = false)))
        val afterRest = ReminderPlanner.nextReminder(
            rules = listOf(r),
            leadMillis = 15 * 60_000L,
            from = at(monday, 8),
            overrides = table
        )!!
        assertEquals(at(LocalDate.of(2026, 3, 3), 8, 45), afterRest.triggerAt)
    }

    @Test
    fun `调班后休息日也会提醒`() {
        val r = rule()
        val table = ShiftSchedule.table(listOf(ov(saturday, 1L, working = true)))
        val plan = ReminderPlanner.nextReminder(
            rules = listOf(r),
            leadMillis = 15 * 60_000L,
            from = at(saturday, 8),
            overrides = table
        )!!
        assertEquals(at(saturday, 8, 45), plan.triggerAt)
    }
}
