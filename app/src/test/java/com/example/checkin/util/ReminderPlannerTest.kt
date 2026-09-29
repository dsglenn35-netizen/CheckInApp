package com.example.checkin.util

import com.example.checkin.data.CheckInRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Calendar

/**
 * 打卡提醒时刻计算的单测。
 *
 * 所有期望值都用 [Calendar] 构造，与被测代码取同一时区，测试不依赖运行环境的时区。
 * 用到的日期锚点：2026-03-02 是**周一**（[周一锚点前提成立] 里有断言兜住这个前提），
 * 2026-03-03 周二、2026-03-04 周三、2026-03-09 周一。
 */
class ReminderPlannerTest {

    private val lead15 = 15 * 60_000L

    private fun cal(y: Int, mo: Int, d: Int, h: Int = 0, mi: Int = 0): Calendar =
        Calendar.getInstance().apply {
            set(y, mo - 1, d, h, mi, 0)
            set(Calendar.MILLISECOND, 0)
        }

    private fun rule(
        id: Long = 1,
        name: String = "早班",
        startHour: Int = 9,
        startMinute: Int = 0,
        endHour: Int = 18,
        endMinute: Int = 0,
        requireCheckOut: Boolean = false,
        daysOfWeek: Int = 127,
        shiftPattern: String = "",
        enabled: Boolean = true
    ) = CheckInRule(
        id = id,
        name = name,
        startHour = startHour,
        startMinute = startMinute,
        endHour = endHour,
        endMinute = endMinute,
        latitude = 0.0,
        longitude = 0.0,
        radiusMeters = 100.0,
        enabled = enabled,
        daysOfWeek = daysOfWeek,
        shiftPattern = shiftPattern,
        requireCheckOut = requireCheckOut
    )

    @Test
    fun `周一锚点前提成立`() {
        // 后面依赖星期的用例都建立在这个前提上，先把它显式钉死
        assertEquals(Calendar.MONDAY, cal(2026, 3, 2).get(Calendar.DAY_OF_WEEK))
    }

    @Test
    fun `上班卡提醒落在窗口开始前提前量处`() {
        val plan = ReminderPlanner.nextReminder(
            listOf(rule()), lead15, cal(2026, 3, 2, 8, 0).timeInMillis
        )!!
        assertEquals(PunchSlot.IN, plan.slot)
        assertEquals(cal(2026, 3, 2, 8, 45).timeInMillis, plan.triggerAt)
        assertEquals(cal(2026, 3, 2, 9, 0).timeInMillis, plan.slotFrom)
        assertEquals(cal(2026, 3, 2, 18, 0).timeInMillis, plan.slotTo)
    }

    @Test
    fun `已过今天的提醒时刻则顺延到明天`() {
        val plan = ReminderPlanner.nextReminder(
            listOf(rule()), lead15, cal(2026, 3, 2, 9, 30).timeInMillis
        )!!
        assertEquals(cal(2026, 3, 3, 8, 45).timeInMillis, plan.triggerAt)
    }

    @Test
    fun `恰好在提醒时刻不重复提醒`() {
        // 只排"严格未来"的提醒：等于触发时刻视为已过，顺延到下一天
        val plan = ReminderPlanner.nextReminder(
            listOf(rule()), lead15, cal(2026, 3, 2, 8, 45).timeInMillis
        )!!
        assertEquals(cal(2026, 3, 3, 8, 45).timeInMillis, plan.triggerAt)
    }

    @Test
    fun `非生效星期不提醒`() {
        // 只在周一上班；从周二早上看，下一次提醒是下周一
        val mondayOnly = rule(daysOfWeek = 1 shl 0)
        val plan = ReminderPlanner.nextReminder(
            listOf(mondayOnly), lead15, cal(2026, 3, 3, 8, 0).timeInMillis
        )!!
        assertEquals(cal(2026, 3, 9, 8, 45).timeInMillis, plan.triggerAt)
    }

    @Test
    fun `轮转班制的休息日不提醒`() {
        // 上2休1，锚点 2026-03-02（周一）：03-02 / 03-03 上班，03-04 休息，03-05 上班
        val rotation = rule(shiftPattern = "R:2/1:2026-03-02")
        val plan = ReminderPlanner.nextReminder(
            listOf(rotation), lead15, cal(2026, 3, 4, 8, 0).timeInMillis
        )!!
        assertEquals(cal(2026, 3, 5, 8, 45).timeInMillis, plan.triggerAt)
    }

    @Test
    fun `请假或放假日整天不提醒`() {
        val plan = ReminderPlanner.nextReminder(
            rules = listOf(rule()),
            leadMillis = lead15,
            from = cal(2026, 3, 2, 8, 0).timeInMillis,
            skippedDates = setOf("2026-03-02")
        )!!
        assertEquals(cal(2026, 3, 3, 8, 45).timeInMillis, plan.triggerAt)
    }

    @Test
    fun `未开启下班卡时整窗只提醒一次`() {
        // 13:00 已过上班卡提醒；若把 17:45（结束前 15 分钟）当作下班卡提醒就是错的
        val plan = ReminderPlanner.nextReminder(
            listOf(rule()), lead15, cal(2026, 3, 2, 13, 0).timeInMillis
        )!!
        assertEquals(cal(2026, 3, 3, 8, 45).timeInMillis, plan.triggerAt)
    }

