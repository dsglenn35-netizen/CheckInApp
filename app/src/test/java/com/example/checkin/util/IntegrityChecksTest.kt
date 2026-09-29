package com.example.checkin.util

import com.example.checkin.data.CheckInRecord
import com.example.checkin.data.CheckStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 打卡记录完整性留痕的单测。
 *
 * 口径：异常信号**只留痕、不改变打卡结果**，因此这里全部是"判定与文案"的断言。
 * 时钟阈值由 [CheckInValidator.CLOCK_SKEW_WARN_MS] 提供（5 分钟），此处显式钉住边界。
 */
class IntegrityChecksTest {

    private fun record(
        mock: Boolean = false,
        skewMs: Long? = null
    ) = CheckInRecord(
        timestamp = 1_700_000_000_000L,
        latitude = 31.2304,
        longitude = 121.4737,
        address = "上海市",
        ruleName = "早班",
        status = CheckStatus.SUCCESS.name,
        clockSkewMs = skewMs,
        mockLocation = mock
    )

    @Test
    fun `正常记录没有任何异常留痕`() {
        val r = record()
        assertFalse(IntegrityChecks.hasAnomaly(r))
        assertEquals("", IntegrityChecks.anomalyLabel(r))
    }

    @Test
    fun `模拟位置被判为异常并写明原因`() {
        val r = record(mock = true)
        assertTrue(IntegrityChecks.hasAnomaly(r))
        assertTrue(IntegrityChecks.anomalyLabel(r).contains("模拟位置"))
    }

    @Test
    fun `时钟偏差超过阈值判为异常`() {
        val r = record(skewMs = CheckInValidator.CLOCK_SKEW_WARN_MS + 1)
        assertTrue(IntegrityChecks.hasAnomaly(r))
        assertTrue(IntegrityChecks.anomalyLabel(r).contains("时钟"))
    }

    @Test
    fun `时钟偏差恰好等于阈值不算异常`() {
        // isClockSkewed 用的是"严格大于"，边界值属于可信范围
        val r = record(skewMs = CheckInValidator.CLOCK_SKEW_WARN_MS)
        assertFalse(IntegrityChecks.hasAnomaly(r))
        assertEquals("", IntegrityChecks.anomalyLabel(r))
    }

    @Test
    fun `未取得授时的时间偏差不算异常`() {
        assertFalse(IntegrityChecks.hasAnomaly(record(skewMs = null)))
    }

    @Test
    fun `两类信号同时出现时文案都写上`() {
        val r = record(mock = true, skewMs = CheckInValidator.CLOCK_SKEW_WARN_MS * 2)
        val label = IntegrityChecks.anomalyLabel(r)
        assertTrue(label.contains("模拟位置"))
        assertTrue(label.contains("时钟"))
        // 同一列里用分号分隔，避免两个原因糊成一团
        assertTrue(label.contains("；"))
    }

    @Test
    fun `报表文案统一带异常前缀`() {
        // 界面与导出表共用同一个文案来源，正常时必须为空串而不是"正常"之类的措辞，
        // 否则整列会被"正常"刷满，反而看不出哪条有问题
        assertEquals("", IntegrityChecks.anomalyLabel(record()))
        assertTrue(IntegrityChecks.anomalyLabel(record(mock = true)).startsWith("异常："))
    }
}
