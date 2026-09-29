package com.example.checkin.util

import com.example.checkin.data.CheckInRecord
import com.example.checkin.data.CheckStatus
import java.time.LocalDate

/**
 * 统计汇总：今日 + 本月 + 连续打卡。
 *
 * **口径**：这里的 "Success" 一律指**有效出勤**（正常打卡 + 外勤打卡），
 * 判定走 [CheckStatus.isAttended]。加入外勤后若仍写 `status == SUCCESS`，
 * 外勤会被算进"失败"与"未出勤"，而这种错只体现在数字上、界面上看不出来。
 * 外勤次数由 [todayFieldWork] 单独给出，供界面区分展示。
 */
data class StatsSummary(
    val todaySuccess: Int = 0,
    /** 今天改判为外勤的次数（**已计入** [todaySuccess]，此处仅为分开显示） */
    val todayFieldWork: Int = 0,
    val todayFail: Int = 0,
    val monthSuccess: Int = 0,
    val monthAttempts: Int = 0,
    val monthAttendanceDays: Int = 0,
    val streakDays: Int = 0
) {
    /** 今天是否打过卡（无论成败，也无论是否外勤） */
    val todayCheckedIn: Boolean get() = todaySuccess + todayFail > 0

    /** 本月按时率 0..1（外勤按"已出勤"计入分子） */
    val monthRate: Float get() = if (monthAttempts == 0) 0f else monthSuccess.toFloat() / monthAttempts
}

fun computeStats(
    records: List<CheckInRecord>,
    today: LocalDate = LocalDate.now()
): StatsSummary {
    val todayRecords = records.filter { it.timestamp.toLocalDate() == today }
    val todaySuccess = todayRecords.count { CheckStatus.isAttended(it.status) }
    val todayFieldWork = todayRecords.count { it.status == CheckStatus.FIELD_WORK.name }
    val todayFail = todayRecords.count { CheckStatus.isAbsent(it.status) }

    val inMonth = records.filter {
        val d = it.timestamp.toLocalDate()
        d.year == today.year && d.monthValue == today.monthValue
    }
    val monthSuccess = inMonth.count { CheckStatus.isAttended(it.status) }
    val monthAttendanceDays = inMonth
        .filter { CheckStatus.isAttended(it.status) }
        .map { it.timestamp.toLocalDate() }
        .distinct()
        .size

    // 连续打卡天数：从今天（或昨天）往前数连续有出勤记录的天数。
    // 失败记录所在的那天会中断连续，因此只取出勤日。
    val successDates = records
        .filter { CheckStatus.isAttended(it.status) }
        .map { it.timestamp.toLocalDate() }
        .toSet()
    var streak = 0
    var day = if (successDates.contains(today)) today else today.minusDays(1)
    while (successDates.contains(day)) {
        streak++
        day = day.minusDays(1)
    }

    return StatsSummary(
        todaySuccess = todaySuccess,
        todayFieldWork = todayFieldWork,
        todayFail = todayFail,
        monthSuccess = monthSuccess,
        monthAttempts = inMonth.size,
        monthAttendanceDays = monthAttendanceDays,
        streakDays = streak
    )
}

/** 星期位掩码 -> 中文描述（bit0=周一 … bit6=周日） */
fun formatDaysOfWeek(mask: Int): String {
    val m = mask and 0b1111111
    if (m == 0) return "不生效"
    if (m == 0b1111111) return "每天"
    if (m == 0b0011111) return "周一至周五"
    if (m == 0b1100000) return "周六、周日"
    val names = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")
    return names.filterIndexed { i, _ -> m and (1 shl i) != 0 }.joinToString("、")
}
