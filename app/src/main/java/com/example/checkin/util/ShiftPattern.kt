package com.example.checkin.util

import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * 班制：规则在"哪几天生效"的表达方式。
 *
 * 支持两种：
 * 1. **每周固定**（[Kind.WEEKLY]）：沿用原有的星期位掩码，如周一至周五；
 * 2. **轮转班制**（[Kind.ROTATION]）：上 N 天休 M 天循环，如"上四休二"、
 *    保安/医护常见的"上二休二"。以锚点日期为周期起点，按天差取模判定。
 *
 * 序列化形式（存于 [com.example.checkin.data.CheckInRule.shiftPattern]）：
 * - `""` 或 `"W"`：每周固定，使用规则自身的 daysOfWeek；
 * - `"R:4/2:2026-01-05"`：上 4 天休 2 天，锚点 2026-01-05。
 */
data class ShiftPattern(
    val kind: Kind,
    /** 轮转班制：连续上班天数 */
    val workDays: Int = 0,
    /** 轮转班制：连续休息天数 */
    val restDays: Int = 0,
    /** 轮转班制：周期锚点（该日视为周期第 1 天，即上班日） */
    val anchor: LocalDate? = null
) {

    enum class Kind { WEEKLY, ROTATION }

    /** 周期总天数 */
    val cycleLength: Int get() = workDays + restDays

    /** 人类可读描述，如"上4休2" */
    val label: String
        get() = when (kind) {
            Kind.WEEKLY -> "每周固定"
            Kind.ROTATION -> "上${workDays}休${restDays}"
        }

    /**
     * 指定日期是否是班制内的"上班日"。
     *
     * 每周固定模式需要外部传入星期位掩码（[daysMask]）判定；
     * 轮转模式与星期无关，按与锚点的天数差对周期取模。
     */
    fun isWorkDay(date: LocalDate, daysMask: Int): Boolean = when (kind) {
        Kind.WEEKLY -> daysMask and (1 shl dayIndexOf(date)) != 0
        Kind.ROTATION -> {
            val a = anchor
            if (a == null || cycleLength <= 0) {
                // 表达式不完整时退化为每周掩码判定，避免误判为"永不生效"
                daysMask and (1 shl dayIndexOf(date)) != 0
            } else {
                val diff = ChronoUnit.DAYS.between(a, date)
                // Kotlin 的 mod 对负数返回非负结果，锚点之前的日期也能正确落在周期内
                (diff.mod(cycleLength.toLong())) < workDays
            }
        }
    }

    /** 序列化为存储字符串 */
    fun serialize(): String = when (kind) {
        Kind.WEEKLY -> ""
        Kind.ROTATION ->
            if (anchor == null) "" else "R:$workDays/$restDays:$anchor"
    }

    companion object {

        /** 周一起算的星期序号：周一=0 … 周日=6 */
        fun dayIndexOf(date: LocalDate): Int = (date.dayOfWeek.value + 6) % 7

        /**
         * 解析存储字符串；无法识别时返回每周固定模式。
         * 容错：缺少锚点或数值非法时仍返回轮转模式（isWorkDay 会退化为每周掩码），
         * 但序列化时会回落为空串，避免脏数据扩散。
         */
        fun parse(raw: String?): ShiftPattern {
            val s = raw?.trim().orEmpty()
            if (s.isEmpty() || s == "W") return ShiftPattern(Kind.WEEKLY)
            if (!s.startsWith("R:")) return ShiftPattern(Kind.WEEKLY)
            val parts = s.split(":")
            if (parts.size < 3) return ShiftPattern(Kind.WEEKLY)
            val cycles = parts[1].split("/")
            if (cycles.size != 2) return ShiftPattern(Kind.WEEKLY)
            val work = cycles[0].toIntOrNull() ?: return ShiftPattern(Kind.WEEKLY)
            val rest = cycles[1].toIntOrNull() ?: return ShiftPattern(Kind.WEEKLY)
            if (work <= 0 || rest < 0) return ShiftPattern(Kind.WEEKLY)
            val anchor = runCatching { LocalDate.parse(parts[2]) }.getOrNull()
            return ShiftPattern(Kind.ROTATION, work, rest, anchor)
        }

        /** 构造每周固定班制 */
        fun weekly(): ShiftPattern = ShiftPattern(Kind.WEEKLY)

        /** 构造轮转班制（默认锚点取今天） */
        fun rotation(workDays: Int, restDays: Int, anchor: LocalDate = LocalDate.now()): ShiftPattern =
            ShiftPattern(Kind.ROTATION, workDays, restDays, anchor)

        /** 常用轮转预设：上 N 休 M 中最常见的几种 */
        val COMMON_ROTATIONS: List<Pair<Int, Int>> = listOf(
            2 to 1, 2 to 2, 3 to 1, 4 to 2, 5 to 2, 7 to 7
        )
    }
}
