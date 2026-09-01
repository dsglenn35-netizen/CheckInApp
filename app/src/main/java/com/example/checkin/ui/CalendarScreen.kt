package com.example.checkin.ui

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
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
import com.example.checkin.data.TimeEntry
import com.example.checkin.ui.RecordRow
import com.example.checkin.util.CheckInValidator
import com.example.checkin.util.ExportFormat
import com.example.checkin.util.ExportScope
import com.example.checkin.util.formatHM
import com.example.checkin.util.statusColor
import com.example.checkin.util.toLocalDate
import java.time.LocalDate
import java.time.YearMonth
import java.util.Calendar

/** 当天有生效规则但完全未打卡时的标记颜色（灰） */
private val MissedColor = Color(0xFF9E9E9E)

/** 请假标记颜色（蓝） */
private val LeaveBlue = Color(0xFF1E88E5)

/** 加班标记颜色（珊瑚橙） */
private val OvertimeColor = Color(0xFFFF7043)

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
    val leaveDates = remember(leaveDays) { leaveDays.map { it.date }.toSet() }
    val entriesByDay = remember(timeEntries) { timeEntries.groupBy { it.date } }
    var showTimeEntryDialog by remember { mutableStateOf(false) }
    val monthStats = remember(currentMonth, records) {
        val inMonth = records.filter {
            val d = it.timestamp.toLocalDate()
            d.year == currentMonth.year && d.monthValue == currentMonth.monthValue
        }
        val success = inMonth.count { it.status == CheckStatus.SUCCESS.name }
        val days = inMonth
            .filter { it.status == CheckStatus.SUCCESS.name }
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
                            hasLeaveRange = dateKey?.let { k ->
                                entriesByDay[k]?.any { it.type == TimeEntry.TYPE_LEAVE }
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
        val selectedIsLeave = selectedDate.toString() in leaveDates
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "${selectedDate.year}年${selectedDate.monthValue}月${selectedDate.dayOfMonth}日" +
                    (if (selectedIsLeave) "（请假）" else "") +
                    "打卡记录（${dayRecords.size}）",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = { viewModel.toggleLeaveDay(selectedDate) }) {
                Text(
                    if (selectedIsLeave) "取消请假" else "标记请假",
                    color = if (selectedIsLeave) MaterialTheme.colorScheme.onSurfaceVariant
                    else MaterialTheme.colorScheme.primary
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End
        ) {
            TextButton(onClick = { showTimeEntryDialog = true }) {
                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("请假/加班时段")
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
                    onMarkLeave = { viewModel.markRecordAsLeave(record) }
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
}

@Composable
private fun MonthDayCell(
    modifier: Modifier = Modifier,
    date: LocalDate?,
    dayRecords: List<CheckInRecord>?,
    rules: List<CheckInRule>,
    isLeave: Boolean,
    hasLeaveRange: Boolean,
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
                DayMarker(date, dayRecords, rules, isLeave, hasLeaveRange, hasOvertime)
            }
        }
    }
}

/**
 * 日历格子标记：
 * - 请假（全天或时段）→ 蓝色标记（优先）；
 * - 加班 → 珊瑚橙标记；
 * - 当天无生效规则 → 不显示；
 * - 当天所有生效规则都成功打卡 → 绿色；
 * - 有生效规则但未全部完成：
 *   - 当天无任何记录 → 灰色（未打卡）；
 *   - 有失败记录 → 按失败原因显示不同颜色（最多 2 个，超出显示 +）
 */
@Composable
private fun DayMarker(
    date: LocalDate,
    dayRecords: List<CheckInRecord>?,
    rules: List<CheckInRule>,
    isLeave: Boolean,
    hasLeaveRange: Boolean,
    hasOvertime: Boolean
) {
    if (isLeave || hasLeaveRange) {
        // 请假（全天或时段）：蓝色
        StatusDot(LeaveBlue)
        return
    }
    if (hasOvertime) {
        // 加班：珊瑚橙
        StatusDot(OvertimeColor)
        return
    }

    // 当天生效规则数：只在日期或规则变化时重算（42 个格子 × 每次重组都会调用）
    val expected = remember(date, rules) {
        val cal = Calendar.getInstance().apply {
            clear()
            set(date.year, date.monthValue - 1, date.dayOfMonth)
        }
        rules.count { it.enabled && CheckInValidator.isActiveOnDay(it, cal) }
    }
    if (expected == 0) return

    val doneRules = dayRecords
        ?.filter { it.status == CheckStatus.SUCCESS.name }
        ?.mapNotNull { it.ruleName }
        ?.distinct()
        ?.size ?: 0

    if (doneRules >= expected) {
        // 全部规则都成功：绿色
        StatusDot(statusColor(CheckStatus.SUCCESS.name))
        return
    }

    val failStatuses = dayRecords
        ?.filter { it.status != CheckStatus.SUCCESS.name }
        ?.map { it.status }
        ?.distinct() ?: emptyList()

    if (failStatuses.isEmpty()) {
        // 未打卡 / 部分未完成且无失败记录：灰色
        StatusDot(MissedColor)
    } else {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            failStatuses.take(2).forEach { status ->
                StatusDot(statusColor(status))
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

@Composable
private fun StatusDot(color: Color, size: Dp = 6.dp) {
    Box(Modifier.size(size).clip(CircleShape).background(color))
}

/** 时间段标注（请假/加班）条目卡片 */
@Composable
private fun TimeEntryRow(entry: TimeEntry, onDelete: () -> Unit) {
    val isLeave = entry.type == TimeEntry.TYPE_LEAVE
    val color = if (isLeave) LeaveBlue else OvertimeColor
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
                        if (isLeave) "请假" else "加班",
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
        title = { Text("添加请假/加班时段") },
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
