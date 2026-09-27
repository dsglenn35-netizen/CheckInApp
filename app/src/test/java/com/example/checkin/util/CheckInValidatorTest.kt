package com.example.checkin.util

import com.example.checkin.data.CheckInRule
import com.example.checkin.data.CheckInSite
import com.example.checkin.data.MatchSource
import com.example.checkin.data.TimeEntry
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CheckInValidator 纯逻辑单元测试。
 * 覆盖最容易出错的边界：结束整点不含、跨午夜窗口、星期位掩码、
 * 窗口去重基准、下一次规则边界（闹钟调度）、Haversine 距离。
 */
class CheckInValidatorTest {

    private fun rule(
        startHour: Int = 9,
        startMinute: Int = 0,
        endHour: Int = 18,
        endMinute: Int = 0,
        daysOfWeek: Int = 127,
        latitude: Double = 30.0,
        longitude: Double = 120.0,
        radiusMeters: Double = 100.0,
        wifiSsid: String? = null
    ) = CheckInRule(
        name = "测试规则",
        startHour = startHour, startMinute = startMinute,
        endHour = endHour, endMinute = endMinute,
        latitude = latitude, longitude = longitude,
        radiusMeters = radiusMeters,
        enabled = true,
        daysOfWeek = daysOfWeek,
        wifiSsid = wifiSsid
    )

    /** 构造 [y-mo-d h:mi:s]（系统时区）的毫秒时间戳，与内部 Calendar 默认时区一致 */
    private fun millis(y: Int, mo: Int, d: Int, h: Int = 0, mi: Int = 0, s: Int = 0): Long =
        LocalDateTime.of(y, mo, d, h, mi, s).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    // ---------- 时间窗口 ----------

    @Test
    fun `普通窗口包含开始整点不含结束整点`() {
        val r = rule(9, 0, 18, 0)
        assertTrue(CheckInValidator.isWithinTime(r, millis(2026, 8, 31, 9, 0, 0)))     // 开始整点：包含
        assertTrue(CheckInValidator.isWithinTime(r, millis(2026, 8, 31, 17, 59, 59)))  // 结束前 1 秒：包含
        assertFalse(CheckInValidator.isWithinTime(r, millis(2026, 8, 31, 18, 0, 0)))   // 结束整点：不含
        assertFalse(CheckInValidator.isWithinTime(r, millis(2026, 8, 31, 8, 59, 59)))  // 开始前：不含
    }

    @Test
    fun `跨午夜窗口`() {
        val r = rule(22, 0, 6, 0)
        assertTrue(CheckInValidator.isWithinTime(r, millis(2026, 8, 31, 22, 0, 0)))
        assertTrue(CheckInValidator.isWithinTime(r, millis(2026, 8, 31, 23, 59, 59)))
        assertTrue(CheckInValidator.isWithinTime(r, millis(2026, 9, 1, 0, 0, 0)))      // 次日 00:00
        assertTrue(CheckInValidator.isWithinTime(r, millis(2026, 9, 1, 5, 59, 59)))   // 结束前 1 秒
        assertFalse(CheckInValidator.isWithinTime(r, millis(2026, 9, 1, 6, 0, 0)))    // 结束整点
        assertFalse(CheckInValidator.isWithinTime(r, millis(2026, 8, 31, 21, 59, 59))) // 开始前
    }

    // ---------- 生效星期（2026-08-31 是周一，2026-09-06 是周日） ----------

    @Test
    fun `生效星期位掩码_仅周一生效`() {
        // dayIndex：周一=0，需要 bit0
        val r = rule(9, 0, 18, 0, daysOfWeek = 1 shl 0)
        assertTrue(CheckInValidator.isWithinTime(r, millis(2026, 8, 31, 10, 0, 0)))
    }

    @Test
    fun `生效星期位掩码_非生效日不匹配`() {
        // 周一当天，规则只在周日(bit6)生效
        val r = rule(9, 0, 18, 0, daysOfWeek = 1 shl 6)
        assertFalse(CheckInValidator.isWithinTime(r, millis(2026, 8, 31, 10, 0, 0)))
    }

