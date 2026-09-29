package com.example.checkin.ui

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.checkin.data.CheckInRecord
import com.example.checkin.data.CheckInRule
import com.example.checkin.data.CheckStatus
import com.example.checkin.data.LeaveDay
import com.example.checkin.data.TimeEntry
import com.example.checkin.ui.RecordRow
import com.example.checkin.util.CheckInValidator
import com.example.checkin.util.ExportFormat
import com.example.checkin.util.ExportScope
import com.example.checkin.util.formatHM
import com.example.checkin.util.holidayColor
import com.example.checkin.util.leaveColor
import com.example.checkin.util.missedColor
import com.example.checkin.util.overtimeColor
import com.example.checkin.util.statusColor
import com.example.checkin.util.toLocalDate
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.Calendar

/** 日历页：按月展示打卡情况，点击日期查看当天记录 */
@Composable
fun CalendarScreen(viewModel: CheckInViewModel) {
    val records by viewModel.records.collectAsState()
    val rules by viewModel.rules.collectAsState()
    val leaveDays by viewModel.leaveDays.collectAsState()
    val timeEntries by viewModel.timeEntries.collectAsState()
    val exporting by viewModel.exporting.collectAsState()
    val exportMessage by viewModel.exportMessage.collectAsState()
    val context = LocalContext.current
    var currentMonth by remember { mutableStateOf(YearMonth.now()) }
    var selectedDate by remember { mutableStateOf(LocalDate.now()) }
    var showExportDialog by remember { mutableStateOf(false) }
    // 导出对话框内选择的状态（保持跨打开记忆）
    var exportTarget by remember { mutableStateOf(ExportTarget.THIS_MONTH) }
    var exportMonth by remember { mutableStateOf(YearMonth.now()) }
    var exportFormat by remember { mutableStateOf(ExportFormat.XLSX) }

    LaunchedEffect(exportMessage) {
        exportMessage?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            viewModel.consumeExportMessage()
        }
    }

    val recordsByDay = remember(records) { records.groupBy { it.timestamp.toLocalDate() } }
    // 特殊日按类型拆分：请假（蓝）与公司放假（青绿）语义相同、配色与统计口径不同
    val leaveDates = remember(leaveDays) {
        leaveDays.filter { !it.isHoliday }.map { it.date }.toSet()
    }
    val holidayDates = remember(leaveDays) {
        leaveDays.filter { it.isHoliday }.map { it.date }.toSet()
    }
    val entriesByDay = remember(timeEntries) { timeEntries.groupBy { it.date } }
    var showTimeEntryDialog by remember { mutableStateOf(false) }
    var showRangeDialog by remember { mutableStateOf(false) }
    val monthStats = remember(currentMonth, records) {
        val inMonth = records.filter {
            val d = it.timestamp.toLocalDate()
            d.year == currentMonth.year && d.monthValue == currentMonth.monthValue
        }
        // 出勤口径统一走 CheckStatus.isAttended：外勤算有效出勤（否则会显示成缺勤）
        val success = inMonth.count { CheckStatus.isAttended(it.status) }
        val days = inMonth
            .filter { CheckStatus.isAttended(it.status) }
            .map { it.timestamp.toLocalDate() }
            .distinct()
            .size
        val rate = if (inMonth.isEmpty()) 0 else success * 100 / inMonth.size
        Triple(success, inMonth.size, days) to rate
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { currentMonth = currentMonth.minusMonths(1) }) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "上个月")
            }
            Text(
                "${currentMonth.year}年${currentMonth.monthValue}月",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = { currentMonth = currentMonth.plusMonths(1) }) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "下个月")
            }
        }

        Text(
            "本月出勤 ${monthStats.first.third} 天 · 按时率 ${monthStats.second}% · 成功 ${monthStats.first.first} 次 · 失败 ${monthStats.first.second - monthStats.first.first} 次",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End
        ) {
            TextButton(
                onClick = { showExportDialog = true },
                enabled = !exporting
            ) {
                Icon(
                    Icons.Filled.FileDownload,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(4.dp))
                Text("导出")
            }
        }

        Spacer(Modifier.height(8.dp))

        Row {
            listOf("一", "二", "三", "四", "五", "六", "日").forEach { label ->
                Text(
                    label,
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(Modifier.height(4.dp))

        // 图例：当天状态只是不同颜色的小圆点，不标出来只能靠猜
        CalendarLegend()

        Spacer(Modifier.height(4.dp))

        val firstDay = currentMonth.atDay(1)
        val offset = (firstDay.dayOfWeek.value + 6) % 7 // 周一为一周起始
        val daysInMonth = currentMonth.lengthOfMonth()
        val totalCells = ((offset + daysInMonth + 6) / 7) * 7
        val today = LocalDate.now()

        Column {
            repeat(totalCells / 7) { week ->
                Row {
                    repeat(7) { col ->
                        val cellIndex = week * 7 + col
                        val date = when {
                            cellIndex < offset -> null
                            cellIndex >= offset + daysInMonth -> null
                            else -> currentMonth.atDay(cellIndex - offset + 1)
                        }
                        val dateKey = date?.toString()
                        MonthDayCell(
                            modifier = Modifier.weight(1f),
                            date = date,
                            dayRecords = date?.let { recordsByDay[it] },
                            rules = rules,
                            isLeave = dateKey?.let { it in leaveDates } ?: false,
                            isHoliday = dateKey?.let { it in holidayDates } ?: false,
                            hasLeaveRange = dateKey?.let { k ->
                                entriesByDay[k]?.any { it.type == TimeEntry.TYPE_LEAVE }
                            } ?: false,
                            hasHolidayRange = dateKey?.let { k ->
                                entriesByDay[k]?.any { it.type == TimeEntry.TYPE_HOLIDAY }
                            } ?: false,
                            hasOvertime = dateKey?.let { k ->
                                entriesByDay[k]?.any { it.type == TimeEntry.TYPE_OVERTIME }
                            } ?: false,
                            isSelected = date == selectedDate,
                            isToday = date == today,
                            onClick = { date?.let { selectedDate = it } }
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(24.dp))

        val dayRecords = recordsByDay[selectedDate].orEmpty().sortedByDescending { it.timestamp }
        val selectedKey = selectedDate.toString()
        val selectedIsLeave = selectedKey in leaveDates
        val selectedIsHoliday = selectedKey in holidayDates
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "${selectedDate.year}年${selectedDate.monthValue}月${selectedDate.dayOfMonth}日" +
                    when {
                        selectedIsHoliday -> "（放假）"
                        selectedIsLeave -> "（请假）"
                        else -> ""
                    } +
                    "打卡记录（${dayRecords.size}）",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f)
            )
        }

        // 特殊日标记：请假 / 公司放假。两者都会让当天不再自动打卡（含失败记录）
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End
        ) {
            TextButton(
                onClick = {
                    viewModel.setDayMark(
                        selectedDate,
                        if (selectedIsLeave) null else LeaveDay.KIND_LEAVE
                    )
                }
            ) {
                Text(
                    if (selectedIsLeave) "取消请假" else "标记请假",
                    color = if (selectedIsLeave) MaterialTheme.colorScheme.onSurfaceVariant
                    else MaterialTheme.colorScheme.primary
                )
            }
            TextButton(
                onClick = {
                    viewModel.setDayMark(
                        selectedDate,
                        if (selectedIsHoliday) null else LeaveDay.KIND_HOLIDAY
                    )
                }
            ) {
                Text(
                    if (selectedIsHoliday) "取消放假" else "标记放假",
                    color = if (selectedIsHoliday) MaterialTheme.colorScheme.onSurfaceVariant
                    else holidayColor(isSystemInDarkTheme())
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End
        ) {
            TextButton(onClick = { showRangeDialog = true }) {
                Icon(Icons.Filled.DateRange, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("区间标记")
            }
            TextButton(onClick = { showTimeEntryDialog = true }) {
                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("请假/放假/加班时段")
            }
        }

        // 当天的时间段标注列表（请假/加班）
        val dayEntries = entriesByDay[selectedDate.toString()].orEmpty()
        if (dayEntries.isNotEmpty()) {
            dayEntries.forEach { entry ->
                TimeEntryRow(
                    entry = entry,
                    onDelete = { viewModel.deleteTimeEntry(entry) }
                )
                Spacer(Modifier.height(8.dp))
            }
            Spacer(Modifier.height(8.dp))
        }

        Spacer(Modifier.height(8.dp))
        if (dayRecords.isEmpty()) {
            Text("当天没有打卡记录", color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            dayRecords.forEach { record ->
                RecordRow(
                    record,
                    onDelete = { viewModel.deleteRecord(record) },
                    onEditNote = { note -> viewModel.updateRecordNote(record.id, note) },
                    onMarkLeave = { viewModel.markRecordAsLeave(record) },
                    onMarkFieldWork = { reason -> viewModel.markAsFieldWork(record, reason) }
                )
                Spacer(Modifier.height(8.dp))
            }
        }
        Spacer(Modifier.height(16.dp))
    }

    if (showExportDialog) {
        AlertDialog(
            onDismissRequest = { showExportDialog = false },
            title = { Text("导出打卡记录") },
            text = {
                Column {
                    // 导出范围
                    Text("导出范围", style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = exportTarget == ExportTarget.THIS_MONTH,
                            onClick = { exportTarget = ExportTarget.THIS_MONTH },
                            label = { Text("本月") }
                        )
                        FilterChip(
                            selected = exportTarget == ExportTarget.SPECIFIC_MONTH,
                            onClick = { exportTarget = ExportTarget.SPECIFIC_MONTH },
                            label = { Text("指定月份") }
                        )
                        FilterChip(
                            selected = exportTarget == ExportTarget.ALL,
                            onClick = { exportTarget = ExportTarget.ALL },
                            label = { Text("全部") }
                        )
                    }
                    // 指定月份时显示月份选择器
                    if (exportTarget == ExportTarget.SPECIFIC_MONTH) {
                        Spacer(Modifier.height(8.dp))
                        MonthPicker(month = exportMonth, onMonthChange = { exportMonth = it })
                    }
                    // 导出格式
                    Spacer(Modifier.height(12.dp))
                    Text("导出格式", style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = exportFormat == ExportFormat.CSV,
                            onClick = { exportFormat = ExportFormat.CSV },
                            label = { Text("CSV") }
                        )
                        FilterChip(
                            selected = exportFormat == ExportFormat.XLSX,
                            onClick = { exportFormat = ExportFormat.XLSX },
                            label = { Text("Excel (.xlsx)") }
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !exporting,
                    onClick = {
                        showExportDialog = false
                        val (scope, month) = when (exportTarget) {
                            ExportTarget.THIS_MONTH -> ExportScope.THIS_MONTH to null
                            ExportTarget.SPECIFIC_MONTH -> ExportScope.THIS_MONTH to exportMonth
                            ExportTarget.ALL -> ExportScope.ALL to null
                        }
                        viewModel.exportRecords(scope, exportFormat, month)
                    }
                ) { Text("导出") }
            },
            dismissButton = { TextButton(onClick = { showExportDialog = false }) { Text("取消") } }
        )
    }

    if (showTimeEntryDialog) {
        TimeEntryDialog(
            onDismiss = { showTimeEntryDialog = false },
            onSave = { type, startMinute, endMinute, note ->
                showTimeEntryDialog = false
                viewModel.addTimeEntry(type, selectedDate, startMinute, endMinute, note)
            }
        )
    }

    if (showRangeDialog) {
        RangeMarkDialog(
            initialDate = selectedDate,
            onDismiss = { showRangeDialog = false },
            onApply = { start, end, kind ->
                showRangeDialog = false
                viewModel.markDateRange(start, end, kind)
            },
            onClear = { start, end ->
                showRangeDialog = false
                viewModel.clearDateRange(start, end)
            }
        )
    }
}

@Composable
private fun MonthDayCell(
    modifier: Modifier = Modifier,
    date: LocalDate?,
    dayRecords: List<CheckInRecord>?,
    rules: List<CheckInRule>,
    isLeave: Boolean,
    isHoliday: Boolean,
    hasLeaveRange: Boolean,
    hasHolidayRange: Boolean,
    hasOvertime: Boolean,
    isSelected: Boolean,
    isToday: Boolean,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(8.dp)
    Box(
        modifier = modifier
            .aspectRatio(1f)
            .padding(2.dp)
            .clip(shape)
            .background(
                if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                else Color.Transparent
            )
            .border(
                if (isToday) 1.5.dp else 0.dp,
                MaterialTheme.colorScheme.primary,
                shape
            )
            .clickable(enabled = date != null, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        if (date != null) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    "${date.dayOfMonth}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (isSelected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurface
                )
                DayMarker(
                    date, dayRecords, rules,
                    isLeave, isHoliday, hasLeaveRange, hasHolidayRange, hasOvertime
                )
            }
        }
    }
}

/**
 * 日历格子标记（优先级从上到下）：
 * - 公司放假（全天或时段）→ 青绿标记；
 * - 请假（全天或时段）→ 蓝色标记；
 * - 加班 → 珊瑚橙标记；
 * - 当天无生效规则 → 不显示；
 * - 当天所有应打卡规则都成功打卡 → 绿色；
 * - 有应打卡规则但未全部完成：
 *   - 当天无任何记录 → 灰色（未打卡）；
 *   - 有失败记录 → 按失败原因显示不同颜色（最多 2 个，超出显示 +）
 */
@Composable
private fun DayMarker(
    date: LocalDate,
    dayRecords: List<CheckInRecord>?,
    rules: List<CheckInRule>,
    isLeave: Boolean,
    isHoliday: Boolean,
    hasLeaveRange: Boolean,
    hasHolidayRange: Boolean,
    hasOvertime: Boolean
) {
    val dark = isSystemInDarkTheme()
    // 放假优先于请假（两者语义相同，都表示当天不用打卡；同时存在时按"放假"显示）。
    // 特殊日刻意画得比打卡结果点**大一号**（9dp vs 6dp）：
    // 日历格子很小，仅靠颜色在扫视时仍不够醒目。
    if (isHoliday || hasHolidayRange) {
        StatusDot(holidayColor(dark), size = SPECIAL_DOT_SIZE)
        return
    }
    if (isLeave || hasLeaveRange) {
        // 请假（全天或时段）：玫红
        StatusDot(leaveColor(dark), size = SPECIAL_DOT_SIZE)
        return
    }
    if (hasOvertime) {
        // 加班：珊瑚橙
        StatusDot(overtimeColor(dark))
        return
    }

    // 当天应打卡的规则：只在日期或规则变化时重算（42 个格子 × 每次重组都会调用）
    val dueRules = remember(date, rules) {
        val cal = Calendar.getInstance().apply {
            clear()
            set(date.year, date.monthValue - 1, date.dayOfMonth)
        }
        rules.filter { it.enabled && CheckInValidator.isActiveOnDay(it, cal) }
    }
    if (dueRules.isEmpty()) return

    // 出勤判定用"有效出勤"（正常 + 外勤）；颜色上两者要分开，见下方 allDone 分支
    val attendedRecords = dayRecords.orEmpty().filter { CheckStatus.isAttended(it.status) }

    // 逐条规则判断"是否已成功"，而不是比较"成功规则名去重后的个数"：
    // 计数比较在规则被改名/删除、或存在同名规则时会给出错误结论——
    // 例如删掉一条规则后，往日只完成了 2/3 也会因为分母变小而被判成绿色；
    // 同理两条同名规则只会被 distinct 记成 1 条。
    // 记录优先按 ruleId 匹配；ruleId = 0 是 v2.5 之前的旧记录，回退按规则名匹配。
    val allDone = dueRules.all { rule ->
        attendedRecords.any { rec ->
            if (rec.ruleId > 0L) rec.ruleId == rule.id else rec.ruleName == rule.name
        }
    }

    if (allDone) {
        // 当天所有应打卡规则均已出勤：**全部正常打卡**才是绿色；
        // 只要有外勤改判就是青色 —— 一眼能看出"这天出勤了，但不是正常打卡"
        val hasFieldWork = attendedRecords.any { it.status == CheckStatus.FIELD_WORK.name }
        StatusDot(
            if (hasFieldWork) {
                statusColor(CheckStatus.FIELD_WORK.name, dark)
            } else {
                statusColor(CheckStatus.SUCCESS.name, dark)
            }
        )
        return
    }

    // 只统计"未出勤"的状态：外勤已经算出勤了，不能再当失败原因标在日历上
    val failStatuses = dayRecords
        ?.filter { CheckStatus.isAbsent(it.status) }
        ?.map { it.status }
        ?.distinct() ?: emptyList()

    if (failStatuses.isEmpty()) {
        // 未打卡 / 部分未完成且无失败记录：灰色
        StatusDot(missedColor(dark))
    } else {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            failStatuses.take(2).forEach { status ->
                StatusDot(statusColor(status, dark))
                Spacer(Modifier.width(2.dp))
            }
            if (failStatuses.size > 2) {
                Text(
                    "+",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** 打卡结果点（小） */
private val DOT_SIZE = 6.dp

/** 特殊日（请假 / 放假）标记点：比结果点大一号，扫视时更容易捕捉 */
private val SPECIAL_DOT_SIZE = 9.dp

@Composable
private fun StatusDot(color: Color, size: Dp = DOT_SIZE) {
    Box(Modifier.size(size).clip(CircleShape).background(color))
}

/**
 * 日历图例：把当天各种标记的含义直接标出来，避免"这个点是什么意思"。
 * 正常 / 请假 / 放假三色刻意冷暖对立，图例里再按实际大小绘制，所见即所得。
 */
@Composable
private fun CalendarLegend() {
    val dark = isSystemInDarkTheme()
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        LegendEntry(statusColor(CheckStatus.SUCCESS.name, dark), "正常", DOT_SIZE)
        LegendEntry(leaveColor(dark), "请假", SPECIAL_DOT_SIZE)
        LegendEntry(holidayColor(dark), "放假", SPECIAL_DOT_SIZE)
        LegendEntry(overtimeColor(dark), "加班", DOT_SIZE)
        LegendEntry(missedColor(dark), "未打卡", DOT_SIZE)
    }
}

/** 图例单项：圆点 + 文字（在 Row 里平铺，不需额外容器） */
@Composable
private fun LegendEntry(color: Color, label: String, size: Dp) {
    Box(Modifier.size(size).clip(CircleShape).background(color))
    Spacer(Modifier.width(3.dp))
    Text(
        label,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Spacer(Modifier.width(10.dp))
}

/** 时间段标注（请假/加班）条目卡片 */
@Composable
private fun TimeEntryRow(entry: TimeEntry, onDelete: () -> Unit) {
    val dark = isSystemInDarkTheme()
    val isHoliday = entry.type == TimeEntry.TYPE_HOLIDAY
    val isLeave = entry.type == TimeEntry.TYPE_LEAVE
    val color = when {
        isHoliday -> holidayColor(dark)
        isLeave -> leaveColor(dark)
        else -> overtimeColor(dark)
    }
    Card(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(color)
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        when {
                            isHoliday -> "放假"
                            isLeave -> "请假"
                            else -> "加班"
                        },
                        color = color,
                        fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "${formatHM(entry.startMinute / 60, entry.startMinute % 60)} - " +
                            "${formatHM(entry.endMinute / 60, entry.endMinute % 60)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                entry.note?.let {
                    Text(
                        "备注：$it",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = "删除标注",
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

/** 添加时间段标注对话框：类型（请假/加班）+ 时间段 + 备注 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimeEntryDialog(
    onDismiss: () -> Unit,
    onSave: (type: String, startMinute: Int, endMinute: Int, note: String?) -> Unit
) {
    var type by remember { mutableStateOf(TimeEntry.TYPE_LEAVE) }
    var startHour by remember { mutableIntStateOf(9) }
    var startMinute by remember { mutableIntStateOf(0) }
    var endHour by remember { mutableIntStateOf(12) }
    var endMinute by remember { mutableIntStateOf(0) }
    var noteText by remember { mutableStateOf("") }
    var errorText by remember { mutableStateOf<String?>(null) }
    var showStartPicker by remember { mutableStateOf(false) }
    var showEndPicker by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加请假 / 放假 / 加班时段") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = type == TimeEntry.TYPE_LEAVE,
                        onClick = { type = TimeEntry.TYPE_LEAVE },
                        label = { Text("请假") }
                    )
                    FilterChip(
                        selected = type == TimeEntry.TYPE_HOLIDAY,
                        onClick = { type = TimeEntry.TYPE_HOLIDAY },
                        label = { Text("放假") }
                    )
                    FilterChip(
                        selected = type == TimeEntry.TYPE_OVERTIME,
                        onClick = { type = TimeEntry.TYPE_OVERTIME },
                        label = { Text("加班") }
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = formatHM(startHour, startMinute),
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("开始时间") },
                        trailingIcon = {
                            IconButton(onClick = { showStartPicker = true }) {
                                Icon(Icons.Filled.Schedule, contentDescription = "选择开始时间")
                            }
                        },
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = formatHM(endHour, endMinute),
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("结束时间") },
                        trailingIcon = {
                            IconButton(onClick = { showEndPicker = true }) {
                                Icon(Icons.Filled.Schedule, contentDescription = "选择结束时间")
                            }
                        },
                        modifier = Modifier.weight(1f)
                    )
                }
                OutlinedTextField(
                    value = noteText,
                    onValueChange = { noteText = it },
                    label = { Text("备注（可选）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                errorText?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val start = startHour * 60 + startMinute
                val end = endHour * 60 + endMinute
                errorText = when {
                    start >= end -> "结束时间必须晚于开始时间"
                    else -> null
                }
                if (errorText == null) {
                    onSave(type, start, end, noteText)
                }
            }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )

    if (showStartPicker) {
        val state = rememberTimePickerState(
            initialHour = startHour, initialMinute = startMinute, is24Hour = true
        )
        AlertDialog(
            onDismissRequest = { showStartPicker = false },
            confirmButton = {
                TextButton(onClick = {
                    startHour = state.hour
                    startMinute = state.minute
                    showStartPicker = false
                }) { Text("确定") }
            },
            dismissButton = { TextButton(onClick = { showStartPicker = false }) { Text("取消") } },
            text = { TimePicker(state = state) }
        )
    }

    if (showEndPicker) {
        val state = rememberTimePickerState(
            initialHour = endHour, initialMinute = endMinute, is24Hour = true
        )
        AlertDialog(
            onDismissRequest = { showEndPicker = false },
            confirmButton = {
                TextButton(onClick = {
                    endHour = state.hour
                    endMinute = state.minute
                    showEndPicker = false
                }) { Text("确定") }
            },
            dismissButton = { TextButton(onClick = { showEndPicker = false }) { Text("取消") } },
            text = { TimePicker(state = state) }
        )
    }
}

/**
 * 按日期区间标记特殊日：公司放假（如国庆 1–7 号）或连续多天请假。
 *
 * 一次把区间内每一天写成同一种标记（**覆盖**已有标记），也可一键清除该区间标记；
 * 区间含首尾两天。标记后这些天不再产生任何自动打卡记录。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RangeMarkDialog(
    initialDate: LocalDate,
    onDismiss: () -> Unit,
    onApply: (LocalDate, LocalDate, String) -> Unit,
    onClear: (LocalDate, LocalDate) -> Unit
) {
    var kind by remember { mutableStateOf(LeaveDay.KIND_HOLIDAY) }
    var start by remember { mutableStateOf(initialDate) }
    var end by remember { mutableStateOf(initialDate) }
    // 1 = 正在选开始日期，2 = 正在选结束日期，0 = 未在选择
    var picking by remember { mutableIntStateOf(0) }
    var errorText by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("按日期区间标记") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    "用于公司放假（如国庆 1–7 号）或连续多天请假；区间含首尾两天，" +
                        "标记后这些天不再自动打卡。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = kind == LeaveDay.KIND_HOLIDAY,
                        onClick = { kind = LeaveDay.KIND_HOLIDAY },
                        label = { Text("放假") }
                    )
                    FilterChip(
                        selected = kind == LeaveDay.KIND_LEAVE,
                        onClick = { kind = LeaveDay.KIND_LEAVE },
                        label = { Text("请假") }
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = start.toString(),
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("开始日期") },
                        trailingIcon = {
                            IconButton(onClick = { picking = 1 }) {
                                Icon(Icons.Filled.DateRange, contentDescription = "选择开始日期")
                            }
                        },
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = end.toString(),
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("结束日期") },
                        trailingIcon = {
                            IconButton(onClick = { picking = 2 }) {
                                Icon(Icons.Filled.DateRange, contentDescription = "选择结束日期")
                            }
                        },
                        modifier = Modifier.weight(1f)
                    )
                }
                Text(
                    "共 " + (ChronoUnit.DAYS.between(start, end) + 1).coerceAtLeast(1) + " 天",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
                errorText?.let {
                    Text(
                        it,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                errorText = if (end.isBefore(start)) "结束日期不能早于开始日期" else null
                if (errorText == null) onApply(start, end, kind)
            }) {
                Text(if (kind == LeaveDay.KIND_HOLIDAY) "标记放假" else "标记请假")
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = {
                    errorText = if (end.isBefore(start)) "结束日期不能早于开始日期" else null
                    if (errorText == null) onClear(start, end)
                }) { Text("清除该区间") }
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        }
    )

    if (picking != 0) {
        val pickingStart = picking == 1
        val datePickerState = rememberDatePickerState(
            initialSelectedDateMillis = (if (pickingStart) start else end)
                .atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        )
        DatePickerDialog(
            onDismissRequest = { picking = 0 },
            confirmButton = {
                TextButton(onClick = {
                    datePickerState.selectedDateMillis?.let { millis ->
                        // Material3 DatePicker 以 UTC 零点表示所选日期
                        val picked = Instant.ofEpochMilli(millis)
                            .atZone(ZoneOffset.UTC).toLocalDate()
                        if (pickingStart) start = picked else end = picked
                    }
                    picking = 0
                }) { Text("确定") }
            },
            dismissButton = { TextButton(onClick = { picking = 0 }) { Text("取消") } }
        ) {
            DatePicker(state = datePickerState)
        }
    }
}

/** 导出范围（对话框内选择）：本月 / 指定月份 / 全部 */
private enum class ExportTarget {
    THIS_MONTH,
    SPECIFIC_MONTH,
    ALL
}

/**
 * 月份选择器：年份 ◀ ▶ 翻页 + 12 个月按钮网格，选中月份高亮。
 * 用于导出对话框"指定月份"范围。
 */
@Composable
private fun MonthPicker(month: YearMonth, onMonthChange: (YearMonth) -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            IconButton(onClick = { onMonthChange(month.minusYears(1)) }) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "上一年")
            }
            Text(
                "${month.year} 年",
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.Center,
                fontWeight = FontWeight.SemiBold
            )
            IconButton(onClick = { onMonthChange(month.plusYears(1)) }) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "下一年")
            }
        }
        Spacer(Modifier.height(4.dp))
        (1..12).chunked(4).forEach { rowMonths ->
            Row(Modifier.fillMaxWidth()) {
                rowMonths.forEach { m ->
                    val selected = m == month.monthValue
                    TextButton(
                        onClick = { onMonthChange(month.withMonth(m)) },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            "${m}月",
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                            color = if (selected) MaterialTheme.colorScheme.primary else Color.Unspecified
                        )
                    }
                }
            }
        }
    }
}
