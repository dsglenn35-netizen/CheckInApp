package com.example.checkin.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.example.checkin.data.CheckInRecord
import com.example.checkin.data.CheckInRule
import com.example.checkin.data.CheckStatus
import com.example.checkin.data.LeaveDay
import com.example.checkin.data.TimeEntry
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.YearMonth
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 导出范围 */
enum class ExportScope(val label: String) {
    THIS_MONTH("本月"),
    ALL("全部")
}

/** 导出格式 */
enum class ExportFormat {
    CSV,
    XLSX
}

/** 备份数据：规则 + 记录 + 请假 + 时间段标注 */
data class BackupData(
    val rules: List<CheckInRule>,
    val records: List<CheckInRecord>,
    val leaveDays: List<LeaveDay> = emptyList(),
    val timeEntries: List<TimeEntry> = emptyList()
)

/**
 * 导出/备份工具：
 * - CSV：UTF-8 带 BOM，Excel/WPS 直接打开中文不乱码（完整记录列表）
 * - XLSX：无第三方依赖的最小 Excel 文件，Sheet1「打卡记录」+ Sheet2「汇总统计」
 *   （出勤/请假/加班/按时率/各规则统计/按日出勤明细）
 * - JSON：完整数据备份（规则 + 记录 + 请假 + 时间段标注），可恢复
 */
object ExportManager {

    // ---------- 通用 ----------

    private fun exportDir(context: Context): File {
        val dir = context.getExternalFilesDir(null)?.let { File(it, "exports") }
            ?: File(context.filesDir, "exports")
        dir.mkdirs()
        return dir
    }

    private fun stamp(): String = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

    /** 通过系统分享面板导出（MIME 按扩展名推断） */
    fun share(context: Context, file: File) {
        val mime = when (file.extension.lowercase()) {
            "csv" -> "text/csv"
            "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            "json" -> "application/json"
            else -> "*/*"
        }
        val uri: Uri = FileProvider.getUriForFile(
            context, "${context.packageName}.fileprovider", file
        )
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(send, "分享打卡数据").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(chooser)
    }

    // ---------- CSV ----------

    /**
     * 生成 CSV 文件并返回。
     * 列：序号、打卡时间、打卡状态、命中规则、纬度、经度、地址、备注。
     */
    suspend fun exportCsv(
        context: Context,
        records: List<CheckInRecord>,
        scopeLabel: String
    ): File = withContext(Dispatchers.IO) {
        val file = File(exportDir(context), "打卡记录_${scopeLabel}_${stamp()}.csv")

        val sb = StringBuilder()
        sb.append('\uFEFF') // BOM：让 Excel 正确识别 UTF-8 中文
        sb.append("序号,打卡时间,打卡状态,命中规则,纬度,经度,地址,备注\n")
        records.forEachIndexed { index, r ->
            val hasCoords = r.latitude != 0.0 || r.longitude != 0.0
            sb.append(index + 1).append(',')
                .append(csvField(formatDateTime(r.timestamp))).append(',')
                .append(csvField(statusLabel(r.status))).append(',')
                .append(csvField(r.ruleName)).append(',')
                .append(csvField(if (hasCoords) "%.6f".format(Locale.US, r.latitude) else "")).append(',')
                .append(csvField(if (hasCoords) "%.6f".format(Locale.US, r.longitude) else "")).append(',')
                .append(csvField(r.address)).append(',')
                .append(csvField(r.note)).append('\n')
        }

        FileOutputStream(file).use { out ->
            out.write(sb.toString().toByteArray(Charsets.UTF_8))
        }
        file
    }

    /** CSV 字段转义：含逗号/引号/换行时用双引号包裹，内部引号翻倍 */
    private fun csvField(value: Any?): String {
        val s = value?.toString() ?: ""
        return if (s.contains(',') || s.contains('"') || s.contains('\n') || s.contains('\r')) {
            "\"" + s.replace("\"", "\"\"") + "\""
        } else s
    }

    // ---------- XLSX（无依赖的最小实现） ----------

