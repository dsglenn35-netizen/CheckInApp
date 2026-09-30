package com.example.checkin.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import androidx.core.content.FileProvider
import com.example.checkin.data.CheckInRecord
import com.example.checkin.data.CheckInRule
import com.example.checkin.data.CheckInSite
import com.example.checkin.data.CheckStatus
import com.example.checkin.data.LeaveDay
import com.example.checkin.data.MatchSource
import com.example.checkin.data.RecordOrigin
import com.example.checkin.data.ShiftOverride
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

/** 导出范围显示名：指定了具体月份时优先显示该月份，否则用范围标签 */
fun exportScopeLabel(scope: ExportScope, month: YearMonth?): String =
    month?.let { "${it.year}年${it.monthValue}月" } ?: scope.label

/** 导出格式 */
enum class ExportFormat {
    CSV,
    XLSX
}

/**
 * 备份中的附加地点：关联到所属规则。
 *
 * 恢复时规则会重新插入并取得新主键，所以不能存主键。
 * 但规则名也不是唯一键（允许同名规则），因此 v2.5 起改用
 * [ruleIndex]——该规则在备份 rules 数组中的下标，唯一且稳定；
 * [ruleName] 保留用于旧备份回退匹配与人工排查。
 */
data class BackupSite(
    val ruleName: String,
    /** 所属规则在备份 rules 数组中的下标；旧备份为 -1，恢复时回退按 [ruleName] 匹配 */
    val ruleIndex: Int = -1,
    val site: CheckInSite
)

/**
 * 备份数据：规则 + 附加打卡地点 + 记录 + 请假 + 时间段标注
 * （[photos] 为可选的内嵌照片，键为记录在 [records] 中的**下标**，值为 base64 图片数据。
 *   不用打卡时间戳做键：同一秒内的两条记录时间戳会重复，会互相覆盖）
 */
data class BackupData(
    val rules: List<CheckInRule>,
    val records: List<CheckInRecord>,
    val leaveDays: List<LeaveDay> = emptyList(),
    val timeEntries: List<TimeEntry> = emptyList(),
    val sites: List<BackupSite> = emptyList(),
    /** 调班 / 调休例外（不备份就会在恢复后静默丢失） */
    val shiftOverrides: List<ShiftOverride> = emptyList(),
    val photos: Map<Int, String> = emptyMap()
)

/**
 * 导出/备份工具：
 * - CSV：UTF-8 带 BOM，Excel/WPS 直接打开中文不乱码（完整记录列表）
 * - XLSX：无第三方依赖的最小 Excel 文件，Sheet1「打卡记录」+ Sheet2「汇总统计」
 *   + Sheet3「考勤日报」（HR 版月度考勤表：上下班时刻/迟到早退/加班时长）
 * - JSON：完整数据备份（规则 + 附加地点 + 记录 + 请假 + 时间段标注，可选内嵌照片），可恢复
 */
object ExportManager {

    /** 判定"迟到"的宽限（分钟）：应到时刻之后这么久仍未打卡即视为迟到 */
    private const val LATE_GRACE_MINUTES = 0