    @Test
    fun `工作日位掩码_周一命中周日不命中`() {
        val weekdays = (0..4).fold(0) { acc, i -> acc or (1 shl i) } // 周一~周五 = 0b11111
        val r = rule(9, 0, 18, 0, daysOfWeek = weekdays)
        assertTrue(CheckInValidator.isWithinTime(r, millis(2026, 8, 31, 10, 0, 0)))  // 周一
        assertFalse(CheckInValidator.isWithinTime(r, millis(2026, 9, 6, 10, 0, 0)))  // 周日
    }

    // ---------- 窗口去重基准（windowStartMillis） ----------

    @Test
    fun `普通窗口去重基准为当天开始时刻`() {
        val r = rule(9, 0, 18, 0)
        assertEquals(
            millis(2026, 8, 31, 9, 0, 0),
            CheckInValidator.windowStartMillis(r, millis(2026, 8, 31, 10, 30, 0))
        )
    }

    @Test
    fun `跨午夜窗口_结束段基准为当天开始`() {
        val r = rule(22, 0, 6, 0)
        // 23:00 处于"结束段"（22:00 之后）→ 窗口开始于当天 22:00
        assertEquals(
            millis(2026, 8, 31, 22, 0, 0),
            CheckInValidator.windowStartMillis(r, millis(2026, 8, 31, 23, 0, 0))
        )
        // 次日 01:00 处于"开始段"（00:00~06:00）→ 窗口开始于"昨天" 22:00
        assertEquals(
            millis(2026, 8, 31, 22, 0, 0),
            CheckInValidator.windowStartMillis(r, millis(2026, 9, 1, 1, 0, 0))
        )
    }

    // ---------- 下一次规则边界（nextBoundaryMillis，闹钟调度用） ----------

    @Test
    fun `无启用规则返回空`() {
        assertNull(CheckInValidator.nextBoundaryMillis(emptyList(), millis(2026, 8, 31, 10, 0, 0)))
    }

    @Test
    fun `普通窗口下一次边界为开始或结束`() {
        val r = rule(9, 0, 18, 0)
        assertEquals(
            millis(2026, 8, 31, 9, 0, 0),
            CheckInValidator.nextBoundaryMillis(listOf(r), millis(2026, 8, 31, 7, 0, 0))
        )
        assertEquals(
            millis(2026, 8, 31, 18, 0, 0),
            CheckInValidator.nextBoundaryMillis(listOf(r), millis(2026, 8, 31, 10, 0, 0))
        )
    }

    @Test
    fun `仅周一生效的规则_从周二找到下周一`() {
        val r = rule(9, 0, 18, 0, daysOfWeek = 1 shl 0) // 仅周一
        // 2026-09-01 是周二 → 下一个周一是 2026-09-07（8 天扫描内）
        assertEquals(
            millis(2026, 9, 7, 9, 0, 0),
            CheckInValidator.nextBoundaryMillis(listOf(r), millis(2026, 9, 1, 0, 0, 0))
        )
    }

    @Test
    fun `跨午夜窗口的下一次边界`() {
        val r = rule(22, 0, 6, 0)
        // 20:00 → 当天 22:00 进入时段
        assertEquals(
            millis(2026, 8, 31, 22, 0, 0),
            CheckInValidator.nextBoundaryMillis(listOf(r), millis(2026, 8, 31, 20, 0, 0))
        )
        // 23:00 → 次日 06:00 离开时段
        assertEquals(
            millis(2026, 9, 1, 6, 0, 0),
            CheckInValidator.nextBoundaryMillis(listOf(r), millis(2026, 8, 31, 23, 0, 0))
        )
    }

    // ---------- 距离与半径 ----------

    @Test
    fun `同一点距离为零`() {
        assertEquals(0.0, CheckInValidator.distanceMeters(30.0, 120.0, 30.0, 120.0), 0.0001)
    }

    @Test
    fun `纬度差距离约等于理论值`() {
        // 1 度纬度 ≈ π * 6371000 / 180 ≈ 111194.9 m，0.001° ≈ 111.2 m
        val d = CheckInValidator.distanceMeters(0.0, 0.0, 0.001, 0.0)
        assertTrue("实际距离 $d 应接近 111.2m", abs(d - 111.2) < 1.0)
    }

    @Test
    fun `距离计算对称`() {
        val a = CheckInValidator.distanceMeters(30.0, 120.0, 31.0, 121.0)
        val b = CheckInValidator.distanceMeters(31.0, 121.0, 30.0, 120.0)
        assertEquals(a, b, 1e-6)
    }

