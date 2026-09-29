package com.example.checkin.util

import com.example.checkin.data.CheckInRecord
import com.example.checkin.data.CheckStatus
import com.example.checkin.data.RecordOrigin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 外勤改判策略的单测。
 *
 * 两条不变量：
 * 1. **只认"地点外"** —— 时间也不对的记录不能改判，否则外勤会变成绕过考勤时间的后门；
 * 2. **必须留痕** —— 改判后记录要带 editedAt，报表里显示"人工修正"，不能伪装成系统判定。
 */
class FieldWorkPolicyTest {

    private fun record(
        status: String = CheckStatus.OUT_OF_RANGE.name,
        note: String? = null,
        editedAt: Long? = null
    ) = CheckInRecord(
        timestamp = 1_700_000_000_000L,
        latitude = 31.2304,
        longitude = 121.4737,
        address = "上海市",
        ruleName = "早班",
        ruleId = 1L,
        status = status,
        note = note,
        origin = RecordOrigin.MANUAL.name,
        editedAt = editedAt
    )

    @Test
    fun `只有地点外可以改判为外勤`() {
        assertTrue(FieldWorkPolicy.canMarkAsFieldWork(CheckStatus.OUT_OF_RANGE.name))
        assertFalse(FieldWorkPolicy.canMarkAsFieldWork(CheckStatus.OUT_OF_TIME.name))
        assertFalse(
            FieldWorkPolicy.canMarkAsFieldWork(CheckStatus.OUT_OF_TIME_AND_RANGE.name)
        )
        assertFalse(FieldWorkPolicy.canMarkAsFieldWork(CheckStatus.NO_LOCATION.name))
        assertFalse(FieldWorkPolicy.canMarkAsFieldWork(CheckStatus.SUCCESS.name))
    }

    @Test
    fun `外勤原因必填且不能只有一两个字`() {
        assertFalse(FieldWorkPolicy.isReasonValid(null))
        assertFalse(FieldWorkPolicy.isReasonValid(""))
        assertFalse(FieldWorkPolicy.isReasonValid("  "))
        assertFalse(FieldWorkPolicy.isReasonValid("外"))
        assertTrue(FieldWorkPolicy.isReasonValid("客户现场"))
        // 前后空白不算长度
        assertTrue(FieldWorkPolicy.isReasonValid("  客户现场  "))
    }

    @Test
    fun `改判后状态为外勤且原因写进备注`() {
        val updated = FieldWorkPolicy.applyTo(record(), "客户现场驻场", now = 999L)!!
        assertEquals(CheckStatus.FIELD_WORK.name, updated.status)
        assertEquals("外勤：客户现场驻场", updated.note)
        assertEquals("客户现场驻场", FieldWorkPolicy.reasonOf(updated.note))
    }

    @Test
    fun `改判必须留痕且打卡时刻与地点不变`() {
        val original = record()
        val updated = FieldWorkPolicy.applyTo(original, "客户现场", now = 12345L)!!
        // 留痕：报表「数据来源」会据此显示"人工修正"
        assertEquals(12345L, updated.editedAt)
        assertTrue(updated.isEdited)
        // 只改判定结果，取证事实一律不动
        assertEquals(original.timestamp, updated.timestamp)
        assertEquals(original.latitude, updated.latitude, 0.0)
        assertEquals(original.longitude, updated.longitude, 0.0)
        assertEquals(original.address, updated.address)
        // 改的是判定而非时间，不应伪造"原始打卡时刻"
        assertNull(updated.originalTimestamp)
    }

    @Test
    fun `已修正过的记录保留首次修正时刻`() {
        val updated = FieldWorkPolicy.applyTo(
            record(editedAt = 555L), "客户现场", now = 999L
        )!!
        assertEquals(555L, updated.editedAt)
    }

    @Test
    fun `不可改判的记录返回空且不写入原因`() {
        assertNull(FieldWorkPolicy.applyTo(record(status = CheckStatus.OUT_OF_TIME.name), "客户现场", 1L))
        assertNull(FieldWorkPolicy.applyTo(record(status = CheckStatus.SUCCESS.name), "客户现场", 1L))
    }

    @Test
    fun `原因不合规时返回空`() {
        assertNull(FieldWorkPolicy.applyTo(record(), "", 1L))
        assertNull(FieldWorkPolicy.applyTo(record(), "外", 1L))
        assertNull(FieldWorkPolicy.applyTo(record(), null, 1L))
    }

    @Test
    fun `外勤备注识别与原因回读`() {
        assertTrue(FieldWorkPolicy.isFieldWorkNote("外勤：客户现场"))
        assertFalse(FieldWorkPolicy.isFieldWorkNote("请假"))
        assertFalse(FieldWorkPolicy.isFieldWorkNote(null))
        assertNull(FieldWorkPolicy.reasonOf("请假"))
    }

    @Test
    fun `改判后的记录算有效出勤不算正常出勤`() {
        val updated = FieldWorkPolicy.applyTo(record(), "客户现场", 1L)!!
        assertTrue(CheckStatus.isAttended(updated.status))
        assertFalse(CheckStatus.isNormal(updated.status))
        assertFalse(CheckStatus.isAbsent(updated.status))
    }
}
