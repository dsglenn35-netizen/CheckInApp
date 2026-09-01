package com.example.checkin.util

import com.example.checkin.data.CheckInRule
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
        radiusMeters: Double = 100.0
    ) = CheckInRule(
        name = "测试规则",
        startHour = startHour, startMinute = startMinute,
        endHour = endHour, endMinute = endMinute,
        latitude = latitude, longitude = longitude,
        radiusMeters = radiusMeters,
        enabled = true,
        daysOfWeek = daysOfWeek
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
}