    @Test
    fun `跨午夜窗口上班卡当天提醒下班卡次日提醒`() {
        val night = rule(startHour = 22, endHour = 6, requireCheckOut = true)

        val inPlan = ReminderPlanner.nextReminder(
            listOf(night), lead15, cal(2026, 3, 2, 20, 0).timeInMillis
        )!!
        assertEquals(PunchSlot.IN, inPlan.slot)
        assertEquals(cal(2026, 3, 2, 21, 45).timeInMillis, inPlan.triggerAt)
        assertEquals(cal(2026, 3, 2, 22, 0).timeInMillis, inPlan.slotFrom)
        assertEquals(cal(2026, 3, 3, 2, 0).timeInMillis, inPlan.slotTo)

        // 22:30 已在窗口内（处于上班卡槽），下一条应是次日的下班卡提醒
        val outPlan = ReminderPlanner.nextReminder(
            listOf(night), lead15, cal(2026, 3, 2, 22, 30).timeInMillis
        )!!
        assertEquals(PunchSlot.OUT, outPlan.slot)
        assertEquals(cal(2026, 3, 3, 5, 45).timeInMillis, outPlan.triggerAt)
        assertEquals(cal(2026, 3, 3, 2, 0).timeInMillis, outPlan.slotFrom)
        assertEquals(cal(2026, 3, 3, 6, 0).timeInMillis, outPlan.slotTo)
    }

    @Test
    fun `下班卡提醒按窗口结束时刻倒推`() {
        val day = rule(requireCheckOut = true)
        val plan = ReminderPlanner.nextReminder(
            listOf(day), lead15, cal(2026, 3, 2, 14, 0).timeInMillis
        )!!
        assertEquals(PunchSlot.OUT, plan.slot)
        assertEquals(cal(2026, 3, 2, 17, 45).timeInMillis, plan.triggerAt)
    }

    @Test
    fun `多条规则取最近的一次提醒`() {
        val early = rule(id = 1, name = "早班", startHour = 9, endHour = 18)
        val late = rule(id = 2, name = "晚班", startHour = 10, endHour = 19)
        val plan = ReminderPlanner.nextReminder(
            listOf(late, early), lead15, cal(2026, 3, 2, 8, 0).timeInMillis
        )!!
        assertEquals(1L, plan.ruleId)
        assertEquals(cal(2026, 3, 2, 8, 45).timeInMillis, plan.triggerAt)
    }

    @Test
    fun `提前量为零时恰在窗口开始提醒`() {
        val plan = ReminderPlanner.nextReminder(
            listOf(rule()), 0L, cal(2026, 3, 2, 8, 0).timeInMillis
        )!!
        assertEquals(cal(2026, 3, 2, 9, 0).timeInMillis, plan.triggerAt)
    }

    @Test
    fun `停用的规则不提醒`() {
        assertNull(
            ReminderPlanner.nextReminder(
                listOf(rule(enabled = false)), lead15, cal(2026, 3, 2, 8, 0).timeInMillis
            )
        )
    }

    @Test
    fun `空窗口规则不提醒`() {
        // 开始与结束相同 = 空窗口，规则本就不生效
        val empty = rule(startHour = 9, endHour = 9)
        assertNull(
            ReminderPlanner.nextReminder(
                listOf(empty), lead15, cal(2026, 3, 2, 8, 0).timeInMillis
            )
        )
    }

    @Test
    fun `没有规则时返回空`() {
        assertNull(
            ReminderPlanner.nextReminder(
                emptyList(), lead15, cal(2026, 3, 2, 8, 0).timeInMillis
            )
        )
    }

    /**
     * 交叉校验：提醒计划里的槽位区间必须与打卡引擎实际使用的槽位区间**逐毫秒一致**。
     * 两者若漂移，就会出现"提醒说还能打下班卡、实际却记成了上班卡"这类最难查的错。
     */
    @Test
    fun `槽位区间与打卡引擎一致`() {
        val day = rule(requireCheckOut = true)
        val inPlan = ReminderPlanner.nextReminder(
            listOf(day), lead15, cal(2026, 3, 2, 8, 0).timeInMillis
        )!!
        val inRange = CheckInValidator.punchSlotRangeFor(
            day, cal(2026, 3, 2, 9, 30).timeInMillis
        )
        assertEquals(PunchSlot.IN, inRange.slot)
        assertEquals(inPlan.slotFrom, inRange.from)
        assertEquals(inPlan.slotTo, inRange.to)

        val outPlan = ReminderPlanner.nextReminder(
            listOf(day), lead15, cal(2026, 3, 2, 14, 0).timeInMillis
        )!!
        val outRange = CheckInValidator.punchSlotRangeFor(
            day, cal(2026, 3, 2, 14, 0).timeInMillis
        )
        assertEquals(PunchSlot.OUT, outRange.slot)
        assertEquals(outPlan.slotFrom, outRange.from)
        assertEquals(outPlan.slotTo, outRange.to)
    }

    /** 跨午夜窗口的槽位区间同样要与引擎一致（凌晨段归属前一天） */
    @Test
    fun `跨午夜槽位区间与打卡引擎一致`() {
        val night = rule(startHour = 22, endHour = 6, requireCheckOut = true)
        val outPlan = ReminderPlanner.nextReminder(
            listOf(night), lead15, cal(2026, 3, 2, 22, 30).timeInMillis
        )!!
        val engine = CheckInValidator.punchSlotRangeFor(
            night, cal(2026, 3, 3, 2, 30).timeInMillis
        )
        assertEquals(PunchSlot.OUT, engine.slot)
        assertEquals(outPlan.slotFrom, engine.from)
        assertEquals(outPlan.slotTo, engine.to)
    }
}