    @Test
    fun `半径判定`() {
        val r = rule(9, 0, 18, 0, latitude = 30.0, longitude = 120.0, radiusMeters = 100.0)
        // 纬度差 0.001° ≈ 111m > 100m → 范围外
        assertFalse(CheckInValidator.isWithinRange(r, 30.001, 120.0))
        // 半径放大到 200m → 范围内
        val big = rule(9, 0, 18, 0, latitude = 30.0, longitude = 120.0, radiusMeters = 200.0)
        assertTrue(CheckInValidator.isWithinRange(big, 30.001, 120.0))
    }

    // ---------- 多打卡点（附加地点） ----------

    private fun site(
        name: String = "南门",
        latitude: Double = 30.0,
        longitude: Double = 120.0,
        radiusMeters: Double = 100.0,
        wifiSsid: String? = null,
        ruleId: Long = 1L
    ) = CheckInSite(
        ruleId = ruleId, name = name,
        latitude = latitude, longitude = longitude,
        radiusMeters = radiusMeters, wifiSsid = wifiSsid
    )

    @Test
    fun `主地点未命中但附加地点命中时整体命中`() {
        val r = rule(9, 0, 18, 0, latitude = 30.0, longitude = 120.0, radiusMeters = 100.0)
        // 距离主地点约 1.1km（0.01° ≈ 1112m），落在附加地点半径内
        val s = site(latitude = 30.01, longitude = 120.0, radiusMeters = 200.0)
        val m = CheckInValidator.matchRange(r, listOf(s), 30.0105, 120.0, null, gpsUsable = true)
        assertTrue(m.matched)
        assertEquals("南门", m.siteName)
        assertEquals(MatchSource.GPS, m.source)
    }

    @Test
    fun `所有地点都不在范围内则不命中并给出最近距离`() {
        val r = rule(9, 0, 18, 0, latitude = 30.0, longitude = 120.0, radiusMeters = 100.0)
        val s = site(latitude = 30.05, longitude = 120.0, radiusMeters = 100.0)
        // 距主地点 0.001° ≈ 111m > 100m；距附加地点更远
        val m = CheckInValidator.matchRange(r, listOf(s), 30.001, 120.0, null, gpsUsable = true)
        assertFalse(m.matched)
        assertTrue("最近距离应约 111m，实际 ${m.distanceMeters}", abs(m.distanceMeters!! - 111.2) < 2.0)
    }

    @Test
    fun `定位不可靠时按已登记WiFi兜底命中`() {
        val r = rule(9, 0, 18, 0, latitude = 30.0, longitude = 120.0, radiusMeters = 100.0)
        // 坐标完全在范围外（1km 外），但连上了登记的 WiFi
        val s = site(latitude = 30.0, longitude = 120.0, radiusMeters = 100.0, wifiSsid = "Office-5G")
        val m = CheckInValidator.matchRange(
            r, listOf(s), 30.01, 120.0, currentSsid = "Office-5G", gpsUsable = false
        )
        assertTrue(m.matched)
        assertEquals(MatchSource.WIFI, m.source)
    }

    @Test
    fun `定位可信且坐标命中时以GPS为准而非WiFi`() {
        val r = rule(9, 0, 18, 0, latitude = 30.0, longitude = 120.0, radiusMeters = 100.0)
        val m = CheckInValidator.matchRange(
            r, emptyList(), 30.0, 120.0, currentSsid = "Office-5G", gpsUsable = true
        )
        assertTrue(m.matched)
        assertEquals(MatchSource.GPS, m.source)
    }

    @Test
    fun `定位不可信且坐标未命中时WiFi兜底`() {
        val r = rule(9, 0, 18, 0, latitude = 30.0, longitude = 120.0, radiusMeters = 100.0, wifiSsid = "Office")
        val m = CheckInValidator.matchRange(
            r, emptyList(), 30.01, 120.0, currentSsid = "office", gpsUsable = false
        )
        assertTrue(m.matched)
        assertEquals(MatchSource.WIFI, m.source)
    }