    /**
     * 生成 .xlsx 文件：
     * Sheet1「打卡记录」（全部记录）+ Sheet2「汇总统计」
     * （出勤/请假/加班/按时率/各规则统计/按日出勤明细）。
     */
    suspend fun exportXlsx(
        context: Context,
        records: List<CheckInRecord>,
        rules: List<CheckInRule>,
        leaveDays: List<LeaveDay>,
        timeEntries: List<TimeEntry>,
        scope: ExportScope
    ): File = withContext(Dispatchers.IO) {
        val file = File(exportDir(context), "打卡记录_${scope.label}_${stamp()}.xlsx")
        val sheets = listOf(
            "打卡记录" to recordsSheetXml(records),
            "汇总统计" to summarySheetXml(records, rules, leaveDays, timeEntries, scope)
        )

        ZipOutputStream(FileOutputStream(file)).use { zip ->
            zip.writeEntry("[Content_Types].xml", contentTypesXml(sheets.size))
            zip.writeEntry("_rels/.rels", rootRelsXml())
            zip.writeEntry("xl/workbook.xml", workbookXml(sheets.map { it.first }))
            zip.writeEntry("xl/_rels/workbook.xml.rels", workbookRelsXml(sheets.size))
            sheets.forEachIndexed { i, (_, xml) ->
                zip.writeEntry("xl/worksheets/sheet${i + 1}.xml", xml)
            }
        }
        file
    }

    private fun ZipOutputStream.writeEntry(name: String, content: String) {
        putNextEntry(ZipEntry(name))
        write(content.toByteArray(Charsets.UTF_8))
        closeEntry()
    }

    private fun recordsSheetXml(records: List<CheckInRecord>): String {
        val sb = StringBuilder(sheetXmlHeader())
        sb.append("<sheetData>")
        val headers = listOf("序号", "打卡时间", "打卡状态", "命中规则", "纬度", "经度", "地址", "备注")
        sb.append(rowXml(1, headers.map { cellXml(it, isString = true) }))
        records.forEachIndexed { index, r ->
            val hasCoords = r.latitude != 0.0 || r.longitude != 0.0
            val values = listOf(
                cellXml((index + 1).toString(), isString = false),
                cellXml(formatDateTime(r.timestamp), isString = true),
                cellXml(statusLabel(r.status), isString = true),
                cellXml(r.ruleName ?: "", isString = true),
                cellXml(if (hasCoords) "%.6f".format(Locale.US, r.latitude) else "", isString = true),
                cellXml(if (hasCoords) "%.6f".format(Locale.US, r.longitude) else "", isString = true),
                cellXml(r.address ?: "", isString = true),
                cellXml(r.note ?: "", isString = true)
            )
            sb.append(rowXml(index + 2, values))
        }
        sb.append("</sheetData></worksheet>")
        return sb.toString()
    }

