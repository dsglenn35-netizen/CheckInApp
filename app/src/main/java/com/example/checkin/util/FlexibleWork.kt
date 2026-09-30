package com.example.checkin.util

import com.example.checkin.data.CheckInRecord
import com.example.checkin.data.CheckInRule
import java.time.LocalDate
import java.time.YearMonth

/** 一个自由工时日的达标情况 */
data class FlexibleDay(
    val date: LocalDate,
    val ruleName: String,
    val requiredMinutes: Int,
    val workedMinutes: Int
) {
    /** 当日工时是否达标 */
    val met: Boolean get() = workedMinutes >= requiredMinutes

    /** 还差多少分钟（已达标为 0） */
    val shortfallMinutes: Int get() = (requiredMinutes - workedMinutes).coerceAtLeast(0)
}

/** 自由工时的区间汇总（通常是一个月） */
data class FlexibleSummary(
    /** 统计到的应工作天数（已排除请假 / 放假与规则不生效的日期） */
    val requiredDays: Int = 0,
    /** 其中工时达标的天数 */
    val metDays: Int = 0,
    /** 应工作总分钟数 */
    val requiredMinutes: Int = 0,
    /** 实际累计工时（分钟） */
    val workedMinutes: Int = 0
) {
    /** 工时未达标的天数 */
    val shortfallDays: Int get() = requiredDays - metDays

    /** 要求的工时里实际完成了多少（0..1） */
    val completionRate: Float
        get() = if (requiredMinutes <= 0) 0f else workedMinutes.toFloat() / requiredMinutes

    /** 工时缺口（分钟） */
    val shortfallMinutes: Int get() = (requiredMinutes - workedMinutes).coerceAtLeast(0)
}

/**
 * 自由工时制（弹性工作制）的达标判定与工时汇总 —— 纯逻辑，可单测。
 *
 * ## 与固定班次的区别
 *
 * 固定班次关心"**几点到的**"（迟到 / 早退），自由工时关心"**干够了没有**"（当日工时是否达标）。
 * 因此自由工时规则不参与迟到早退判定（见 [ShiftAttendance.lateMinutes]），
 * 而额外多出一个"每日应工作分钟数"的目标。
 *
 * ## 两个口径上的取舍
 *
 * 1. **告假当天不算"未达标"**：请假 / 放假日你本来就不该上班，
 *    把它算成"工时不足"会让达标率莫名其妙地掉下来。调用方通过 [summarize] 的
 *    [excludedDates] 传入这些日期。
 * 2. **只有一次打卡的当天算未达标**：工时无法确定（可能只打了上班卡），
 *    记为 0 分钟即未达标 —— 这比"猜测他干满了"更诚实，也提示用户把下班卡补上。
 */
object FlexibleWork {

    /** 未设置每日工时目标 */
    const val NO_TARGET = -1

    /** 目标下限：低于半小时的目标没有实际意义，视为未设置 */
    const val MIN_TARGET_MINUTES = 30

    /** 目标上限：一天不可能超过 24 小时 */
    const val MAX_TARGET_MINUTES = 24 * 60

    /** 默认目标：8 小时 */
    const val DEFAULT_TARGET_MINUTES = 8 * 60

    /** 该规则是否按自由工时考核（自由工时 **且** 设了有效的每日工时目标） */
    fun isFlexibleWithTarget(rule: CheckInRule): Boolean =
        rule.flexible && rule.requiredWorkMinutes in MIN_TARGET_MINUTES..MAX_TARGET_MINUTES

    /** 把分钟数规整为合法目标；非法返回 [NO_TARGET] */
    fun sanitizeTarget(minutes: Int): Int =
        if (minutes in MIN_TARGET_MINUTES..MAX_TARGET_MINUTES) minutes else NO_TARGET

    /**
     * 某一天的达标情况；规则不是自由工时、未设目标、或当天该规则不生效时返回 null。
     *
     * [workedMinutes] 由 [ShiftAttendance.workMinutes] 提供（当天首末打卡之差）。
     */
    fun dayResult(rule: CheckInRule, date: LocalDate, workedMinutes: Int): FlexibleDay? {
        if (!isFlexibleWithTarget(rule)) return null
        if (!CheckInValidator.isActiveOnDate(rule, date)) return null
        return FlexibleDay(date, rule.name, rule.requiredWorkMinutes, workedMinutes)
    }

    /**
     * 汇总 [month] 内所有自由工时规则的达标情况。
     *
     * @param excludedDates 不需要上班的日期（请假 / 放假），这些日期不计入应工作天数
     */
    fun summarize(
        records: List<CheckInRecord>,
        rules: List<CheckInRule>,
        month: YearMonth,
        excludedDates: Set<LocalDate> = emptySet()
    ): FlexibleSummary {
        val flexibleRules = rules.filter { isFlexibleWithTarget(it) }
        if (flexibleRules.isEmpty()) return FlexibleSummary()
        val byDate = AttendanceCalculator.groupByAttendanceDate(records, rules)
        var requiredDays = 0
        var metDays = 0
        var requiredMinutes = 0
        var workedMinutes = 0
        for (dayOfMonth in 1..month.lengthOfMonth()) {
            val date = month.atDay(dayOfMonth)
            if (date in excludedDates) continue
            val dayRecords = byDate[date].orEmpty()
            for (rule in flexibleRules) {
                // shiftsFor 内部按班制/星期过滤，规则当天不生效时不会返回班次
                val shift = AttendanceCalculator.shiftsFor(date, dayRecords, listOf(rule))
                    .firstOrNull() ?: continue
                requiredDays++
                requiredMinutes += rule.requiredWorkMinutes
                workedMinutes += shift.workMinutes
                if (shift.workMinutes >= rule.requiredWorkMinutes) metDays++
            }
        }
        return FlexibleSummary(
            requiredDays = requiredDays,
            metDays = metDays,
            requiredMinutes = requiredMinutes,
            workedMinutes = workedMinutes
        )
    }
}
