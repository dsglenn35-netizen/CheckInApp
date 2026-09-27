package com.example.checkin.core

import android.content.Context
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import com.example.checkin.data.CheckInRecord
import com.example.checkin.data.CheckInRepository
import com.example.checkin.data.CheckInRule
import com.example.checkin.data.CheckInSite
import com.example.checkin.data.CheckStatus
import com.example.checkin.data.MatchSource
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
 * "时间段 + 地点范围（多打卡点 + WiFi 兜底）"校验、逆地理编码与入库逻辑。
 */
class CheckInEngine(
    private val appContext: Context,
    private val repository: CheckInRepository,
    private val locationTracker: LocationTracker
) {

    /**
     * 自动打卡串行锁：进入打卡时段瞬间会有多个并发调用方
     * （边界闹钟、周期轮询、GPS/网络定位回调），
     * 若并发执行"冷却查询 + 入库"会同时通过检查导致重复打卡，
     * 用互斥锁保证同规则防重复校验的原子性。
     */
    private val autoCheckInMutex = Mutex()

    companion object {
        /** 自动打卡失败记录冷却：同一规则失败后 30 分钟内不重复记录失败 */
        const val AUTO_FAIL_COOLDOWN_MS = 30 * 60_000L

        /** 直接使用的定位超过 5 分钟视为过期，需重新获取 */
        private const val MAX_LOCATION_AGE_MS = 5 * 60_000L

        /**
         * 自动打卡判定"失败"前，定位必须足够新鲜（≤2 分钟）。
         * 首次判定时 GPS 常尚未锁定，拿到的是网络粗定位或旧的兜底位置，
         * 坐标偏差可能把"正确地点"误判成地点外 → 产生"先失败后成功"噪音；
         * 定位不够新鲜时不记录失败，等 GPS 稳定后由轮询/定位回调重新判定。
         */
        private const val FAIL_LOCATION_MAX_AGE_MS = 2 * 60_000L

        /** 判定"定位可信"允许的最大精度（米）：超过视为粗定位，可用于 WiFi 兜底 */
        private const val RELIABLE_ACCURACY_M = 100f

        /**
         * 系统时间与定位时间之差（毫秒，带符号）。
         *
         * 打卡应用完全依赖设备系统时间判定"是否在时段内"，用户手动改时间即可绕过。
         * GPS 授时是外部可信参照：偏差过大时在记录上留痕、UI 与导出中标记，
         * 使时间被修改这件事可被察觉。
         *
         * 网络定位的 time 字段不可靠（可能来自缓存），因此仅接受 GPS 定位来源。
         */
        fun systemClockSkewMs(loc: Location?, now: Long): Long? {
            if (loc == null) return null
            if (!LocationManager.GPS_PROVIDER.equals(loc.provider, ignoreCase = true)) return null
            if (loc.time <= 0L) return null
            // 定位自身过于陈旧时其授时无参考价值
            if (now - loc.time > MAX_LOCATION_AGE_MS) return null
            return now - loc.time
        }

        // 时钟异常阈值与判定统一由 CheckInValidator.isClockSkewed 提供，
        // 此处不再重复定义，避免两处常量各自漂移。
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
        val sitesByRule = repository.sitesGroupedByRule()
        val loc = locationTracker.requestCurrentLocation()
        val ssid = locationTracker.currentWifiSsid()
        val (status, rule, siteName, source) = evaluate(ruleList, sitesByRule, loc, ssid, now)
        val address = withContext(Dispatchers.IO) { reverseGeocode(loc) }
        val record = CheckInRecord(
            timestamp = now,
            latitude = loc?.latitude ?: 0.0,
            longitude = loc?.longitude ?: 0.0,
            address = address,
            ruleName = rule?.name,
            ruleId = rule?.id ?: 0L,
            status = status.name,
            matchSource = source.name,
            clockSkewMs = systemClockSkewMs(loc, now),
            photoPath = photoPath
        )
        repository.insertRecord(record)
        return CheckInResult(record, loc)
    }

    /**
     * 自动打卡：
     * - 时间 + 地点（任一步行点或 WiFi 兜底）符合某规则 → 记录成功
     *   （**同一规则同一打卡时段内只记录一次成功**，按规则主键去重）；
     * - 时间符合、定位可信（新鲜 ≤2 分钟且精度达标）但地点不符 → 记录失败并标注原因
     *   （同规则 30 分钟内不重复，避免刷屏）；
     * - 定位尚未就绪（GPS 冷启动/无信号/位置过期）→ 不记录失败，等定位稳定后重新判定，
     *   避免进入时段瞬间的粗定位把正确地点误判为失败；
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

        // 全天请假 / 公司放假：整天不产生任何自动打卡记录（含失败记录）。
        // 两种标记语义相同（当天不用打卡），区别只在报表里的计数口径。
        val todayKey = now.toLocalDate().toString()
        if (repository.leaveDay(todayKey) != null) return null
        // 按时间段的请假 / 放假：落在该时段内同样不产生记录；加班时段不参与本判定
        val nowMinute = Calendar.getInstance().apply { timeInMillis = now }
            .let { it.get(Calendar.HOUR_OF_DAY) * 60 + it.get(Calendar.MINUTE) }
        if (CheckInValidator.isWithinTimeOff(repository.timeEntriesForDate(todayKey), nowMinute)) {
            return null
        }

        // 不在任何规则的时段内：不记录
        val activeRule = ruleList.firstOrNull { CheckInValidator.isWithinTime(it, now) }
            ?: return null

        val loc = location?.takeIf { now - it.time <= MAX_LOCATION_AGE_MS }
            ?: locationTracker.requestCurrentLocation()
        val sitesByRule = repository.sitesGroupedByRule()
        val ageMs = loc?.let { now - it.time } ?: Long.MAX_VALUE
        // 定位是否可信：不可信时不记失败、允许 WiFi 兜底
        val gpsUsable = CheckInValidator.isLocationReliable(
            lat = loc?.latitude,
            lon = loc?.longitude,
            accuracyMeters = loc?.accuracy ?: Float.MAX_VALUE,
            locationAgeMs = ageMs,
            maxAccuracyMeters = RELIABLE_ACCURACY_M,
            maxAgeMs = FAIL_LOCATION_MAX_AGE_MS
        )
        val ssid = locationTracker.currentWifiSsid()

        // 成功：时间 + 地点（含附加地点与 WiFi 兜底）均匹配
        var matchedRule: CheckInRule? = null
        var matchedSource = MatchSource.GPS
        for (rule in ruleList) {
            if (!CheckInValidator.isWithinTime(rule, now)) continue
            val sites = sitesByRule[rule.id].orEmpty()
            val m = CheckInValidator.matchRange(
                rule = rule,
                sites = sites,
                lat = loc?.latitude,
                lon = loc?.longitude,
                currentSsid = ssid,
                gpsUsable = gpsUsable
            )
            if (m.matched) {
                matchedRule = rule
                matchedSource = m.source
                break
            }
        }

        if (matchedRule != null) {
            // 同一规则同一打卡时段内只记录一次成功（按窗口实例开始时刻去重，规则主键匹配）。
            // 去重必须早于逆地理编码：时段内每 60 秒轮询与每次定位回调都会走到这里，
            // 已成功打卡后若仍先做逆地理编码，会产生大量随即被丢弃的网络请求，
            // 还会在互斥锁内多耗一次网络往返。
            // 去重按**打卡槽位**：未开启"需要下班卡"时窗口即槽位（同一时段只记一次）；
            // 开启后窗口对半分为上班卡/下班卡，各记一次，从而得到上下班两个时刻。
            val slotRange = CheckInValidator.punchSlotRangeFor(matchedRule, now)
            if (repository.lastSuccessForRuleInRange(
                    matchedRule.id, matchedRule.name, slotRange.from, slotRange.to
                ) != null
            ) {
                return null
            }
            val address = withContext(Dispatchers.IO) { reverseGeocode(loc) }
            return CheckInRecord(
                timestamp = now,
                latitude = loc?.latitude ?: 0.0,
                longitude = loc?.longitude ?: 0.0,
                address = address,
                ruleName = matchedRule.name,
                ruleId = matchedRule.id,
                status = CheckStatus.SUCCESS.name,
                matchSource = matchedSource.name,
                clockSkewMs = systemClockSkewMs(loc, now)
            ).also { repository.insertRecord(it) }
        }

        // 失败：仅当定位**新鲜且精度可信**但仍落在半径外时才记录（30 分钟冷却）。
        // 定位缺失、过期或精度过差（GPS 冷启动/室内漂移）时不记录失败：
        // 此时"地点外"是瞬时误判，等定位稳定后会补成功，
        // 避免同一窗口出现"先失败后成功"的噪音记录。
        if (!gpsUsable) return null
        val status = CheckStatus.OUT_OF_RANGE
        if (repository.lastRecordForRule(activeRule.id, activeRule.name, now - failCooldownMs) != null) {
            return null
        }
        val failAddress = withContext(Dispatchers.IO) { reverseGeocode(loc) }
        return CheckInRecord(
            timestamp = now,
            latitude = loc!!.latitude,
            longitude = loc.longitude,
            address = failAddress,
            ruleName = activeRule.name,
            ruleId = activeRule.id,
            status = status.name,
            matchSource = MatchSource.GPS.name,
            clockSkewMs = systemClockSkewMs(loc, now)
        ).also { repository.insertRecord(it) }
    }

    /**
     * 修正记录后重新判定状态：
     * 若记录修正后的时间与地点（含附加地点）落在某个**启用规则**的窗口和半径内，
     * 则把"时间外/地点外"等失败状态升级为成功，并写入对应规则名与规则主键；否则原样返回。
     */
    suspend fun reEvaluateStatus(record: CheckInRecord): CheckInRecord {
        val ruleList = repository.enabledRules()
        val sitesByRule = repository.sitesGroupedByRule()
        for (rule in ruleList) {
            if (!CheckInValidator.isWithinTime(rule, record.timestamp)) continue
            val m = CheckInValidator.matchRange(
                rule = rule,
                sites = sitesByRule[rule.id].orEmpty(),
                lat = record.latitude,
                lon = record.longitude,
                currentSsid = null,
                gpsUsable = true
            )
            if (m.matched) {
                return record.copy(
                    status = CheckStatus.SUCCESS.name,
                    ruleName = rule.name,
                    ruleId = rule.id
                )
            }
        }
        return record
    }

    /**
     * 时间段 + 地点校验，返回 (结果状态, 命中的规则, 命中地点名, 验证方式)。
     *
     * 归因原则：以"同一规则同时命中时间与地点"为成功标准；
     * 未成功时优先归因于更接近成功的维度——时间已落在某规则时段内
     * 则显示"地点外"（即使当前位置在另一条规则半径内，对当前应打卡的规则
     * 而言地点仍不符）；仅地点命中则"时间外"；两者都未命中才"时间外且地点外"。
     */
    private fun evaluate(
        ruleList: List<CheckInRule>,
        sitesByRule: Map<Long, List<CheckInSite>>,
        loc: Location?,
        currentSsid: String?,
        now: Long
    ): EvalResult {
        if (ruleList.isEmpty()) return EvalResult(CheckStatus.NO_RULE, null, null, MatchSource.GPS)

        val lat = loc?.latitude
        val lon = loc?.longitude
        val ageMs = loc?.let { now - it.time } ?: Long.MAX_VALUE
        val gpsUsable = CheckInValidator.isLocationReliable(
            lat = lat,
            lon = lon,
            accuracyMeters = loc?.accuracy ?: Float.MAX_VALUE,
            locationAgeMs = ageMs,
            maxAccuracyMeters = RELIABLE_ACCURACY_M,
            maxAgeMs = FAIL_LOCATION_MAX_AGE_MS
        )

        // 时间 + 地点同时命中的规则
        for (rule in ruleList) {
            if (!CheckInValidator.isWithinTime(rule, now)) continue
            val m = CheckInValidator.matchRange(
                rule, sitesByRule[rule.id].orEmpty(), lat, lon, currentSsid, gpsUsable
            )
            if (m.matched) {
                return EvalResult(CheckStatus.SUCCESS, rule, m.siteName, m.source)
            }
        }

        val timeOk = ruleList.any { CheckInValidator.isWithinTime(it, now) }
        val locOk = ruleList.any {
            CheckInValidator.matchRange(
                it, sitesByRule[it.id].orEmpty(), lat, lon, currentSsid, gpsUsable
            ).matched
        }

        val status = when {
            lat == null || lon == null -> CheckStatus.NO_LOCATION
            timeOk -> CheckStatus.OUT_OF_RANGE
            locOk -> CheckStatus.OUT_OF_TIME
            else -> CheckStatus.OUT_OF_TIME_AND_RANGE
        }
        return EvalResult(status, null, null, MatchSource.GPS)
    }

    /** 校验结果 */
    private data class EvalResult(
        val status: CheckStatus,
        val rule: CheckInRule?,
        val siteName: String?,
        val source: MatchSource
    )

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
     * 按经纬度逆地理编码（供"修正打卡记录"使用）。
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