    /** 汇总统计 Sheet：考勤指标 + 各规则统计 + 按日出勤明细 */
    private fun summarySheetXml(
        records: List<CheckInRecord>,
        rules: List<CheckInRule>,
        leaveDays: List<LeaveDay>,
        timeEntries: List<TimeEntry>,
        scope: ExportScope
    ): String {
        val success = records.count { it.status == CheckStatus.SUCCESS.name }
        val fail = records.size - success
        val rate = if (records.isEmpty()) 0.0 else success * 100.0 / records.size

        // 出勤天数：有成功打卡记录的天数
        val attendanceDays = records
            .filter { it.status == CheckStatus.SUCCESS.name }
            .map { it.timestamp.toLocalDate() }
            .distinct()
            .size

        // 请假天数：全天请假 + 有请假时段标注的日期（去重）
        val leaveDateKeys = (leaveDays.map { it.date } +
            timeEntries.filter { it.type == TimeEntry.TYPE_LEAVE }.map { it.date }).toSet()

        // 加班
        val overtimeEntries = timeEntries.filter { it.type == TimeEntry.TYPE_OVERTIME }
        val overtimeMinutes = overtimeEntries.sumOf { (it.endMinute - it.startMinute).coerceAtLeast(0) }

        // 各规则成功统计
        val ruleStats = rules.mapNotNull { rule ->
            val c = records.count {
                it.status == CheckStatus.SUCCESS.name && it.ruleName == rule.name
            }
            if (c > 0) rule.name to c else null
        }

        val rows = mutableListOf<Pair<String, String>>()
        rows += "导出范围" to scope.label
        rows += "导出时间" to formatDateTime(System.currentTimeMillis())
        rows += "记录总数" to records.size.toString()
        rows += "成功次数" to success.toString()
        rows += "失败次数" to fail.toString()
        rows += "按时率" to "%.1f%%".format(Locale.US, rate)
        rows += "出勤天数" to "$attendanceDays 天"
        rows += "请假天数" to "${leaveDateKeys.size} 天"
        rows += "加班次数" to overtimeEntries.size.toString()
        rows += "加班总时长" to "%.1f 小时".format(Locale.US, overtimeMinutes / 60.0)

        val sb = StringBuilder(sheetXmlHeader())
        sb.append("<sheetData>")
        var rowNum = 1
        rows.forEach { (k, v) ->
            sb.append(rowXml(rowNum, listOf(cellXml(k, isString = true), cellXml(v, isString = true))))
            rowNum++
        }

        if (ruleStats.isNotEmpty()) {
            sb.append(rowXml(rowNum, listOf(cellXml("【各规则成功统计】", isString = true))))
            rowNum++
            ruleStats.forEach { (name, c) ->
                sb.append(rowXml(rowNum, listOf(cellXml(name, isString = true), cellXml("$c 次", isString = true))))
                rowNum++
            }
        }

        // 按日出勤明细
        sb.append(rowXml(rowNum, listOf(cellXml("【按日出勤明细】", isString = true))))
        rowNum++
        val dayRecordsByDate = records.groupBy { it.timestamp.toLocalDate() }
        val days = when (scope) {
            ExportScope.THIS_MONTH -> {
                val ym = YearMonth.now()
                (1..ym.lengthOfMonth()).map { ym.atDay(it) }
            }
            ExportScope.ALL -> {
                (dayRecordsByDate.keys +
                    leaveDateKeys.map { LocalDate.parse(it) } +
                    timeEntries.map { LocalDate.parse(it.date) })
                    .sorted()
            }
        }
        days.forEach { date ->
            val key = date.toString()
            val dayRecs = dayRecordsByDate[date].orEmpty()
            val isLeave = key in leaveDateKeys
            val hasOvertime = timeEntries.any { it.date == key && it.type == TimeEntry.TYPE_OVERTIME }
            val status = when {
                isLeave -> "请假"
                else -> {
                    val s = dayRecs.count { it.status == CheckStatus.SUCCESS.name }
                    val f = dayRecs.size - s
                    val base = when {
                        s > 0 -> "✓ 出勤(${s}次)"
                        f > 0 -> "✗ 未成功(${f}次)"
                        else -> "未打卡"
                    }
                    if (hasOvertime) "$base；加班" else base
                }
            }
            sb.append(rowXml(rowNum, listOf(cellXml(key, isString = true), cellXml(status, isString = true))))
            rowNum++
        }

        sb.append("</sheetData></worksheet>")
        return sb.toString()
    }

    private fun sheetXmlHeader(): String =
        "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n" +
            "<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">"

    private fun rowXml(row: Int, cells: List<String>): String =
        cells.joinToString(prefix = "<row r=\"$row\">", postfix = "</row>") { it }

    private fun cellXml(value: String, isString: Boolean): String =
        if (isString) {
            "<c t=\"inlineStr\"><is><t>${xmlEscape(value)}</t></is></c>"
        } else {
            "<c><v>$value</v></c>"
        }

    private fun xmlEscape(s: String): String =
        s.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")

