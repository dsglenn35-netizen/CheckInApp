package com.example.checkin.util

import com.example.checkin.data.CheckInRule
import com.example.checkin.data.ShiftOverride
import java.time.LocalDate

/**
 * 调班 / 调休的查表逻辑（纯逻辑，可单测）。
 *
 * 语义：**例外优先于周期**。某条规则在某一天有覆盖记录时以它为准，
 * 否则回落到规则自身的星期 / 轮转班制（[CheckInValidator.isActiveOnDate]）。
 */
object ShiftSchedule {

    /** 由数据库行构建查找表（key -> 当天是否上班） */
    fun table(overrides: List<ShiftOverride>): Map<String, Boolean> =
        overrides.associate { ShiftOverride.key(it.date, it.ruleId) to it.working }

    /** 该规则在该日期的覆盖值；没有覆盖返回 null（表示"按规则默认"） */
    fun overrideFor(
        rule: CheckInRule,
        date: LocalDate,
        overrides: Map<String, Boolean>
    ): Boolean? = overrides[ShiftOverride.key(date.toString(), rule.id)]

    /**
     * 该规则在 [date] 当天**实际**是否需要上班。
     *
     * 注意这里调用的是 [CheckInValidator.isActiveOnDate] 的**默认口径**
     * （不带覆盖表），因此不会与它形成递归 —— 覆盖的判定只在调用方传入 overrides 时生效。
     */
    fun isWorkDay(
        rule: CheckInRule,
        date: LocalDate,
        overrides: Map<String, Boolean> = emptyMap()
    ): Boolean =
        overrideFor(rule, date, overrides) ?: CheckInValidator.isActiveOnDate(rule, date)

    /** 日历 / 报表上的说明文案 */
    fun label(working: Boolean): String = if (working) "调班上班" else "调休"
}
