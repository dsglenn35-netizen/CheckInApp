package com.example.checkin.util

import com.example.checkin.data.CheckInRecord
import com.example.checkin.data.CheckInRule
import com.example.checkin.data.CheckStatus
import java.io.StringReader
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.xml.sax.InputSource

/**
 * XLSX 工作表 XML 的结构测试。
 *
 * 手写 OOXML 最容易出的两类错，Excel 都只报"文件已损坏"、不指位置：
 * 1. 标签不闭合 / 属性没引号  -> 用 XML 解析器兜住；
 * 2. 节点顺序违反 schema（sheetViews、cols 必须排在 sheetData **之前**）-> 用下标断言兜住。
 */
class XlsxSheetXmlTest {

    private fun millis(y: Int, mo: Int, d: Int, h: Int, mi: Int) =
        LocalDateTime.of(y, mo, d, h, mi).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private fun rule() = CheckInRule(
        id = 1L, name = "白班",
        startHour = 9, startMinute = 0, endHour = 18, endMinute = 0,
        latitude = 30.0, longitude = 120.0, radiusMeters = 100.0,
        enabled = true, daysOfWeek = 127,
        requiredStartMinute = 9 * 60, requiredEndMinute = 18 * 60
    )

    private fun record(offsetMinutes: Int, edited: Boolean = false) = CheckInRecord(
        timestamp = millis(2026, 8, 31, 9, 0) + offsetMinutes * 60_000L,
        latitude = 30.0, longitude = 120.0,
        address = "浙江省杭州市", ruleName = "白班",
        status = CheckStatus.SUCCESS.name, ruleId = 1L,
        origin = if (edited) "EDITED" else "AUTO",
        editedAt = if (edited) 1234567890L else null,
        originalTimestamp = if (edited) millis(2026, 8, 31, 9, 30) else null
    )

    private fun sheets(
        records: List<CheckInRecord> = listOf(record(0), record(540)),
        employee: EmployeeInfo = EmployeeInfo()
    ) = ExportManager.allSheetsXmlForTest(
        records = records,
        rules = listOf(rule()),
        // 用 ALL：日期范围由测试数据本身决定，而不是"跑测试时的当前月份"，
        // 否则测试会随运行时间漂移（THIS_MONTH 只列当前月，8 月的数据会整天落空）
        scope = ExportScope.ALL,
        employee = employee
    )

    /** 用 JDK 的解析器验证 XML 格式良好（标签闭合、属性合法） */
    private fun assertWellFormed(xml: String, label: String) {
        try {
            DocumentBuilderFactory.newInstance()
                .apply { isNamespaceAware = true }
                .newDocumentBuilder()
                .parse(InputSource(StringReader(xml)))
        } catch (e: Exception) {
            fail(label + " 的 XML 格式不正确：" + e.message)
        }
    }

    @Test
    fun `三张工作表与样式表都是格式良好的 XML`() {
        sheets().forEach { (name, xml) -> assertWellFormed(xml, name) }
        assertWellFormed(ExportManager.stylesXmlForTest(), "styles.xml")
    }

    @Test
    fun `包级别的 ContentTypes 与 rels 都是格式良好的 XML`() {
        ExportManager.packageXmlForTest(3).forEach { (name, xml) -> assertWellFormed(xml, name) }
    }

    @Test
    fun `工作表节点顺序符合 schema：sheetViews 与 cols 都在 sheetData 之前`() {
        sheets().forEach { (name, xml) ->
            val views = xml.indexOf("<sheetViews>")
            val cols = xml.indexOf("<cols>")
            val data = xml.indexOf("<sheetData>")
            assertTrue("$name 缺少 sheetData", data >= 0)
            if (views >= 0) assertTrue("$name 的 sheetViews 必须在 sheetData 之前", views < data)
            if (cols >= 0) assertTrue("$name 的 cols 必须在 sheetData 之前", cols < data)
        }
    }

    @Test
    fun `表头使用了加粗样式`() {
        val attendance = sheets().first { it.first == "考勤日报" }.second
        // cellXfs 下标 1 是加粗；表头行必须有 s="1"
        assertTrue(attendance.contains("s=\"1\""))
    }

    @Test
    fun `考勤日报包含标题块与人员信息`() {
        val attendance = sheets(employee = EmployeeInfo("张三", "00123", "技术部"))
            .first { it.first == "考勤日报" }.second
        assertTrue(attendance.contains("考勤表"))
        assertTrue(attendance.contains("姓名：张三"))
        assertTrue(attendance.contains("工号：00123"))
        assertTrue(attendance.contains("部门：技术部"))
        assertTrue("应留出签字栏", attendance.contains("员工签字"))
    }

    @Test
    fun `人员信息为空时仍留出可手写的下划线`() {
        val attendance = sheets(employee = EmployeeInfo()).first { it.first == "考勤日报" }.second
        assertTrue(attendance.contains("姓名："))
        assertTrue(attendance.contains("工号："))
        assertTrue(attendance.contains("部门："))
    }

    @Test
    fun `考勤日报含数据来源列并标出人工修正`() {
        val attendance = sheets(records = listOf(record(0), record(540, edited = true)))
            .first { it.first == "考勤日报" }.second
        assertTrue(attendance.contains("数据来源"))
        assertTrue("被修正过的那天应标注", attendance.contains("含人工修正"))
    }

    @Test
    fun `没有任何记录时也能正常生成表格`() {
        val list = sheets(records = emptyList())
        list.forEach { (name, xml) -> assertWellFormed(xml, name) }
    }

    @Test
    fun `汇总统计标出人工修正条数`() {
        val summary = sheets(records = listOf(record(0), record(540, edited = true)))
            .first { it.first == "汇总统计" }.second
        assertTrue(summary.contains("其中人工修正"))
    }
}