    @Test
    fun `定位可信时GPS未命中不使用WiFi兜底`() {
        // 安全边界：GPS 是强证据，SSID 可伪造且易重名，
        // 可信定位下不允许 WiFi 覆盖"未命中"的判定，否则等于把判定权交给可伪造的信号。
        val r = rule(9, 0, 18, 0, latitude = 30.0, longitude = 120.0, radiusMeters = 100.0, wifiSsid = "Office")
        val m = CheckInValidator.matchRange(
            r, emptyList(), 30.01, 120.0, currentSsid = "Office", gpsUsable = true
        )
        assertFalse("可信定位未命中时不应由 WiFi 兜底", m.matched)
        assertEquals(MatchSource.GPS, m.source)
    }

    @Test
    fun `WiFi未登记时不兜底`() {
        val r = rule(9, 0, 18, 0, latitude = 30.0, longitude = 120.0, radiusMeters = 100.0)
        val m = CheckInValidator.matchRange(
            r, emptyList(), 30.01, 120.0, currentSsid = "Other-WiFi", gpsUsable = false
        )
        assertFalse(m.matched)
    }

    @Test
    fun `WiFi匹配忽略大小写并按逗号分隔`() {
        assertTrue(CheckInValidator.matchesSsid("A-1, B-2 ,c-3", "b-2"))
        assertTrue(CheckInValidator.matchesSsid("A-1", "a-1"))
        assertFalse(CheckInValidator.matchesSsid("A-1", "A-2"))
        assertFalse(CheckInValidator.matchesSsid(null, "A-1"))
        assertFalse(CheckInValidator.matchesSsid("A-1", null))
        assertFalse(CheckInValidator.matchesSsid("A-1", "  "))
    }

    @Test
    fun `定位可信性判定综合精度与新鲜度`() {
        // 新鲜且精度好
        assertTrue(
            CheckInValidator.isLocationReliable(30.0, 120.0, 20f, 30_000L)
        )
        // 精度过差（200m 上限）
        assertFalse(
            CheckInValidator.isLocationReliable(30.0, 120.0, 500f, 30_000L)
        )
        // 位置过期（超过 2 分钟）
        assertFalse(
            CheckInValidator.isLocationReliable(30.0, 120.0, 20f, 3 * 60_000L)
        )
        // 无坐标（0,0 占位）
        assertFalse(CheckInValidator.isLocationReliable(0.0, 0.0, 10f, 1_000L))
        assertFalse(CheckInValidator.isLocationReliable(null, null, 10f, 1_000L))
    }

    @Test
    fun `时钟偏差阈值判定`() {
        assertFalse(CheckInValidator.isClockSkewed(null))
        assertFalse(CheckInValidator.isClockSkewed(60_000L))       // 1 分钟：正常
        assertFalse(CheckInValidator.isClockSkewed(5 * 60_000L))   // 恰好 5 分钟：不报警
        assertTrue(CheckInValidator.isClockSkewed(6 * 60_000L))    // 6 分钟：异常
        assertTrue(CheckInValidator.isClockSkewed(-30 * 60_000L))  // 慢 30 分钟：异常
    }

    // ---------- 轮转班制在时间判定中的生效 ----------

    @Test
    fun `轮班制休息日不进入打卡时段`() {
        // 上2休2，锚点 2026-08-31（周一）
        val r = rule(9, 0, 18, 0).copy(
            shiftPattern = ShiftPattern.rotation(2, 2, java.time.LocalDate.of(2026, 8, 31)).serialize()
        )
        assertTrue(CheckInValidator.isWithinTime(r, millis(2026, 8, 31, 10, 0, 0)))   // 周期第1天：上班
        assertTrue(CheckInValidator.isWithinTime(r, millis(2026, 9, 1, 10, 0, 0)))    // 第2天：上班
        assertFalse(CheckInValidator.isWithinTime(r, millis(2026, 9, 2, 10, 0, 0)))   // 第3天：休息
        assertFalse(CheckInValidator.isWithinTime(r, millis(2026, 9, 3, 10, 0, 0)))   // 第4天：休息
        assertTrue(CheckInValidator.isWithinTime(r, millis(2026, 9, 4, 10, 0, 0)))    // 第5天：新周期上班
    }

