package com.example.checkin.util

import com.example.checkin.data.CheckInRule
import com.example.checkin.data.CheckInSite
import com.example.checkin.data.MatchSource
import com.example.checkin.data.TimeEntry
import java.util.Calendar
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** 地点匹配结果：是否命中 + 命中的地点说明 + 验证方式 */
data class MatchResult(
    /** 是否落在某个打卡点的允许范围内 */
    val matched: Boolean,
    /** 命中的地点名称（主地点或附加地点），未命中为 null */
    val siteName: String? = null,
    /** 验证方式：GPS 或 WiFi 兜底 */
    val source: MatchSource = MatchSource.GPS,
    /** 距最近打卡点的距离（米），无坐标时为 null */
    val distanceMeters: Double? = null
)

/** 一个班次内的打卡槽位：上班卡 / 下班卡 */
enum class PunchSlot { IN, OUT }

/** 打卡槽位在窗口内的时间区间 [from, to) */
data class PunchSlotRange(val slot: PunchSlot, val from: Long, val to: Long)

/**
 * 打卡校验的纯逻辑：时间段匹配 + 地理距离匹配。
 *
 * 本对象只做判定、不落库，但地点验证方式沿用数据层的
 * [com.example.checkin.data.MatchSource]，不再自建同名枚举
 * （原先两处各有一份、引擎里还得手工转换，新增验证方式时极易漏改一处）。
 */
object CheckInValidator {

    /**
     * 判断当前时间是否在规则的时间窗口内。
     * 支持跨午夜窗口（如 22:00 - 06:00）。
     */
    fun isWithinTime(
        rule: CheckInRule,
        timeMillis: Long,
        overrides: Map<String, Boolean> = emptyMap()
    ): Boolean {
        val cal = Calendar.getInstance().apply { timeInMillis = timeMillis }
        return isWithinTime(rule, cal, overrides)
    }

