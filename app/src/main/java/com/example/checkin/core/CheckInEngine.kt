package com.example.checkin.core

import android.content.Context
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import com.example.checkin.data.CheckInRecord
import com.example.checkin.data.CheckInRepository
import com.example.checkin.data.CheckInRule
import com.example.checkin.data.CheckStatus
import com.example.checkin.data.TimeEntry
import com.example.checkin.location.LocationTracker
import com.example.checkin.util.CheckInValidator
import com.example.checkin.util.toLocalDate
import java.util.Calendar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.Locale

/** 一次打卡的结果：记录 + 本次获取到的定位（供 UI 展示） */
data class CheckInResult(
    val record: CheckInRecord,
    val location: Location?
)

/**
 * 打卡执行引擎：手动打卡与自动打卡共用同一套
 * “时间段 + 地点范围”校验、逆地理编码与入库逻辑。
 */
class CheckInEngine(
    private val appContext: Context,
    private val repository: CheckInRepository,
    private val locationTracker: LocationTracker
) {

    /**
     * 自动打卡串行锁：进入打卡时段瞬间会有多个并发调用方
     * （边界闹钟、周期轮询、GPS/网络定位回调），
     * 若并发执行“冷却查询 + 入库”会同时通过检查导致重复打卡，
     * 用互斥锁保证同规则防重复校验的原子性。
     */
    private val autoCheckInMutex = Mutex()

    companion object {
        /** 自动打卡失败记录冷却：同一规则失败后 30 分钟内不重复记录失败 */
        const val AUTO_FAIL_COOLDOWN_MS = 30 * 60_000L

        /** 直接使用的定位超过 5 分钟视为过期，需重新获取 */
        private const val MAX_LOCATION_AGE_MS = 5 * 60_000L
    }

    /**
     * 手动打卡：无论是否满足条件都会记录一次
     * （记录当时的系统时间、地点与结果状态）。
     *
     * @param photoPath 打卡取证照片的本地路径（可空）
     */
    suspend fun checkIn(
        now: Long = System.currentTimeMillis(),
        photoPath: String? = null
    ): CheckInResult {
        val ruleList = repository.enabledRules()
        val loc = locationTracker.requestCurrentLocation()
        val (status, rule) = evaluate(ruleList, loc, now)
        val address = withContext(Dispatchers.IO) { reverseGeocode(loc) }
        val record = CheckInRecord(
            timestamp = now,
            latitude = loc?.latitude ?: 0.0,
            longitude = loc?.longitude ?: 0.0,
            address = address,
            ruleName = rule?.name,
            status = status.name,
            photoPath = photoPath
        )
        repository.insertRecord(record)
        return CheckInResult(record, loc)
    }

    /**
     * 自动打卡：
     * - 时间 + 地点均符合某规则 → 记录成功（**同一规则同一打卡时段内只记录一次成功**）；
     * - 时间符合但地点不符（或定位失败）→ 记录失败并标注原因，含时间与地点
     *   （同规则 30 分钟内不重复，避免刷屏）；
     * - 不在任何规则的时间段内 → 不记录。
     *
     * @param location 调用方已有的最新定位（可空，过期会自动重新获取）
     * @param failCooldownMs 同一规则失败记录的防重复冷却
     */
    suspend fun autoCheckIn(
        now: Long = System.currentTimeMillis(),
        location: Location? = null,
        failCooldownMs: Long = AUTO_FAIL_COOLDOWN_MS
    ): CheckInRecord? = autoCheckInMutex.withLock {
        autoCheckInInternal(now, location, failCooldownMs)
    }

    /** 自动打卡实际逻辑（调用方必须先持有 [autoCheckInMutex]） */
    private suspend fun autoCheckInInternal(
        now: Long,
        location: Location?,
        failCooldownMs: Long
    ): CheckInRecord? {
        val ruleList = repository.enabledRules()
        if (ruleList.isEmpty()) return null

        // 请假：全天请假（请假模式）或当前时间落在某个请假时段内 → 不产生任何自动打卡记录（含失败记录）
        val todayKey = now.toLocalDate().toString()
        if (repository.leaveDay(todayKey) != null) return null
        val nowMinute = Calendar.getInstance().apply { timeInMillis = now }
            .let { it.get(Calendar.HOUR_OF_DAY) * 60 + it.get(Calendar.MINUTE) }
        val leaveRanges = repository.timeEntriesForDate(todayKey)
            .filter { it.type == TimeEntry.TYPE_LEAVE }
        if (leaveRanges.any { nowMinute in it.startMinute until it.endMinute }) return null

        // 不在任何规则的时段内：不记录
        val activeRule = ruleList.firstOrNull { CheckInValidator.isWithinTime(it, now) }
            ?: return null

        val loc = location?.takeIf { now - it.time <= MAX_LOCATION_AGE_MS }
            ?: locationTracker.requestCurrentLocation()

        // 成功：时间 + 地点均匹配
        val matchedRule = if (loc != null) {
            ruleList.firstOrNull {
                CheckInValidator.isWithinTime(it, now) &&
                    CheckInValidator.isWithinRange(it, loc.latitude, loc.longitude)
            }
        } else null

        val address = withContext(Dispatchers.IO) { reverseGeocode(loc) }

        if (matchedRule != null) {
            // 同一规则同一打卡时段内只记录一次成功（按窗口实例开始时刻去重）
            val since = CheckInValidator.windowStartMillis(matchedRule, now)
            if (repository.lastSuccess(matchedRule.name, since) != null) return null
            return CheckInRecord(
                timestamp = now,
                latitude = loc!!.latitude,
                longitude = loc.longitude,
                address = address,
                ruleName = matchedRule.name,
                status = CheckStatus.SUCCESS.name
            ).also { repository.insertRecord(it) }
        }

        // 失败：时段内但地点不符（或定位失败），记录时间与地点并标注原因
        val status = if (loc == null) CheckStatus.NO_LOCATION else CheckStatus.OUT_OF_RANGE
        if (repository.lastRecord(activeRule.name, now - failCooldownMs) != null) return null
        return CheckInRecord(
            timestamp = now,
            latitude = loc?.latitude ?: 0.0,
            longitude = loc?.longitude ?: 0.0,
            address = address,
            ruleName = activeRule.name,
            status = status.name
        ).also { repository.insertRecord(it) }
    }

    /**
     * 修正记录后重新判定状态：
     * 若记录修正后的时间与地点落在某个**启用规则**的窗口和半径内，
     * 则把"时间外/地点外"等失败状态升级为成功，并写入对应规则名；否则原样返回。
     */
    suspend fun reEvaluateStatus(record: CheckInRecord): CheckInRecord {
        val ruleList = repository.enabledRules()
        val matched = ruleList.firstOrNull {
            CheckInValidator.isWithinTime(it, record.timestamp) &&
                CheckInValidator.isWithinRange(it, record.latitude, record.longitude)
        } ?: return record
        return record.copy(status = CheckStatus.SUCCESS.name, ruleName = matched.name)
    }

    /** 时间段 + 地点校验，返回 (结果状态, 命中的规则) */
    private fun evaluate(
        ruleList: List<CheckInRule>,
        loc: Location?,
        now: Long
    ): Pair<CheckStatus, CheckInRule?> {
        if (ruleList.isEmpty()) return CheckStatus.NO_RULE to null

        val timeOk = ruleList.any { CheckInValidator.isWithinTime(it, now) }
        val locOk = loc != null &&
            ruleList.any { CheckInValidator.isWithinRange(it, loc.latitude, loc.longitude) }
        val successRule = if (loc != null) {
            ruleList.firstOrNull {
                CheckInValidator.isWithinTime(it, now) &&
                    CheckInValidator.isWithinRange(it, loc.latitude, loc.longitude)
            }
        } else null

        val status = when {
            successRule != null -> CheckStatus.SUCCESS
            loc == null -> CheckStatus.NO_LOCATION
            timeOk && locOk -> CheckStatus.OUT_OF_TIME_AND_RANGE
            timeOk -> CheckStatus.OUT_OF_RANGE
            locOk -> CheckStatus.OUT_OF_TIME
            else -> CheckStatus.OUT_OF_TIME_AND_RANGE
        }
        return status to successRule
    }

    /** 逆地理编码：坐标 -> 中文地址描述（失败返回 null） */
    @Suppress("DEPRECATION") // Geocoder.getFromLocation 同步版本在 API 33+ 标记废弃，仍可用且跨版本兼容
    private fun reverseGeocode(location: Location?): String? {
        if (location == null) return null
        return runCatching {
            val geocoder = Geocoder(appContext, Locale.getDefault())
            val results = geocoder.getFromLocation(location.latitude, location.longitude, 1)
            results?.firstOrNull()?.let { address ->
                listOfNotNull(
                    address.adminArea,
                    address.locality,
                    address.subLocality,
                    address.thoroughfare,
                    address.featureName
                ).joinToString(" ") { it }
            }
        }.getOrNull()
    }

    /**
     * 按经纬度逆地理编码（供“修正打卡记录”使用）。
     * 坐标全 0（无定位占位）时直接返回 null。
     */
    suspend fun addressFor(latitude: Double, longitude: Double): String? =
        withContext(Dispatchers.IO) {
            if (latitude == 0.0 && longitude == 0.0) null
            else reverseGeocode(
                Location(LocationManager.GPS_PROVIDER).apply {
                    this.latitude = latitude
                    this.longitude = longitude
                }
            )
        }
}