    /** 判定"早退"的宽限（分钟）：应离时刻之前这么久已无打卡即视为早退 */
    private const val EARLY_LEAVE_GRACE_MINUTES = 0

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
                .append(csvField(if (hasCoords) "%.6f".format(Locale.US, r.latitude) else "", guardFormula = false)).append(',')
                .append(csvField(if (hasCoords) "%.6f".format(Locale.US, r.longitude) else "", guardFormula = false)).append(',')
                .append(csvField(r.address)).append(',')
                .append(csvField(r.note)).append('\n')
        }

        FileOutputStream(file).use { out ->
            out.write(sb.toString().toByteArray(Charsets.UTF_8))
        }
        file
    }

    /**
     * CSV 字段转义：含逗号/引号/换行时用双引号包裹，内部引号翻倍。
     *
     * [guardFormula] 为 true 时同时做**公式注入防护**：以 = + - @ 或制表符开头的单元格
     * 会被 Excel/WPS 当作公式执行（CSV Injection），前置一个单引号强制按文本处理。
     * 地址（逆地理编码）与备注都是外部可控文本，必须防护；
     * 由本程序格式化、必然是数值的经纬度列传 false，避免被转成文本。
     */
    private fun csvField(value: Any?, guardFormula: Boolean = true): String {
        val raw = value?.toString() ?: ""
        val s = if (guardFormula && raw.isNotEmpty() &&
            (raw[0] == '=' || raw[0] == '+' || raw[0] == '-' || raw[0] == '@' ||
                raw[0] == '\t' || raw[0] == '\r')
        ) "'" + raw else raw
        return if (s.contains(',') || s.contains('"') || s.contains('\n') || s.contains('\r')) {
            "\"" + s.replace("\"", "\"\"") + "\""
        } else s
    }

    // ---------- XLSX（无依赖的最小实现） ----------

    /**
     * 生成 .xlsx 文件：
     * Sheet1「打卡记录」（全部记录）+ Sheet2「汇总统计」
     * （出勤/请假/加班/按时率/各规则统计/按日出勤明细）
     * + Sheet3「考勤日报」（HR 版月度考勤表：应打卡/实打卡/上下班时刻/迟到早退/加班时长）。
     *
     * @param month 指定导出的月份（非 null 时优先于 [scope] 的范围过滤，
     *              汇总统计与考勤日报的明细也只列出该月天数）
     */
    suspend fun exportXlsx(
        context: Context,
        records: List<CheckInRecord>,
        rules: List<CheckInRule>,
        sites: List<CheckInSite>,
        leaveDays: List<LeaveDay>,
        timeEntries: List<TimeEntry>,
        scope: ExportScope,
        month: YearMonth? = null,
        overrides: Map<String, Boolean> = emptyMap()
    ): File = withContext(Dispatchers.IO) {
        val label = exportScopeLabel(scope, month)
        val file = File(exportDir(context), "打卡记录_${label}_${stamp()}.xlsx")
        val employee = EmployeePrefs.load(context)
        val sheets = listOf(
            "打卡记录" to recordsSheetXml(records),
            "汇总统计" to summarySheetXml(
                records, rules, leaveDays, timeEntries, scope, month, overrides
            ),
            "考勤日报" to attendanceSheetXml(
                records, rules, leaveDays, timeEntries, scope, month, employee, overrides
            )
        )

        assembleXlsx(file, sheets)
        file
    }

    /**
     * 把若干工作表按 OOXML(OPC) 包结构写成 .xlsx。
     *
     * 抽成不依赖 Android API 的纯函数有两个好处：
     * 1. 导出与单测共用同一段打包逻辑，测的就是真正跑的那份；
     * 2. 单测可以把包真的打出来再拆开校验部件是否齐全 —— 手写 OOXML 一旦
     *    漏了某个部件或关系指错，Excel 只会报"文件已损坏"，真机上极难定位。
     */
    private fun assembleXlsx(file: File, sheets: List<Pair<String, String>>) {
        ZipOutputStream(FileOutputStream(file)).use { zip ->
            zip.writeEntry("[Content_Types].xml", contentTypesXml(sheets.size))
            zip.writeEntry("_rels/.rels", rootRelsXml())
            zip.writeEntry("xl/workbook.xml", workbookXml(sheets.map { it.first }))
            zip.writeEntry("xl/_rels/workbook.xml.rels", workbookRelsXml(sheets.size))
            zip.writeEntry("xl/styles.xml", stylesXml())
            sheets.forEachIndexed { i, (_, xml) ->
                zip.writeEntry("xl/worksheets/sheet${i + 1}.xml", xml)
            }
        }
    }

    private fun ZipOutputStream.writeEntry(name: String, content: String) {
        putNextEntry(ZipEntry(name))
        write(content.toByteArray(Charsets.UTF_8))
        closeEntry()
    }

    private fun recordsSheetXml(records: List<CheckInRecord>): String {
        val sb = StringBuilder(
            sheetXmlHeader(
                freezeRows = 1,
                colWidths = listOf(6.0, 20.0, 14.0, 16.0, 12.0, 12.0, 34.0, 12.0, 22.0, 24.0)
            )
        )
        sb.append("<sheetData>")
        val headers = listOf(
            "序号", "打卡时间", "打卡状态", "命中规则", "纬度", "经度", "地址", "验证方式", "异常留痕", "备注"
        )
        sb.append(rowXml(1, headers.map { cellXml(it, isString = true, style = 1) }))
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
                cellXml(matchSourceLabel(r.matchSource) ?: "GPS 定位", isString = true),
                cellXml(IntegrityChecks.anomalyLabel(r), isString = true),
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
        scope: ExportScope,
        month: YearMonth? = null,
        overrides: Map<String, Boolean> = emptyMap()
    ): String {
        // 出勤口径：有效出勤 = 正常打卡 + 外勤打卡，外勤单独计数以便 HR 区分
        val success = records.count { CheckStatus.isNormal(it.status) }
        val fieldWork = records.count { it.status == CheckStatus.FIELD_WORK.name }
        val attended = success + fieldWork
        val fail = records.size - attended
        val rate = if (records.isEmpty()) 0.0 else attended * 100.0 / records.size

        // 出勤天数：有"有效出勤"记录的天数
        val attendanceDays = records
            .filter { CheckStatus.isAttended(it.status) }
            .map { it.timestamp.toLocalDate() }
            .distinct()
            .size

        // 请假 / 放假天数：全天标记 + 对应时段标注的日期，分别去重
        val leaveDateKeys = (leaveDays.filter { !it.isHoliday }.map { it.date } +
            timeEntries.filter { it.type == TimeEntry.TYPE_LEAVE }.map { it.date }).toSet()
        val holidayDateKeys = (leaveDays.filter { it.isHoliday }.map { it.date } +
            timeEntries.filter { it.type == TimeEntry.TYPE_HOLIDAY }.map { it.date }).toSet()

        // 加班
        val overtimeEntries = timeEntries.filter { it.type == TimeEntry.TYPE_OVERTIME }
        val overtimeMinutes = overtimeEntries.sumOf { (it.endMinute - it.startMinute).coerceAtLeast(0) }

        // 各规则出勤统计（含外勤：外勤同样算这条规则的出勤）
        val ruleStats = rules.mapNotNull { rule ->
            val c = records.count {
                CheckStatus.isAttended(it.status) && it.ruleName == rule.name
            }
            if (c > 0) rule.name to c else null
        }

        val rows = mutableListOf<Pair<String, String>>()
        rows += "导出范围" to exportScopeLabel(scope, month)
        rows += "导出时间" to formatDateTime(System.currentTimeMillis())
        rows += "记录总数" to records.size.toString()
        rows += "正常打卡" to success.toString()
        rows += "外勤打卡" to fieldWork.toString()
        rows += "失败次数" to fail.toString()
        rows += "按时率" to "%.1f%%".format(Locale.US, rate)
        rows += "出勤天数" to "$attendanceDays 天"
        rows += "请假天数" to "${leaveDateKeys.size} 天"
        rows += "放假日数" to "${holidayDateKeys.size} 天"
        rows += "加班次数" to overtimeEntries.size.toString()
        rows += "加班总时长" to "%.1f 小时".format(Locale.US, overtimeMinutes / 60.0)

        // 自由工时制的达标汇总（只有存在自由工时规则时才出现这几行）。
        // 请假 / 放假当天不计入应工作天数 —— 那些日子本来就不该上班，
        // 算进去只会让达标率莫名其妙地掉。
        if (rules.any { FlexibleWork.isFlexibleWithTarget(it) }) {
            val flexibleMonth = month ?: YearMonth.now()
            val excludedDays = (leaveDateKeys + holidayDateKeys)
                .mapNotNull { runCatching { LocalDate.parse(it) }.getOrNull() }
                .toSet()
            val flexible = FlexibleWork.summarize(
                records = records,
                rules = rules,
                month = flexibleMonth,
                excludedDates = excludedDays,
                overrides = overrides
            )
            rows += "自由工时应工作" to formatDuration(flexible.requiredMinutes)
            rows += "自由工时实际工时" to formatDuration(flexible.workedMinutes)
            rows += "工时达标天数" to
                (flexible.metDays.toString() + " / " + flexible.requiredDays.toString() + " 天")
            rows += "工时缺口" to formatDuration(flexible.shortfallMinutes)
        }
        // 审计留痕的汇总：这张表里有多少条是人工修正过的，一眼可见
        rows += "其中人工修正" to "${records.count { it.isEdited }} 条"
        rows += "其中补卡" to (records.count { it.origin == RecordOrigin.MAKEUP.name })
            .toString() + " 条"

        val sb = StringBuilder(sheetXmlHeader(freezeRows = 1, colWidths = listOf(18.0, 42.0)))
        sb.append("<sheetData>")
        var rowNum = 1
        rows.forEach { (k, v) ->
            // 指标名加粗，值常规 —— 扫一眼就能找到要看的那一行
            sb.append(
                rowXml(
                    rowNum,
                    listOf(cellXml(k, isString = true, style = 1), cellXml(v, isString = true))
                )
            )
            rowNum++
        }

        if (ruleStats.isNotEmpty()) {
            sb.append(rowXml(rowNum, listOf(cellXml("【各规则出勤统计】", isString = true, style = 1))))
            rowNum++
            ruleStats.forEach { (name, c) ->
                sb.append(rowXml(rowNum, listOf(cellXml(name, isString = true), cellXml("$c 次", isString = true))))
                rowNum++
            }
        }

        // 按日出勤明细
        sb.append(rowXml(rowNum, listOf(cellXml("【按日出勤明细】", isString = true))))
        rowNum++
        val dayRecordsByDate =
            AttendanceCalculator.groupByAttendanceDate(records, rules, overrides)
        val days = when {
            // 指定月份：列出该月所有天
            month != null -> (1..month.lengthOfMonth()).map { month.atDay(it) }
            scope == ExportScope.THIS_MONTH -> {
                val ym = YearMonth.now()
                (1..ym.lengthOfMonth()).map { ym.atDay(it) }
            }
            else -> {
                (dayRecordsByDate.keys +
                    leaveDateKeys.map { LocalDate.parse(it) } +
                    holidayDateKeys.map { LocalDate.parse(it) } +
                    timeEntries.map { LocalDate.parse(it.date) })
                    .sorted()
            }
        }
        days.forEach { date ->
            val key = date.toString()
            val dayRecs = dayRecordsByDate[date].orEmpty()
            val isLeave = key in leaveDateKeys
            val isHoliday = key in holidayDateKeys
            val hasOvertime = timeEntries.any { it.date == key && it.type == TimeEntry.TYPE_OVERTIME }
            val status = when {
                isHoliday -> "放假"
                isLeave -> "请假"
                else -> {
                    val attended = dayRecs.count { CheckStatus.isAttended(it.status) }
                    val field = dayRecs.count { it.status == CheckStatus.FIELD_WORK.name }
                    val absent = dayRecs.size - attended
                    val base = when {
                        attended > 0 ->
                            "✓ 出勤(" + attended + "次)" +
                                (if (field > 0) " 含外勤(" + field + "次)" else "")
                        absent > 0 -> "✗ 未成功(" + absent + "次)"
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

    // ---------- HR 版考勤日报 ----------

    /** 一天一行的考勤汇总 */
    private data class DayAttendance(
        val date: LocalDate,
        val status: String,
        /** 当天应打卡班次数（放假 / 请假当天为 0） */
        val dueCount: Int,
        val firstIn: Long?,
        val lastOut: Long?,
        val lateMinutes: Int,
        val earlyMinutes: Int,
        val workMinutes: Int,
        val overtimeMinutes: Int,
        /** 当天**缺卡**的班次数（该打卡却没打 / 只打了一次） */
        val missingShifts: Int = 0,
        /** 当天**旷工**的班次数（比应到时刻晚阈值以上） */
        val absentShifts: Int = 0,
        val note: String
    )

    /**
     * 考勤日报 Sheet：一天一行，可直接作为月度考勤表交给 HR。
     *
     * 列：日期、星期、班制、应打卡、实打卡、上班打卡、下班打卡、迟到、早退、在岗时长、加班时长、备注。
     *
     * 判定规则（**以"班次"为单位**，一天多段班不会互相污染）：
     * - **考勤日**：成功记录按其班次窗口起点归属，跨午夜班次（22:00-06:00）的凌晨段
     *   回到窗口开始那天，不会被拆到次日、也不会落到非工作日上；
     * - **在岗时长**：各**班次段内**时长之和，而不是"全天首次 → 全天末次"——
     *   后者会把班次之间的空档（如午休）算成在岗时间；
     * - **迟到**：规则配置了"应到时刻"（requiredStartMinute ≥ 0）且该班次首次打卡晚于它，按班次累加；
     * - **早退**：规则配置了"应离时刻"（requiredEndMinute ≥ 0）且该班次末次打卡早于它，按班次累加；
     * - 未配置应到/应离时刻时，迟到早退列显示 "—"，不做臆断。
     *
     * 注意：自动打卡按"同一规则同一时段只记一次成功"去重，因此**一个班次天然只有一次打卡**，
     * 在岗时长会是空的；要拿到上/下班两个时刻，需要一次手动补打下班卡。
     */
    private fun attendanceSheetXml(
        records: List<CheckInRecord>,
        rules: List<CheckInRule>,
        leaveDays: List<LeaveDay>,
        timeEntries: List<TimeEntry>,
        scope: ExportScope,
        month: YearMonth? = null,
        employee: EmployeeInfo = EmployeeInfo(),
        overrides: Map<String, Boolean> = emptyMap()
    ): String {
        val days = reportDays(records, rules, leaveDays, timeEntries, scope, month, overrides)
        val leaveDateKeys = (leaveDays.filter { !it.isHoliday }.map { it.date } +
            timeEntries.filter { it.type == TimeEntry.TYPE_LEAVE }.map { it.date }).toSet()
        val holidayDateKeys = (leaveDays.filter { it.isHoliday }.map { it.date } +
            timeEntries.filter { it.type == TimeEntry.TYPE_HOLIDAY }.map { it.date }).toSet()
        // 按**考勤日**分组：成功记录归到其班次窗口开始的那一天，
        // 跨午夜夜班的凌晨段因此不会再被拆到次日。
        val recordsByDay = AttendanceCalculator.groupByAttendanceDate(records, rules, overrides)

        // 所有涉及日期里出现过的规则，用于"应打卡"与迟到早退判定（按班制判断当天是否上班）
        val rows = mutableListOf<DayAttendance>()
        // 该天是否配置了应到/应离基准（未配置时迟到早退列显示 "—"，不臆断为 0）
        val baselineLate = mutableMapOf<LocalDate, Boolean>()
        val baselineEarly = mutableMapOf<LocalDate, Boolean>()
        for (date in days) {
            val key = date.toString()
            val dayRecs = recordsByDay[date].orEmpty()
            // 外勤也计入班次打卡：否则"只有外勤的那天"会算成缺卡、工时与迟到早退全丢
            val successRecs = dayRecs
                .filter { CheckStatus.isAttended(it.status) }
                .sortedBy { it.timestamp }

            val isHoliday = key in holidayDateKeys
            val isLeave = key in leaveDateKeys
            val isOff = isHoliday || isLeave
            // 当天各**应打卡班次**（按班制/星期判定生效）。
            // 放假 / 请假当天不计"应打卡"，也不参与迟到早退判定——
            // 否则公司放假会被算成"缺卡"，把出勤率与迟到天数一起污染。
            val shifts =
                if (isOff) emptyList()
                else AttendanceCalculator.shiftsFor(date, dayRecs, rules, overrides)
            val dueCount = shifts.size

            // 实际上班 / 下班时刻：取当天全体的首末次，HR 关心的是"几点来、几点走"
            val firstIn = successRecs.firstOrNull()?.timestamp
            val lastOut = successRecs.lastOrNull()?.timestamp

            // 在岗时长 = 各**班次段内**时长之和。
            // 用"全天首次 → 全天末次"会把班次之间的空档（如午休）也当成在岗：
            // 上午班 09:00-12:00 + 下午班 14:00-18:00 实际 7 小时，会被算成 9 小时。
            val workMinutes = shifts.sumOf { it.workMinutes }

            // 迟到 / 早退：按班次分别判定后累加。
            // 跨午夜班次由 ShiftAttendance 以"窗口起点偏移"换算，
            // 凌晨 02:00 的打卡不会被误判成"比 06:00 应离早退了 4 小时"。
            var lateMinutes = 0
            var earlyMinutes = 0
            var hasLateBaseline = false
            var hasEarlyBaseline = false
            shifts.forEach { shift ->
                if (shift.rule.requiredStartMinute >= 0) {
                    hasLateBaseline = true
                    lateMinutes += shift.lateMinutes
                }
                if (shift.rule.requiredEndMinute >= 0) {
                    hasEarlyBaseline = true
                    earlyMinutes += shift.earlyMinutes
                }
            }
            if (lateMinutes <= LATE_GRACE_MINUTES) lateMinutes = 0
            if (earlyMinutes <= EARLY_LEAVE_GRACE_MINUTES) earlyMinutes = 0

            // 完成的班次数：判断"正常 / 部分打卡"按班次覆盖，而不是数记录条数。
            // 开启"需要下班卡"的班次必须打满两次（上班卡 + 下班卡）才算完成。
            val completeShifts = shifts.count { s ->
                if (s.rule.requireCheckOut) s.punches.size >= 2 else s.hasPunch
            }

            val overtime = timeEntries
                .filter { it.date == key && it.type == TimeEntry.TYPE_OVERTIME }
                .sumOf { (it.endMinute - it.startMinute).coerceAtLeast(0) }

            // 缺卡 / 旷工按**班次**判定，一天多段班不会互相牵连
            val absence = AbsencePolicy.summarize(shifts)

            val status = when {
                isHoliday -> "放假"
                isLeave -> "请假"
                dueCount == 0 -> if (successRecs.isNotEmpty()) "非应打卡" else "—"
                absence.absentShifts > 0 -> "旷工"
                completeShifts >= dueCount -> "正常"
                completeShifts > 0 -> "部分打卡"
                dayRecs.isNotEmpty() -> "未成功"
                else -> "缺卡"
            }

            // 备注：请假/加班/班制/时钟异常/命中 WiFi 等需要 HR 知道的信息
            val notes = mutableListOf<String>()
            if (isHoliday) notes += "放假"
            if (isLeave) notes += "请假"
            val ruleNames = successRecs.mapNotNull { it.ruleName }.distinct()
            if (ruleNames.isNotEmpty()) notes += "规则：${ruleNames.joinToString("、")}"
            shifts.forEach { shift ->
                val sp = ShiftPattern.parse(shift.rule.shiftPattern)
                if (sp.kind == ShiftPattern.Kind.ROTATION) notes += sp.label
            }
            // 只在规则明确"想要下班卡"（开启了需要下班卡，或配置了应离时刻）却只打了一次时提示，
            // 避免对"一天一次打卡"的日常用法刷屏
            val missingOut = shifts.count {
                (it.rule.requireCheckOut || it.rule.requiredEndMinute >= 0) && it.punches.size == 1
            }
            if (missingOut > 0) notes += "缺下班卡 $missingOut 个班次"
            if (absence.absentShifts > 0) {
                notes += "旷工 " + absence.absentShifts + " 个班次"
            }
            if (absence.missingShifts > 0) {
                notes += "缺卡 " + absence.missingShifts + " 个班次"
            }
            val skewed = dayRecs.count { CheckInValidator.isClockSkewed(it.clockSkewMs) }
            if (skewed > 0) notes += "时钟异常 ${skewed} 条"
            val mocked = dayRecs.count { it.mockLocation }
            if (mocked > 0) notes += "定位可疑 ${mocked} 条"
            val wifiMatched = dayRecs.count { it.matchSource == MatchSource.WIFI.name }
            if (wifiMatched > 0) notes += "WiFi 判定 $wifiMatched 条"
            dayRecs.filter { !it.note.isNullOrBlank() }.forEach { notes += it.note!! }

            rows += DayAttendance(
                date = date,
                status = status,
                dueCount = dueCount,
                firstIn = firstIn,
                lastOut = lastOut,
                lateMinutes = lateMinutes,
                earlyMinutes = earlyMinutes,
                workMinutes = workMinutes,
                overtimeMinutes = overtime,
                missingShifts = absence.missingShifts,
                absentShifts = absence.absentShifts,
                note = notes.distinct().joinToString("；")
            )
            // 记录该天是否配置了应到/应离基准（用 map 传递，避免改动 data class）
            baselineLate[date] = hasLateBaseline
            baselineEarly[date] = hasEarlyBaseline
        }

        val sb = StringBuilder(
            sheetXmlHeader(
                // 冻结标题块 + 表头共 4 行，滚动时表头始终可见
                freezeRows = 4,
                colWidths = listOf(
                    11.0, 7.0, 10.0, 8.0, 9.0, 11.0, 11.0, 11.0, 11.0, 10.0, 10.0, 13.0, 30.0
                )
            )
        )
        sb.append("<sheetData>")
        // 表头块（第 1~3 行）：表名 / 人员信息 / 导出时间 —— 让这份表可以直接交出去
        sb.append(rowXml(1, listOf(cellXml(exportScopeLabel(scope, month) + " 考勤表", isString = true, style = 1))))
        sb.append(
            rowXml(
                2,
                listOf(
                    cellXml(
                        employee.headerLine() ?: "姓名：            工号：            部门：",
                        isString = true
                    )
                )
            )
        )
        sb.append(rowXml(3, listOf(cellXml("导出时间：" + formatDateTime(System.currentTimeMillis()), isString = true))))
        val headers = listOf(
            "日期", "星期", "状态", "应打卡", "实打卡", "上班打卡", "下班打卡",
            "迟到(分钟)", "早退(分钟)", "在岗时长", "加班时长", "数据来源", "备注"
        )
        sb.append(rowXml(4, headers.map { cellXml(it, isString = true, style = 1) }))

        val weekNames = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")
        rows.forEachIndexed { index, row ->
            // 应打卡班次数由上面的班次归集给出（放假 / 请假当天为 0，显示 "—"）
            val dueCount = row.dueCount
            val dayRecs = recordsByDay[row.date].orEmpty()
            val actual = dayRecs.count { CheckStatus.isAttended(it.status) }
            // 数据来源：把这天有没有"人改过的记录"直接摆在表上供 HR 核查。
            // 有修正就标"含人工修正"，不掩盖 —— 一张能看出哪里被改过的表才有证据价值。
            val sourceText = when {
                dayRecs.any { it.isEdited } -> "含人工修正"
                // 补卡是"人新加的一条"，与"改过的"一样必须摆在表上
                dayRecs.any { it.origin == RecordOrigin.MAKEUP.name } -> "含补卡"
                dayRecs.any { it.isManual } -> "手动打卡"
                dayRecs.isEmpty() -> ""
                else -> "自动打卡"
            }
            val lateText = when {
                !baselineLate.getOrDefault(row.date, false) -> "—"
                row.lateMinutes > 0 -> row.lateMinutes.toString()
                else -> "0"
            }
            val earlyText = when {
                !baselineEarly.getOrDefault(row.date, false) -> "—"
                row.earlyMinutes > 0 -> row.earlyMinutes.toString()
                else -> "0"
            }
            val cells = listOf(
                cellXml(row.date.toString(), isString = true),
                cellXml(weekNames[(row.date.dayOfWeek.value + 6) % 7], isString = true),
                cellXml(row.status, isString = true),
                cellXml(if (dueCount > 0) dueCount.toString() else "—", isString = true),
                cellXml("$actual 次", isString = true),
                cellXml(row.firstIn?.let { formatTime(it) } ?: "", isString = true),
                cellXml(row.lastOut?.let { formatTime(it) } ?: "", isString = true),
                cellXml(lateText, isString = true),
                cellXml(earlyText, isString = true),
                cellXml(if (row.workMinutes > 0) formatDuration(row.workMinutes) else "", isString = true),
                cellXml(if (row.overtimeMinutes > 0) formatDuration(row.overtimeMinutes) else "", isString = true),
                cellXml(sourceText, isString = true),
                cellXml(row.note, isString = true)
            )
            sb.append(rowXml(index + 5, cells))
        }

        // 合计行
        val totalDays = rows.size
        // 正常出勤天数：以日报的 status == "正常" 为准。
        // 注意日报的 status 取值是 "正常 / 部分打卡 / 未成功 / 缺卡 / 旷工" 这一套，
        // 而 "✓ 出勤(N次)" 是汇总页【按日出勤明细】用的另一套文案 ——
        // 之前按后者比较，导致这一格恒为 0（改错方向了，这里改回来）。
        val normalDays = rows.count { it.status == "正常" }
        val missingDays = rows.count { it.missingShifts > 0 }
        val absentDays = rows.count { it.absentShifts > 0 }
        val lateDays = rows.count { it.lateMinutes > 0 }
        val earlyDays = rows.count { it.earlyMinutes > 0 }
        val totalWork = rows.sumOf { it.workMinutes }
        val totalOvertime = rows.sumOf { it.overtimeMinutes }
        sb.append(
            rowXml(
                rows.size + 5,
                listOf(
                    cellXml("合计", isString = true, style = 1),
                    cellXml("$totalDays 天", isString = true),
                    cellXml("正常 $normalDays 天", isString = true),
                    cellXml("缺卡 $missingDays 天 / 旷工 $absentDays 天", isString = true),
                    cellXml("迟到 $lateDays 天", isString = true),
                    cellXml("早退 $earlyDays 天", isString = true),
                    cellXml("", isString = true),
                    cellXml("", isString = true),
                    cellXml("", isString = true),
                    cellXml(formatDuration(totalWork), isString = true),
                    cellXml(formatDuration(totalOvertime), isString = true),
                    cellXml("", isString = true),
                    cellXml("", isString = true)
                )
            )
        )

        // 签字栏：HR 归档时通常要签字确认，留出位置省得再手画
        sb.append(
            rowXml(
                rows.size + 7,
                listOf(cellXml("员工签字：______________     主管签字：______________", isString = true))
            )
        )

        sb.append("</sheetData></worksheet>")
        return sb.toString()
    }

    /** 报告覆盖的日期集合（指定月份 → 该月全月；本月 → 当月全月；全部 → 有数据的日期） */
    private fun reportDays(
        records: List<CheckInRecord>,
        rules: List<CheckInRule>,
        leaveDays: List<LeaveDay>,
        timeEntries: List<TimeEntry>,
        scope: ExportScope,
        month: YearMonth?,
        overrides: Map<String, Boolean> = emptyMap()
    ): List<LocalDate> = when {
        month != null -> (1..month.lengthOfMonth()).map { month.atDay(it) }
        scope == ExportScope.THIS_MONTH -> {
            val ym = YearMonth.now()
            (1..ym.lengthOfMonth()).map { ym.atDay(it) }
        }
        // 记录按**考勤日**取值：跨午夜夜班的凌晨段不会多带出一个日期
        else -> (AttendanceCalculator.groupByAttendanceDate(records, rules, overrides).keys +
            leaveDays.map { LocalDate.parse(it.date) } +
            timeEntries.map { LocalDate.parse(it.date) })
            .distinct()
            .sorted()
    }

    /** 分钟数 -> "Xh Ym" */
    private fun formatDuration(minutes: Int): String =
        if (minutes <= 0) "0" else "${minutes / 60}h ${minutes % 60}m"

    /**
     * 工作表头部：worksheet 根节点 + 冻结窗格 + 列宽。
     *
     * 元素顺序必须遵守 OOXML schema：sheetViews 与 cols 都要排在 sheetData **之前**，
     * 顺序错了 Excel 会报"文件已损坏"。
     *
     * @param freezeRows 冻结前 N 行（0 表示不冻结），滚动时表头始终可见
     * @param colWidths  各列宽度（字符数），空表示用默认宽度
     */
    private fun sheetXmlHeader(freezeRows: Int = 1, colWidths: List<Double> = emptyList()): String {
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n")
        sb.append("<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">")
        if (freezeRows > 0) {
            sb.append("<sheetViews><sheetView workbookViewId=\"0\">")
            sb.append("<pane ySplit=\"")
                .append(freezeRows)
                .append("\" topLeftCell=\"A")
                .append(freezeRows + 1)
                .append("\" activePane=\"bottomLeft\" state=\"frozen\"/>")
            sb.append("</sheetView></sheetViews>")
        }
        if (colWidths.isNotEmpty()) {
            sb.append("<cols>")
            colWidths.forEachIndexed { i, w ->
                sb.append("<col min=\"").append(i + 1).append("\" max=\"").append(i + 1)
                    .append("\" width=\"").append(w).append("\" customWidth=\"1\"/>")
            }
            sb.append("</cols>")
        }
        return sb.toString()
    }

    /**
     * 最小样式表：只定义两种单元格格式 —— 常规、加粗（表头）。
     * 没有 styles.xml 时所有单元格都是同一副样子，表头与数据无法区分，交给 HR 还得自己排版。
     */
    private fun stylesXml(): String =
        "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n" +
            "<styleSheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">" +
            "<fonts count=\"2\">" +
            "<font><sz val=\"11\"/><name val=\"Calibri\"/></font>" +
            "<font><b/><sz val=\"11\"/><name val=\"Calibri\"/></font>" +
            "</fonts>" +
            "<fills count=\"2\">" +
            "<fill><patternFill patternType=\"none\"/></fill>" +
            "<fill><patternFill patternType=\"gray125\"/></fill>" +
            "</fills>" +
            "<borders count=\"1\">" +
            "<border><left/><right/><top/><bottom/><diagonal/></border>" +
            "</borders>" +
            "<cellStyleXfs count=\"1\">" +
            "<xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/>" +
            "</cellStyleXfs>" +
            "<cellXfs count=\"2\">" +
            "<xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\"/>" +
            "<xf numFmtId=\"0\" fontId=\"1\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyFont=\"1\"/>" +
            "</cellXfs>" +
            "<cellStyles count=\"1\">" +
            "<cellStyle name=\"Normal\" xfId=\"0\" builtinId=\"0\"/>" +
            "</cellStyles>" +
            "</styleSheet>"

    private fun rowXml(row: Int, cells: List<String>): String =
        cells.joinToString(prefix = "<row r=\"$row\">", postfix = "</row>") { it }

    /**
     * 单元格。
     * @param style cellXfs 下标：0 = 常规，1 = 加粗（表头）。默认 0 时不写 s 属性。
     */
    private fun cellXml(value: String, isString: Boolean, style: Int = 0): String {
        val s = if (style > 0) " s=\"$style\"" else ""
        return if (isString) {
            "<c$s t=\"inlineStr\"><is><t>${xmlEscape(value)}</t></is></c>"
        } else {
            "<c$s><v>$value</v></c>"
        }
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
        sb.append("<Override PartName=\"/xl/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml\"/>")
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
        // styles 关系：rId 排在所有 sheet 之后，避免与上面的 rId 冲突
        sb.append("<Relationship Id=\"rId${sheetCount + 1}\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/>")
        sb.append("</Relationships>")
        return sb.toString()
    }

    // ---------- 单元测试钩子 ----------
    // 以下三个函数只为单测暴露：XLSX 的 XML 拼接一旦写错（标签不闭合、节点顺序违反
    // OOXML schema），Excel 只会报"文件已损坏"，在真机上极难定位。
    // 这些逻辑不依赖任何 Android API，因此可以直接在 JVM 单测里校验结构。

    /** 仅供单元测试：三张工作表的 XML（名称 -> XML） */
    internal fun allSheetsXmlForTest(
        records: List<CheckInRecord>,
        rules: List<CheckInRule>,
        leaveDays: List<LeaveDay> = emptyList(),
        timeEntries: List<TimeEntry> = emptyList(),
        scope: ExportScope = ExportScope.THIS_MONTH,
        month: YearMonth? = null,
        employee: EmployeeInfo = EmployeeInfo()
    ): List<Pair<String, String>> = listOf(
        "打卡记录" to recordsSheetXml(records),
        "汇总统计" to summarySheetXml(records, rules, leaveDays, timeEntries, scope, month),
        "考勤日报" to attendanceSheetXml(
            records, rules, leaveDays, timeEntries, scope, month, employee
        )
    )

    /** 仅供单元测试：样式表 XML */
    internal fun stylesXmlForTest(): String = stylesXml()

    /**
     * 仅供单元测试：用与正式导出**完全相同**的打包逻辑生成一个 .xlsx 文件，
     * 供测试拆包校验 OPC 结构是否完整。
     */
    internal fun writeXlsxForTest(
        file: File,
        records: List<CheckInRecord>,
        rules: List<CheckInRule>,
        leaveDays: List<LeaveDay> = emptyList(),
        timeEntries: List<TimeEntry> = emptyList(),
        scope: ExportScope = ExportScope.ALL,
        month: YearMonth? = null,
        employee: EmployeeInfo = EmployeeInfo()
    ) {
        val sheets = listOf(
            "打卡记录" to recordsSheetXml(records),
            "汇总统计" to summarySheetXml(records, rules, leaveDays, timeEntries, scope, month),
            "考勤日报" to attendanceSheetXml(
                records, rules, leaveDays, timeEntries, scope, month, employee
            )
        )
        assembleXlsx(file, sheets)
    }

    /** 仅供单元测试：内容类型与关系文件的 XML */
    internal fun packageXmlForTest(sheetCount: Int): List<Pair<String, String>> = listOf(
        "[Content_Types].xml" to contentTypesXml(sheetCount),
        "_rels/.rels" to rootRelsXml(),
        "xl/workbook.xml" to workbookXml(List(sheetCount) { "S" + (it + 1) }),
        "xl/_rels/workbook.xml.rels" to workbookRelsXml(sheetCount)
    )

    // ---------- JSON 备份 / 恢复 ----------

    /**
     * 备份格式版本：
     * 3 起 sites 带 ruleIndex、照片按记录下标关联；4 起记录带审计字段；
     * 5 起记录带完整性留痕（模拟位置）；6 起规则带自由工时制与每日工时目标；
     * 7 起带调班 / 调休例外表（不备份就会在恢复后静默丢失）。
     */
    private const val BACKUP_VERSION = 7

    /**
     * 导出完整数据备份（规则 + 附加地点 + 记录 + 请假 + 时间段标注）为 JSON 文件。
     *
     * @param withPhotos true 时把每张取证照片以 base64 内嵌进 JSON（换机也能恢复照片），
     *                   体积会显著增大，由用户在设置页显式开启
     */
    suspend fun exportJson(
        context: Context,
        rules: List<CheckInRule>,
        records: List<CheckInRecord>,
        leaveDays: List<LeaveDay>,
        timeEntries: List<TimeEntry>,
        sites: List<CheckInSite> = emptyList(),
        shiftOverrides: List<ShiftOverride> = emptyList(),
        withPhotos: Boolean = false
    ): File = withContext(Dispatchers.IO) {
        val file = File(exportDir(context), "打卡数据备份_${stamp()}.json")

        val root = JSONObject()
        root.put("app", "CheckInApp")
        root.put("version", BACKUP_VERSION)
        root.put("exportTime", System.currentTimeMillis())
        root.put("withPhotos", withPhotos)

        // 附加地点：用所属规则名建立关联（备份中不保存主键，恢复时规则会获得新主键）
        val ruleNameById = rules.associate { it.id to it.name }
        // 规则在数组中的下标：恢复时规则主键会变、规则名可能重复，下标是唯一稳定的关联键
        val ruleIndexById = rules.mapIndexed { i, r -> r.id to i }.toMap()
        val sitesArr = JSONArray()
        sites.forEach { s ->
            sitesArr.put(
                JSONObject()
                    .put("ruleIndex", ruleIndexById[s.ruleId] ?: -1)
                    .put("ruleName", ruleNameById[s.ruleId] ?: "")
                    .put("name", s.name)
                    .put("latitude", s.latitude)
                    .put("longitude", s.longitude)
                    .put("radiusMeters", s.radiusMeters)
                    .put("wifiSsid", s.wifiSsid ?: "")
            )
        }
        root.put("sites", sitesArr)

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
                    .put("shiftPattern", r.shiftPattern)
                    .put("requiredStartMinute", r.requiredStartMinute)
                    .put("requiredEndMinute", r.requiredEndMinute)
                    .put("flexible", r.flexible)
                    .put("requiredWorkMinutes", r.requiredWorkMinutes)
                    .put("wifiSsid", r.wifiSsid ?: "")
            )
        }
        root.put("rules", rulesArr)

        val overridesArr = JSONArray()
        shiftOverrides.forEach { o ->
            overridesArr.put(
                JSONObject()
                    .put("date", o.date)
                    .put("ruleId", o.ruleId)
                    .put("working", o.working)
            )
        }
        root.put("shiftOverrides", overridesArr)

        val recordsArr = JSONArray()
        records.forEach { r ->
            val o = JSONObject()
                .put("timestamp", r.timestamp)
                .put("latitude", r.latitude)
                .put("longitude", r.longitude)
                .put("address", r.address ?: "")
                .put("ruleName", r.ruleName ?: "")
                .put("status", r.status)
                .put("note", r.note ?: "")
                .put("matchSource", r.matchSource)
                .put("clockSkewMs", r.clockSkewMs ?: -1L)
                // 完整性留痕同样必须进备份：恢复后"哪条地点不可信"不能丢
                .put("mockLocation", r.mockLocation)
                // 审计留痕：备份必须带上，否则恢复后"哪条被改过"就丢了
                .put("origin", r.origin)
                .put("editedAt", r.editedAt ?: -1L)
                .put("originalTimestamp", r.originalTimestamp ?: -1L)
            // 照片：默认只存路径；开启"包含照片"时内嵌 base64（并保留原路径作兼容）
            o.put("photoPath", r.photoPath ?: "")
            if (withPhotos && !r.photoPath.isNullOrBlank()) {
                encodePhotoFile(File(r.photoPath))?.let { o.put("photoBase64", it) }
            }
            recordsArr.put(o)
        }
        root.put("records", recordsArr)

        val leaveArr = JSONArray()
        leaveDays.forEach { d ->
            leaveArr.put(JSONObject().put("date", d.date).put("kind", d.kind))
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

    /** 读取照片文件并 base64 编码；不存在或读取失败返回 null（照片缺失不阻断备份） */
    private fun encodePhotoFile(file: File): String? = runCatching {
        if (!file.isFile || file.length() <= 0L) return null
        Base64.encodeToString(file.readBytes(), Base64.NO_WRAP)
    }.getOrNull()

    /**
     * 解析备份 JSON，失败返回 null。
     * 兼容 v1 备份（无 sites / 无照片 / 无班制字段），缺失字段回落到默认值。
     */
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
                    daysOfWeek = o.optInt("daysOfWeek", 127),
                    shiftPattern = o.optString("shiftPattern", ""),
                    requiredStartMinute = o.optInt("requiredStartMinute", -1),
                    // 旧备份没有自由工时字段：按"固定班次"恢复
                    flexible = o.optBoolean("flexible", false),
                    requiredWorkMinutes = o.optInt("requiredWorkMinutes", -1),
                    requiredEndMinute = o.optInt("requiredEndMinute", -1),
                    wifiSsid = o.optString("wifiSsid").ifEmpty { null }
                )
            }
        }

        val records = mutableListOf<CheckInRecord>()
        root.optJSONArray("records")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val skew = o.optLong("clockSkewMs", -1L)
                val editedAt = o.optLong("editedAt", -1L)
                val originalTs = o.optLong("originalTimestamp", -1L)
                // 旧备份（v2.9 之前）没有审计字段，按"自动打卡、未修正"处理
                val origin = o.optString("origin", RecordOrigin.AUTO.name)
                    .ifBlank { RecordOrigin.AUTO.name }
                records += CheckInRecord(
                    timestamp = o.getLong("timestamp"),
                    latitude = o.getDouble("latitude"),
                    longitude = o.getDouble("longitude"),
                    address = o.optString("address").ifEmpty { null },
                    ruleName = o.optString("ruleName").ifEmpty { null },
                    status = o.getString("status"),
                    note = o.optString("note").ifEmpty { null },
                    photoPath = o.optString("photoPath").ifEmpty { null },
                    matchSource = o.optString("matchSource", MatchSource.GPS.name),
                    clockSkewMs = if (skew >= 0L) skew else null,
                    mockLocation = o.optBoolean("mockLocation", false),
                    origin = origin,
                    editedAt = if (editedAt >= 0L) editedAt else null,
                    originalTimestamp = if (originalTs >= 0L) originalTs else null
                )
            }
        }

        val shiftOverrides = mutableListOf<ShiftOverride>()
        root.optJSONArray("shiftOverrides")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                shiftOverrides += ShiftOverride(
                    date = o.getString("date"),
                    ruleId = o.getLong("ruleId"),
                    working = o.optBoolean("working", true)
                )
            }
        }

        val leaveDays = mutableListOf<LeaveDay>()
        root.optJSONArray("leaveDays")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                // 旧备份没有 kind 字段，按请假处理
                leaveDays += LeaveDay(
                    date = o.getString("date"),
                    kind = o.optString("kind", LeaveDay.KIND_LEAVE)
                )
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

        // 附加地点：按记录的 ruleName 关联回规则主键（恢复时规则是重新插入的，主键会变）
        val sites = mutableListOf<BackupSite>()
        root.optJSONArray("sites")?.let { sitesArr ->
            for (i in 0 until sitesArr.length()) {
                val o = sitesArr.getJSONObject(i)
                sites += BackupSite(
                    ruleName = o.optString("ruleName", ""),
                    ruleIndex = o.optInt("ruleIndex", -1),
                    site = CheckInSite(
                        ruleId = 0L,
                        name = o.optString("name", "打卡点"),
                        latitude = o.getDouble("latitude"),
                        longitude = o.getDouble("longitude"),
                        radiusMeters = o.getDouble("radiusMeters"),
                        wifiSsid = o.optString("wifiSsid").ifEmpty { null }
                    )
                )
            }
        }

        // 内嵌照片：键为记录在 records 数组中的下标（时间戳可能重复，不能做键）
        val photos = mutableMapOf<Int, String>()
        root.optJSONArray("records")?.let { arr ->
            for (i in 0 until arr.length()) {
                val b64 = arr.getJSONObject(i).optString("photoBase64")
                if (b64.isNotEmpty()) photos[i] = b64
            }
        }

        BackupData(rules, records, leaveDays, timeEntries, sites, shiftOverrides, photos)
    }.getOrNull()
}