    @Test
    fun `跨午夜窗口的凌晨段按前一天判定生效星期`() {
        // 2026-08-31 是周一，2026-09-01 是周二
        // 只在周一生效的夜班 22:00-06:00：周一 23:00 与周二 02:00 都属于"周一这一班"
        val mondayOnly = rule(22, 0, 6, 0, daysOfWeek = 1 shl 0)
        assertTrue(CheckInValidator.isWithinTime(mondayOnly, millis(2026, 8, 31, 23, 0, 0)))
        assertTrue(CheckInValidator.isWithinTime(mondayOnly, millis(2026, 9, 1, 2, 0, 0)))
        assertFalse(CheckInValidator.isWithinTime(mondayOnly, millis(2026, 9, 1, 6, 0, 0)))   // 结束整点
        // 周二 23:00 已经是"周二这一班"，周一规则不生效
        assertFalse(CheckInValidator.isWithinTime(mondayOnly, millis(2026, 9, 1, 23, 0, 0)))
        // 周三凌晨 02:00 属于"周二这一班"，周一规则同样不生效
        assertFalse(CheckInValidator.isWithinTime(mondayOnly, millis(2026, 9, 2, 2, 0, 0)))
    }

    @Test
    fun `普通窗口的生效星期不受本次修改影响`() {
        val mondayOnly = rule(9, 0, 18, 0, daysOfWeek = 1 shl 0)
        assertTrue(CheckInValidator.isWithinTime(mondayOnly, millis(2026, 8, 31, 10, 0, 0)))
        assertFalse(CheckInValidator.isWithinTime(mondayOnly, millis(2026, 9, 1, 10, 0, 0)))
    }

    @Test
    fun `轮班制忽略星期掩码`() {
        // 掩码为 0（按星期永不生效），但轮班制应正常判定
        val r = rule(9, 0, 18, 0, daysOfWeek = 0).copy(
            shiftPattern = ShiftPattern.rotation(1, 1, java.time.LocalDate.of(2026, 8, 31)).serialize()
        )
        assertTrue(CheckInValidator.isWithinTime(r, millis(2026, 8, 31, 10, 0, 0)))
        assertFalse(CheckInValidator.isWithinTime(r, millis(2026, 9, 1, 10, 0, 0)))
    }

    // ---------- 时间段请假 / 放假（"不用打卡"的时段） ----------

    private fun entry(type: String, startMinute: Int, endMinute: Int) =
        TimeEntry(
            date = "2026-08-31", type = type,
            startMinute = startMinute, endMinute = endMinute
        )

    @Test
    fun `请假时段内不打卡_结束整点不计入`() {
        val entries = listOf(entry(TimeEntry.TYPE_LEAVE, 9 * 60, 12 * 60))
        assertTrue(CheckInValidator.isWithinTimeOff(entries, 9 * 60))          // 开始整点：在内
        assertTrue(CheckInValidator.isWithinTimeOff(entries, 11 * 60 + 59))    // 结束前 1 分钟：在内
        assertFalse(CheckInValidator.isWithinTimeOff(entries, 12 * 60))        // 结束整点：不含
        assertFalse(CheckInValidator.isWithinTimeOff(entries, 8 * 60 + 59))    // 开始前：不含
    }

    @Test
    fun `放假期段与请假同样抑制打卡`() {
        val entries = listOf(entry(TimeEntry.TYPE_HOLIDAY, 13 * 60, 18 * 60))
        assertTrue(CheckInValidator.isWithinTimeOff(entries, 14 * 60))
        assertFalse(CheckInValidator.isWithinTimeOff(entries, 18 * 60))        // 结束整点：不含
    }

    @Test
    fun `加班时段不属于不打卡时段`() {
        // 加班时段仍应正常打卡，不能被当成"不用打卡"
        val entries = listOf(entry(TimeEntry.TYPE_OVERTIME, 18 * 60, 21 * 60))
        assertFalse(CheckInValidator.isWithinTimeOff(entries, 19 * 60))
    }

    @Test
    fun `多个时段任一命中即抑制`() {
        val entries = listOf(
            entry(TimeEntry.TYPE_LEAVE, 9 * 60, 12 * 60),
            entry(TimeEntry.TYPE_HOLIDAY, 14 * 60, 18 * 60)
        )
        assertFalse(CheckInValidator.isWithinTimeOff(entries, 13 * 60))        // 两段之间的空档
        assertTrue(CheckInValidator.isWithinTimeOff(entries, 15 * 60))
    }

    @Test
    fun `无任何时段标注时不抑制`() {
        assertFalse(CheckInValidator.isWithinTimeOff(emptyList(), 10 * 60))
    }
}
