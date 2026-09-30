package com.example.checkin.util

/** 一个班次在"缺卡 / 旷工"上的判定结果 */
enum class AbsenceKind {
    /** 打卡完整，无需标记 */
    NONE,

    /** **缺卡**：该班次完全没打卡，或只打了一次（未构成完整的上下班） */
    MISSING,

    /** **旷工**：打了卡，但比应到时刻晚了阈值以上 */
    ABSENT
}

/** 缺卡 / 旷工的汇总 */
data class AbsenceSummary(
    val missingShifts: Int = 0,
    val absentShifts: Int = 0
) {
    val total: Int get() = missingShifts + absentShifts
}

/**
 * 缺卡与旷工的判定（纯逻辑，可单测）。
 *
 * ## 两者的区别
 *
 * - **缺卡**：该打卡却没打 —— 完全没记录，或需要下班卡却只打了一次；
 * - **旷工**：打了，但来得太晚（比应到时刻晚 [DEFAULT_ABSENT_AFTER_MINUTES] 分钟以上）。
 *
 * ## 为什么旷工要求"配置了应到时刻"
 *
 * 没配应到时刻的规则根本没有"迟到"这个概念（[ShiftAttendance.lateMinutes] 直接返回 0），
 * 自然不该判旷工；自由工时制同理 —— 它的考核口径是"当日工时够不够"，
 * 几点来不重要，判它旷工是拿错了尺子。
 *
 * 判定**以班次为单位**：一天多段班时，上午班旷工不会牵连下午班。
 */
object AbsencePolicy {

    /** 默认旷工阈值：比应到时刻晚 60 分钟以上记为旷工 */
    const val DEFAULT_ABSENT_AFTER_MINUTES = 60

    /**
     * 班次判定。优先级 **旷工 > 缺卡 > 正常**：
     * 一条班次既晚到又缺下班卡时，更严重的问题是"人根本没按时来"。
     */
    fun kindFor(
        shift: ShiftAttendance,
        absentAfterMinutes: Int = DEFAULT_ABSENT_AFTER_MINUTES
    ): AbsenceKind {
        val threshold = absentAfterMinutes.coerceAtLeast(1)
        if (shift.rule.requiredStartMinute >= 0 && shift.lateMinutes >= threshold) {
            return AbsenceKind.ABSENT
        }
        val needsTwoPunches = shift.rule.requireCheckOut || shift.rule.requiredEndMinute >= 0
        if (shift.punches.isEmpty()) return AbsenceKind.MISSING
        if (needsTwoPunches && shift.punches.size < 2) return AbsenceKind.MISSING
        return AbsenceKind.NONE
    }

    /** 汇总某一天（或任意一组班次）的缺卡与旷工数 */
    fun summarize(
        shifts: List<ShiftAttendance>,
        absentAfterMinutes: Int = DEFAULT_ABSENT_AFTER_MINUTES
    ): AbsenceSummary {
        var missing = 0
        var absent = 0
        shifts.forEach {
            when (kindFor(it, absentAfterMinutes)) {
                AbsenceKind.MISSING -> missing++
                AbsenceKind.ABSENT -> absent++
                AbsenceKind.NONE -> Unit
            }
        }
        return AbsenceSummary(missingShifts = missing, absentShifts = absent)
    }

    /** 报表文案 */
    fun label(kind: AbsenceKind): String = when (kind) {
        AbsenceKind.NONE -> "正常"
        AbsenceKind.MISSING -> "缺卡"
        AbsenceKind.ABSENT -> "旷工"
    }
}
