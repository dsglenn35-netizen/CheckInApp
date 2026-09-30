package com.example.checkin.util

import com.example.checkin.data.CheckInRule
import java.time.LocalDate
import java.util.Calendar

/**
 * 一次「打卡提醒」计划。
 *
 * @param slot 提醒针对的槽位：上班卡 [PunchSlot.IN] / 下班卡 [PunchSlot.OUT]
 * @param triggerAt 提醒触发时刻（毫秒）
 * @param slotFrom 该槽位区间的起点（含），用于"是否已打卡"的去重查询
 * @param slotTo 该槽位区间的终点（不含）
 * @param windowStart 该班次窗口开始时刻（提醒文案用）
 * @param windowEnd 该班次窗口结束时刻
 */
data class ReminderPlan(
    val ruleId: Long,
    val ruleName: String,
    val slot: PunchSlot,
    val triggerAt: Long,
    val slotFrom: Long,
    val slotTo: Long,
    val windowStart: Long,
    val windowEnd: Long
)

/**
 * 打卡提醒的时刻计算（纯逻辑，可单测）。
 *
 * 提醒规则与钉钉的"打卡提醒"对齐：
 * - **上班卡**：窗口开始前 [leadMillis] 提醒 —— "还有 15 分钟上班打卡"；
 * - **下班卡**：窗口结束前 [leadMillis] 提醒，且**仅对开启了"需要下班卡"的规则**，
 *   因为没开下班卡的规则整窗只打一次，提醒第二次会误导用户；
 * - 规则的**生效日**（每周几 / 轮转班制）照常生效，休息日不提醒；
 * - **请假 / 放假日**（[skippedDates]）整天不提醒，与自动打卡在该日静默的口径一致；
 * - 跨午夜窗口（22:00-06:00）归属**窗口开始的那一天**：22:00 的上班卡提醒排在当天 21:45，
 *   次日 06:00 的下班卡提醒排在次日 05:45，与考勤归属口径一致。
 *
 * **只排未来**：早于 [from] 的提醒不补发（不排"迟到的提醒"）。
 * 之所以不做"立即补一条"，是因为本函数会在规则变更、开机、服务刷新时被反复调用，
 * 补发会让用户在窗口内反复收到同一提醒；漏掉的边界由下一次调用自然接续。
 */
object ReminderPlanner {

    /**
     * 向前扫描的天数：覆盖一周的星期组合，也覆盖轮转班制最长周期（上 7 休 7 共 14 天），
     * 与 [CheckInValidator.nextBoundaryMillis] 的 15 天口径保持一致。
     */
    const val HORIZON_DAYS = 15

    /**
     * 计算 [from] 之后最近的一次打卡提醒；没有启用规则或没有未来提醒时返回 null。
     *
     * @param skippedDates 不需要打卡的日期（yyyy-MM-dd），通常是请假 / 放假日
     */
    fun nextReminder(
        rules: List<CheckInRule>,
        leadMillis: Long,
        from: Long = System.currentTimeMillis(),
        skippedDates: Set<String> = emptySet(),
        overrides: Map<String, Boolean> = emptyMap()
    ): ReminderPlan? {
        val lead = leadMillis.coerceAtLeast(0L)
        val dayCal = Calendar.getInstance().apply {
            timeInMillis = from
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        var best: ReminderPlan? = null

        repeat(HORIZON_DAYS) {
            val dayStart = dayCal.timeInMillis
            val date = LocalDate.of(
                dayCal.get(Calendar.YEAR),
                dayCal.get(Calendar.MONTH) + 1,
                dayCal.get(Calendar.DAY_OF_MONTH)
            )
            if (!skippedDates.contains(date.toString())) {
                for (rule in rules) {
                    val plan = plansForDay(rule, date, dayStart, lead, overrides)
                    for (candidate in plan) {
                        if (candidate.triggerAt <= from) continue
                        val current = best
                        if (current == null || candidate.triggerAt < current.triggerAt) {
                            best = candidate
                        }
                    }
                }
            }
            dayCal.add(Calendar.DAY_OF_YEAR, 1)
        }
        return best
    }

    /** 某规则在 [date]（窗口开始日）这一天的全部提醒候选（最多两条：上班卡 + 下班卡） */
    private fun plansForDay(
        rule: CheckInRule,
        date: LocalDate,
        dayStart: Long,
        lead: Long,
        overrides: Map<String, Boolean> = emptyMap()
    ): List<ReminderPlan> {
        if (!rule.enabled) return emptyList()
        val startSec = rule.startHour * 3600 + rule.startMinute * 60
        val endSec = rule.endHour * 3600 + rule.endMinute * 60
        // 开始与结束相同 = 空窗口，规则本就不生效
        if (startSec == endSec) return emptyList()
        // 调班 / 调休同样影响"这天要不要提醒"
        if (!ShiftSchedule.isWorkDay(rule, date, overrides)) return emptyList()

        val crossMidnight = startSec > endSec
        val windowStart = dayStart + startSec * 1000L
        val windowEnd = if (crossMidnight) {
            dayStart + 86_400_000L + endSec * 1000L
        } else {
            dayStart + endSec * 1000L
        }
        val mid = windowStart + (windowEnd - windowStart) / 2

        val plans = mutableListOf(
            ReminderPlan(
                ruleId = rule.id,
                ruleName = rule.name,
                slot = PunchSlot.IN,
                triggerAt = windowStart - lead,
                slotFrom = windowStart,
                slotTo = if (rule.requireCheckOut) mid else windowEnd,
                windowStart = windowStart,
                windowEnd = windowEnd
            )
        )
        if (rule.requireCheckOut) {
            plans += ReminderPlan(
                ruleId = rule.id,
                ruleName = rule.name,
                slot = PunchSlot.OUT,
                triggerAt = windowEnd - lead,
                slotFrom = mid,
                slotTo = windowEnd,
                windowStart = windowStart,
                windowEnd = windowEnd
            )
        }
        return plans
    }
}
