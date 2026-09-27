package com.example.checkin.util

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 轮转班制（上N休M）纯逻辑单元测试。
 * 覆盖：周期取模、锚点之前的日期、序列化往返、脏数据容错、每周固定回退。
 */
class ShiftPatternTest {

    private val anchor = LocalDate.of(2026, 1, 5) // 周一

    @Test
    fun `上4休2 周期内前4天上班后2天休息`() {
        val p = ShiftPattern.rotation(4, 2, anchor)
        // 周期第 1~4 天上班
        assertTrue(p.isWorkDay(anchor, 127))
        assertTrue(p.isWorkDay(anchor.plusDays(1), 127))
        assertTrue(p.isWorkDay(anchor.plusDays(2), 127))
        assertTrue(p.isWorkDay(anchor.plusDays(3), 127))
        // 第 5~6 天休息
        assertFalse(p.isWorkDay(anchor.plusDays(4), 127))
        assertFalse(p.isWorkDay(anchor.plusDays(5), 127))
        // 第 7 天进入下一周期，重新上班
        assertTrue(p.isWorkDay(anchor.plusDays(6), 127))
    }

    @Test
    fun `锚点之前的日期也能正确落在周期内`() {
        val p = ShiftPattern.rotation(4, 2, anchor)
        // 锚点前一天是上一周期的最后一天（休息日）
        assertFalse(p.isWorkDay(anchor.minusDays(1), 127))
        // 锚点前 3 天是上一周期的上班日
        assertTrue(p.isWorkDay(anchor.minusDays(3), 127))
        // 锚点前 6 天恰好是上一周期第 1 天（上班）
        assertTrue(p.isWorkDay(anchor.minusDays(6), 127))
    }

    @Test
    fun `上二休二 与星期无关且周期为4天`() {
        val p = ShiftPattern.rotation(2, 2, anchor)
        assertEquals(4, p.cycleLength)
        assertTrue(p.isWorkDay(anchor, 0))                    // 掩码为 0 也不影响轮班判定
        assertFalse(p.isWorkDay(anchor.plusDays(2), 0))
        assertTrue(p.isWorkDay(anchor.plusDays(4), 0))
    }

    @Test
    fun `序列化与解析往返一致`() {
        val p = ShiftPattern.rotation(5, 2, anchor)
        val raw = p.serialize()
        assertEquals("R:5/2:2026-01-05", raw)
        val parsed = ShiftPattern.parse(raw)
        assertEquals(ShiftPattern.Kind.ROTATION, parsed.kind)
        assertEquals(5, parsed.workDays)
        assertEquals(2, parsed.restDays)
        assertEquals(anchor, parsed.anchor)
        assertTrue(parsed.isWorkDay(anchor.plusDays(1), 127))
    }

    @Test
    fun `每周固定模式使用星期位掩码`() {
        val p = ShiftPattern.parse("")
        assertEquals(ShiftPattern.Kind.WEEKLY, p.kind)
        // 2026-01-05 是周一 -> bit0
        assertTrue(p.isWorkDay(anchor, 0b0000001))
        assertFalse(p.isWorkDay(anchor, 0b0000010))
        assertEquals("", p.serialize())
    }

    @Test
    fun `脏数据回落为每周固定模式`() {
        assertEquals(ShiftPattern.Kind.WEEKLY, ShiftPattern.parse("R:abc/2:2026-01-05").kind)
        assertEquals(ShiftPattern.Kind.WEEKLY, ShiftPattern.parse("R:4:2026-01-05").kind)
        assertEquals(ShiftPattern.Kind.WEEKLY, ShiftPattern.parse("R:0/2:2026-01-05").kind)
        assertEquals(ShiftPattern.Kind.WEEKLY, ShiftPattern.parse("X:1/2:2026-01-05").kind)
        assertEquals(ShiftPattern.Kind.WEEKLY, ShiftPattern.parse(null).kind)
    }

    @Test
    fun `缺少锚点的轮班表达式退化为星期判定而不误判为永不生效`() {
        // 解析成功但锚点非法 -> 保留 ROTATION 类型，判定时回退到位掩码
        val p = ShiftPattern.parse("R:4/2:not-a-date")
        assertEquals(ShiftPattern.Kind.ROTATION, p.kind)
        assertTrue(p.isWorkDay(anchor, 0b0000001))   // 周一且掩码含周一 -> 上班
        assertFalse(p.isWorkDay(anchor, 0b0000010))  // 掩码不含周一 -> 不上班
    }

    @Test
    fun `上七休七 周期为14天`() {
        val p = ShiftPattern.rotation(7, 7, anchor)
        assertEquals(14, p.cycleLength)
        assertTrue(p.isWorkDay(anchor.plusDays(6), 127))
        assertFalse(p.isWorkDay(anchor.plusDays(7), 127))
        assertFalse(p.isWorkDay(anchor.plusDays(13), 127))
        assertTrue(p.isWorkDay(anchor.plusDays(14), 127))
    }

    @Test
    fun `每周固定模式的星期序号以周一为0`() {
        assertEquals(0, ShiftPattern.dayIndexOf(LocalDate.of(2026, 1, 5)))  // 周一
        assertEquals(6, ShiftPattern.dayIndexOf(LocalDate.of(2026, 1, 11))) // 周日
    }
}