    /**
     * 判断当前时间是否在规则的时间窗口内（含生效星期判断）。
     * 窗口为 [开始时刻, 结束时刻)，精确到秒，结束时刻不含整点：
     * 例如 07:50 - 08:00 表示 07:50:00 至 07:59:59 有效，08:00:00（含）起不再有效。
     * 支持跨午夜窗口（如 22:00 - 06:00）。
     *
     * **生效星期以"窗口开始的那一天"为准**：跨午夜窗口的 [00:00, end) 段已经跨到次日，
     * 此时必须回看前一天是否生效。否则「周一 22:00-06:00」的夜班在周二凌晨会被判为
     * 不在窗口内 —— 夜班的凌晨段整段失效，这既影响打卡记录，也让后续的考勤归属失效。
     */
    fun isWithinTime(
        rule: CheckInRule,
        cal: Calendar = Calendar.getInstance(),
        overrides: Map<String, Boolean> = emptyMap()
    ): Boolean {
        val now = cal.get(Calendar.HOUR_OF_DAY) * 3600 +
            cal.get(Calendar.MINUTE) * 60 + cal.get(Calendar.SECOND)
        val start = rule.startHour * 3600 + rule.startMinute * 60
        val end = rule.endHour * 3600 + rule.endMinute * 60
        if (start <= end) {
            return isActiveOnDay(rule, cal, overrides) && now in start until end
        }
        // 跨午夜窗口：窗口归属于它开始的那一天
        return if (now >= start) {
            // 前段 [start, 24:00)：发生在开始当天，看当天是否生效
            isActiveOnDay(rule, cal, overrides)
        } else if (now < end) {
            // 后段 [00:00, end)：已跨到次日，看**前一天**是否生效
            isActiveOnDay(
                rule,
                (cal.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -1) },
                overrides
            )
        } else {
            false
        }
    }

    /**
     * [minuteOfDay]（当天分钟数）是否落在任一时间段请假 / 放假的区间内。
     *
     * 口径与打卡规则完全一致：区间为 [startMinute, endMinute)，**结束整点不计入**
     * （如 09:00-12:00 表示 09:00 至 11:59 有效）。加班时段不属于"不用打卡"，不参与判定。
     *
     * 抽成纯函数是为了能脱离数据库单测边界（含结束整点、加班不误判）。
     */
    fun isWithinTimeOff(entries: List<TimeEntry>, minuteOfDay: Int): Boolean =
        entries.any { it.isTimeOff && minuteOfDay in it.startMinute until it.endMinute }

    /** 判断规则在当天（星期/班制）是否生效。dayIndex：周一=0 … 周日=6 */
    fun isActiveOnDay(
        rule: CheckInRule,
        cal: Calendar = Calendar.getInstance(),
        overrides: Map<String, Boolean> = emptyMap()
    ): Boolean = isActiveOnDate(rule, cal.toLocalDate(), overrides)

    /**
     * 判断规则在指定日期是否生效。
     *
     * **例外优先于周期**：先查 [overrides]（调班 / 调休，键由
     * [com.example.checkin.data.ShiftOverride.key] 生成）；没有覆盖记录时再按班制
     * （[ShiftPattern]）判定，每周固定模式回退到 [CheckInRule.daysOfWeek] 位掩码。
     */
    fun isActiveOnDate(
        rule: CheckInRule,
        date: java.time.LocalDate,
        overrides: Map<String, Boolean> = emptyMap()
    ): Boolean {
        ShiftSchedule.overrideFor(rule, date, overrides)?.let { return it }
        return ShiftPattern.parse(rule.shiftPattern).isWorkDay(date, rule.daysOfWeek)
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
     * 地点匹配：支持**一个规则多个打卡点**（规则自带主地点 + [sites] 里的附加地点），
     * 并在定位不可靠时用 WiFi SSID 兜底。
     *
     * 判定顺序：
     * 1. 主地点半径内 → GPS 命中；
     * 2. 任一附加地点半径内 → GPS 命中（返回该地点名）；
     * 3. **定位不可用或不可信**（无坐标，或 [gpsUsable] 为 false）且连接了已登记的 WiFi →
     *    WiFi 命中。
     *
     * 注意：坐标有效且 [gpsUsable] 为 true 时，**GPS 未命中就是未命中**，不再用 WiFi 兜底。
     * 理由是证据强度：GPS 是打卡的强证据，而 SSID 极易伪造、也常重名（TP-LINK、CMCC 之类），
     * 若允许 WiFi 覆盖可信的 GPS 判定，等于把"人在不在现场"的判定权交给可伪造的信号。
     * WiFi 只承担 GPS 无法工作的场景：室内漂移、冷启动、无信号。
     *
     * @param gpsUsable 定位是否可信（有坐标、精度达标且足够新鲜）；
     *                  仅当其为 false 或坐标缺失时 WiFi 兜底才生效
     */
    fun matchRange(
        rule: CheckInRule,
        sites: List<CheckInSite>,
        lat: Double?,
        lon: Double?,
        currentSsid: String?,
        gpsUsable: Boolean
    ): MatchResult {
        // 先算出离最近打卡点的距离（供 UI 提示"还差多少米"）
        var nearest: Double? = null
        var nearestName: String? = null
        if (lat != null && lon != null) {
            val primary = distanceMeters(rule.latitude, rule.longitude, lat, lon)
            nearest = primary
            nearestName = rule.name
            for (s in sites) {
                val d = distanceMeters(s.latitude, s.longitude, lat, lon)
                if (nearest == null || d < nearest) {
                    nearest = d
                    nearestName = s.name
                }
            }
        }

        if (lat != null && lon != null) {
            // 主地点
            if (distanceMeters(rule.latitude, rule.longitude, lat, lon) <= rule.radiusMeters) {
                return MatchResult(true, rule.name, MatchSource.GPS, nearest)
            }
            // 附加地点：取第一个命中的（距离最近的点位优先更符合直觉，这里按登记顺序即可）
            for (s in sites) {
                if (distanceMeters(s.latitude, s.longitude, lat, lon) <= s.radiusMeters) {
                    return MatchResult(true, s.name, MatchSource.GPS, nearest)
                }
            }
        }

        // WiFi 兜底：定位不可靠（室内漂移/无信号）或坐标未命中时，按已登记 SSID 放行
        val ssid = currentSsid?.trim().orEmpty()
        if (ssid.isNotEmpty() && (!gpsUsable || nearest == null)) {
            if (matchesSsid(rule.wifiSsid, ssid)) {
                return MatchResult(true, "${rule.name}（WiFi）", MatchSource.WIFI, nearest)
            }
            for (s in sites) {
                if (matchesSsid(s.wifiSsid, ssid)) {
                    return MatchResult(true, "${s.name}（WiFi）", MatchSource.WIFI, nearest)
                }
            }
        }

        return MatchResult(false, nearestName, MatchSource.GPS, nearest)
    }

    /** SSID 列表匹配（登记值以英文逗号分隔，忽略大小写与首尾空白） */
    fun matchesSsid(registered: String?, current: String?): Boolean {
        val cur = current?.trim().orEmpty()
        if (cur.isEmpty()) return false
        val list = registered?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: return false
        return list.any { it.equals(cur, ignoreCase = true) }
    }

    /** 坐标系是否有效（排除未定位时的 0,0 占位） */
    fun hasCoordinates(lat: Double?, lon: Double?): Boolean =
        lat != null && lon != null && !(lat == 0.0 && lon == 0.0)

    /**
     * 定位是否可信：有坐标、精度达标（[maxAccuracyMeters] 以内）且足够新鲜。
     *
     * 自动打卡在"定位不可信"时**不记录失败**（等定位稳定后重判），
     * 但允许 WiFi 兜底命中成功，因此该判定同时服务于失败降噪与 WiFi 放行。
     */
    fun isLocationReliable(
        lat: Double?,
        lon: Double?,
        accuracyMeters: Float,
        locationAgeMs: Long,
        maxAccuracyMeters: Float = 200f,
        maxAgeMs: Long = 2 * 60_000L
    ): Boolean {
        if (!hasCoordinates(lat, lon)) return false
        if (accuracyMeters > maxAccuracyMeters) return false
        if (locationAgeMs < 0 || locationAgeMs > maxAgeMs) return false
        return true
    }

    /** 系统时间与定位授时之差超过该值即视为时钟异常（仅留痕与提示，不阻断打卡） */
    const val CLOCK_SKEW_WARN_MS = 5 * 60_000L

    /** 时钟是否可疑异常 */
    fun isClockSkewed(skewMs: Long?): Boolean =
        skewMs != null && kotlin.math.abs(skewMs) > CLOCK_SKEW_WARN_MS

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
     * 规则窗口的总时长（毫秒）。跨午夜窗口（22:00-06:00）正确返回跨过午夜的 8 小时。
     * 开始与结束相同时返回 0（空窗口，规则本就不生效）。
     */
    fun windowDurationMillis(rule: CheckInRule): Long {
        val startSec = rule.startHour * 3600 + rule.startMinute * 60
        val endSec = rule.endHour * 3600 + rule.endMinute * 60
        if (startSec == endSec) return 0L
        val spanSec = if (endSec > startSec) endSec - startSec else endSec - startSec + 24 * 3600
        return spanSec * 1000L
    }

    /**
     * [timeMillis] 落在该规则窗口的哪个**打卡槽位**。
     *
     * - 未开启 [CheckInRule.requireCheckOut]：整个窗口只有一个槽位 [PunchSlot.IN]，
     *   维持"同一时段只记一次成功"的原有语义；
     * - 开启后：窗口对半分为上班卡 [PunchSlot.IN] 与下班卡 [PunchSlot.OUT]，各自只记一次。
     *
     * 对半分而不是"打完第一次就允许第二次"，是因为自动打卡会周期轮询（60 秒一次）：
     * 若第二次不限时间，上班卡打完 60 秒后就会被记成下班卡，在岗时长恒为 1 分钟。
     */
    fun punchSlotFor(rule: CheckInRule, timeMillis: Long): PunchSlot =
        punchSlotRangeFor(rule, timeMillis).slot

    /**
     * [timeMillis] 所在槽位的时间区间 [from, to)（毫秒），
     * 用于"该槽位是否已经打过卡"的去重查询。
     */
    fun punchSlotRangeFor(rule: CheckInRule, timeMillis: Long): PunchSlotRange {
        val start = windowStartMillis(rule, timeMillis)
        val duration = windowDurationMillis(rule)
        if (!rule.requireCheckOut) {
            return PunchSlotRange(PunchSlot.IN, start, start + duration)
        }
        val mid = start + duration / 2
        return if (timeMillis < mid) {
            PunchSlotRange(PunchSlot.IN, start, mid)
        } else {
            PunchSlotRange(PunchSlot.OUT, mid, start + duration)
        }
    }

    /**
     * 计算从 [from] 时刻起，下一次任意启用规则的时间窗口边界（开始或结束）时刻。
     * 返回严格大于 [from] 的最早边界；没有启用规则或没有未来边界时返回 null。
     *
     * 用途：自动打卡前台服务据此安排 AlarmManager 精确闹钟，
     * 在边界到达时唤醒设备切换"省电模式/打卡时段"，避免依赖低频轮询导致切换滞后或漏掉短窗口。
     * 扫描从 [from] 当天起 15 天：既覆盖一周内所有星期组合，
     * 也覆盖轮转班制最长周期（上 7 休 7 共 14 天），保证下一次边界必定被找到。
     */
    fun nextBoundaryMillis(
        rules: List<CheckInRule>,
        from: Long = System.currentTimeMillis(),
        overrides: Map<String, Boolean> = emptyMap()
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
        repeat(15) {
            val dayStart = dayCal.timeInMillis
            for (rule in rules) {
                if (!rule.enabled) continue
                val startSec = rule.startHour * 3600 + rule.startMinute * 60
                val endSec = rule.endHour * 3600 + rule.endMinute * 60
                if (startSec == endSec) continue // 空窗口（开始即结束），无边界
                val activeToday = isActiveOnDay(rule, dayCal, overrides)
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