    private fun contentTypesXml(sheetCount: Int): String {
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n")
        sb.append("<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">")
        sb.append("<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>")
        sb.append("<Default Extension=\"xml\" ContentType=\"application/xml\"/>")
        sb.append("<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>")
        for (i in 1..sheetCount) {
            sb.append("<Override PartName=\"/xl/worksheets/sheet$i.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>")
        }
        sb.append("</Types>")
        return sb.toString()
    }

    private fun rootRelsXml(): String =
        "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n" +
            "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
            "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/>" +
            "</Relationships>"

    private fun workbookXml(sheetNames: List<String>): String {
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n")
        sb.append("<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">")
        sb.append("<sheets>")
        sheetNames.forEachIndexed { i, name ->
            sb.append("<sheet name=\"${xmlEscape(name)}\" sheetId=\"${i + 1}\" r:id=\"rId${i + 1}\"/>")
        }
        sb.append("</sheets></workbook>")
        return sb.toString()
    }

    private fun workbookRelsXml(sheetCount: Int): String {
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n")
        sb.append("<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">")
        for (i in 1..sheetCount) {
            sb.append("<Relationship Id=\"rId$i\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet$i.xml\"/>")
        }
        sb.append("</Relationships>")
        return sb.toString()
    }

    // ---------- JSON 备份 / 恢复 ----------

    /** 导出完整数据备份（规则 + 记录 + 请假 + 时间段标注）为 JSON 文件 */
    suspend fun exportJson(
        context: Context,
        rules: List<CheckInRule>,
        records: List<CheckInRecord>,
        leaveDays: List<LeaveDay>,
        timeEntries: List<TimeEntry>
    ): File = withContext(Dispatchers.IO) {
        val file = File(exportDir(context), "打卡数据备份_${stamp()}.json")

        val root = JSONObject()
        root.put("app", "CheckInApp")
        root.put("version", 1)
        root.put("exportTime", System.currentTimeMillis())

        val rulesArr = JSONArray()
        rules.forEach { r ->
            rulesArr.put(
                JSONObject()
                    .put("name", r.name)
                    .put("startHour", r.startHour)
                    .put("startMinute", r.startMinute)
                    .put("endHour", r.endHour)
                    .put("endMinute", r.endMinute)
                    .put("latitude", r.latitude)
                    .put("longitude", r.longitude)
                    .put("radiusMeters", r.radiusMeters)
                    .put("enabled", r.enabled)
                    .put("daysOfWeek", r.daysOfWeek)
            )
        }
        root.put("rules", rulesArr)

        val recordsArr = JSONArray()
        records.forEach { r ->
            recordsArr.put(
                JSONObject()
                    .put("timestamp", r.timestamp)
                    .put("latitude", r.latitude)
                    .put("longitude", r.longitude)
                    .put("address", r.address ?: "")
                    .put("ruleName", r.ruleName ?: "")
                    .put("status", r.status)
                    .put("note", r.note ?: "")
                    .put("photoPath", r.photoPath ?: "")
            )
        }
        root.put("records", recordsArr)

        val leaveArr = JSONArray()
        leaveDays.forEach { d ->
            leaveArr.put(JSONObject().put("date", d.date))
        }
        root.put("leaveDays", leaveArr)

        val entryArr = JSONArray()
        timeEntries.forEach { e ->
            entryArr.put(
                JSONObject()
                    .put("date", e.date)
                    .put("type", e.type)
                    .put("startMinute", e.startMinute)
                    .put("endMinute", e.endMinute)
                    .put("note", e.note ?: "")
            )
        }
        root.put("timeEntries", entryArr)

        FileOutputStream(file).use { out ->
            out.write(root.toString().toByteArray(Charsets.UTF_8))
        }
        file
    }

    /** 解析备份 JSON，失败返回 null */
    fun parseBackup(json: String): BackupData? = runCatching {
        val root = JSONObject(json)

        val rules = mutableListOf<CheckInRule>()
        root.optJSONArray("rules")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                rules += CheckInRule(
                    name = o.getString("name"),
                    startHour = o.getInt("startHour"),
                    startMinute = o.getInt("startMinute"),
                    endHour = o.getInt("endHour"),
                    endMinute = o.getInt("endMinute"),
                    latitude = o.getDouble("latitude"),
                    longitude = o.getDouble("longitude"),
                    radiusMeters = o.getDouble("radiusMeters"),
                    enabled = o.optBoolean("enabled", true),
                    daysOfWeek = o.optInt("daysOfWeek", 127)
                )
            }
        }

        val records = mutableListOf<CheckInRecord>()
        root.optJSONArray("records")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                records += CheckInRecord(
                    timestamp = o.getLong("timestamp"),
                    latitude = o.getDouble("latitude"),
                    longitude = o.getDouble("longitude"),
                    address = o.optString("address").ifEmpty { null },
                    ruleName = o.optString("ruleName").ifEmpty { null },
                    status = o.getString("status"),
                    note = o.optString("note").ifEmpty { null },
                    photoPath = o.optString("photoPath").ifEmpty { null }
                )
            }
        }

        val leaveDays = mutableListOf<LeaveDay>()
        root.optJSONArray("leaveDays")?.let { arr ->
            for (i in 0 until arr.length()) {
                leaveDays += LeaveDay(date = arr.getJSONObject(i).getString("date"))
            }
        }

        val timeEntries = mutableListOf<TimeEntry>()
        root.optJSONArray("timeEntries")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                timeEntries += TimeEntry(
                    date = o.getString("date"),
                    type = o.getString("type"),
                    startMinute = o.getInt("startMinute"),
                    endMinute = o.getInt("endMinute"),
                    note = o.optString("note").ifEmpty { null }
                )
            }
        }

        BackupData(rules, records, leaveDays, timeEntries)
    }.getOrNull()
}
