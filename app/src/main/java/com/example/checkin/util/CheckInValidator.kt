package com.example.checkin.util

import com.example.checkin.data.CheckInRule
import java.util.Calendar
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** 打卡校验的纯逻辑：时间段匹配 + 地理距离匹配 */
object CheckInValidator {

    /**
     * 判断当前时间是否在规则的时间窗口内。
     * 支持跨午夜窗口（如 22:00 - 06:00）。
     */
    fun isWithinTime(rule: CheckInRule, timeMillis: Long): Boolean {
        val cal = Calendar.getInstance().apply { timeInMillis = timeMillis }
        return isWithinTime(rule, cal)
    }

    /**
     * 判断当前时间是否在规则的时间窗口内（含生效星期判断）。
     * 窗口为 [开始时刻, 结束时刻)，精确到秒，结束时刻不含整点：
     * 例如 07:50 - 08:00 表示 07:50:00 至 07:59:59 有效，08:00:00（含）起不再有效。
     * 支持跨午夜窗口（如 22:00 - 06:00）。
     */
    fun isWithinTime(rule: CheckInRule, cal: Calendar = Calendar.getInstance()): Boolean {
        if (!isActiveOnDay(rule, cal)) return false
        val now = cal.get(Calendar.HOUR_OF_DAY) * 3600 +
            cal.get(Calendar.MINUTE) * 60 + cal.get(Calendar.SECOND)
        val start = rule.startHour * 3600 + rule.startMinute * 60
        val end = rule.endHour * 3600 + rule.endMinute * 60
        return if (start <= end) {
            now in start until end
        } else {
            // 跨午夜：当前时间在 [start, 24:00) 或 [00:00, end)
            now >= start || now < end
        }
    }

    /** 判断规则在当天（星期）是否生效。dayIndex：周一=0 … 周日=6 */
    fun isActiveOnDay(rule: CheckInRule, cal: Calendar = Calendar.getInstance()): Boolean {
        val dayIndex = (cal.get(Calendar.DAY_OF_WEEK) + 5) % 7
        return rule.daysOfWeek and (1 shl dayIndex) != 0
    }

    /** 两个经纬度点之间的距离（米），Haversine 公式 */
    fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val earthRadius = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
            sin(dLon / 2) * sin(dLon / 2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return earthRadius * c
    }

    /** 判断坐标是否落在规则允许的半径范围内 */
    fun isWithinRange(rule: CheckInRule, lat: Double, lon: Double): Boolean =
        distanceMeters(rule.latitude, rule.longitude, lat, lon) <= rule.radiusMeters

    /**
     * 计算 [timeMillis] 所在规则窗口实例的开始时刻（毫秒），用于"同一规则同一时段只记一次成功"去重。
     * 调用前需保证 [timeMillis] 确实落在该规则窗口内（isWithinTime 为 true）。
     * 普通窗口（09:00-18:00）→ 当天开始时刻；跨午夜窗口（22:00-06:00）→
     * 若当前在 [00:00, 结束) 段则窗口开始于"昨天"的开始时刻，否则开始于当天。
     */
    fun windowStartMillis(rule: CheckInRule, timeMillis: Long): Long {
        val cal = Calendar.getInstance().apply { timeInMillis = timeMillis }
        val startSec = rule.startHour * 3600 + rule.startMinute * 60
        val endSec = rule.endHour * 3600 + rule.endMinute * 60
        val dayStartCal = Calendar.getInstance().apply {
            timeInMillis = timeMillis
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val dayStart = dayStartCal.timeInMillis
        if (startSec <= endSec) {
            return dayStart + startSec * 1000L
        }
        // 跨午夜窗口：判断当前处于当天的开始段（00:00 ~ 结束）还是结束段（开始 ~ 24:00）
        val nowSec = cal.get(Calendar.HOUR_OF_DAY) * 3600 +
            cal.get(Calendar.MINUTE) * 60 + cal.get(Calendar.SECOND)
        return if (nowSec < endSec) {
            // 开始段：窗口开始于"昨天"的 start
            dayStart - 86_400_000L + startSec * 1000L
        } else {
            // 结束段：窗口开始于当天 start
            dayStart + startSec * 1000L
        }
    }

    /**
     * 计算从 [from] 时刻起，下一次任意启用规则的时间窗口边界（开始或结束）时刻。
     * 返回严格大于 [from] 的最早边界；没有启用规则或没有未来边界时返回 null。
     *
     * 用途：自动打卡前台服务据此安排 AlarmManager 精确闹钟，
     * 在边界到达时唤醒设备切换"省电模式/打卡时段"，避免依赖低频轮询导致切换滞后或漏掉短窗口。
     * 扫描从 [from] 当天起 8 天，足以覆盖一周内所有星期组合。
     */
    fun nextBoundaryMillis(
        rules: List<CheckInRule>,
        from: Long = System.currentTimeMillis()
    ): Long? {
        val dayCal = Calendar.getInstance().apply {
            timeInMillis = from
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        var best: Long? = null
        val consider = { t: Long ->
            if (t > from && (best == null || t < best!!)) best = t
        }
        repeat(8) {
            val dayStart = dayCal.timeInMillis
            for (rule in rules) {
                if (!rule.enabled) continue
                val startSec = rule.startHour * 3600 + rule.startMinute * 60
                val endSec = rule.endHour * 3600 + rule.endMinute * 60
                if (startSec == endSec) continue // 空窗口（开始即结束），无边界
                val activeToday = isActiveOnDay(rule, dayCal)
                if (activeToday) {
                    // 生效日的窗口开始时刻
                    consider(dayStart + startSec * 1000L)
                }
                if (startSec <= endSec) {
                    // 同一天结束：结束时刻在生效日当天
                    if (activeToday) consider(dayStart + endSec * 1000L)
                } else {
                    // 跨午夜窗口（如 22:00-06:00）：结束时刻落在次日
                    consider(dayStart + 86_400_000L + endSec * 1000L)
                }
            }
            dayCal.add(Calendar.DAY_OF_YEAR, 1)
        }
        return best
    }
}
