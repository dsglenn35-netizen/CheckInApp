package com.example.checkin.ui

import android.location.Location
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Rule
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.checkin.data.CheckInRule
import com.example.checkin.util.formatDaysOfWeek
import com.example.checkin.util.formatHM

/** 规则管理页：规则列表 + 添加/编辑/删除 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RulesScreen(viewModel: CheckInViewModel) {
    val rules by viewModel.rules.collectAsState()
    val currentLocation by viewModel.currentLocation.collectAsState()
    var editingRule by remember { mutableStateOf<CheckInRule?>(null) }
    var showEditor by remember { mutableStateOf(false) }

    Scaffold(
        // 外层导航已处理系统栏内边距，内层不再重复预留，避免顶部空白
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("打卡规则") },
                windowInsets = WindowInsets(0.dp)
            )
        },
        contentWindowInsets = WindowInsets(0.dp),
        floatingActionButton = {
            FloatingActionButton(onClick = {
                editingRule = null
                showEditor = true
            }) {
                Icon(Icons.Filled.Add, contentDescription = "添加规则")
            }
        }
    ) { padding ->
        if (rules.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.Rule,
                    contentDescription = null,
                    modifier = Modifier.size(64.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))
                Text("还没有打卡规则", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                Text(
                    "点击右下角 + 添加规则\n设置打卡时间段与允许打卡的地点范围",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.padding(padding),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(rules, key = { it.id }) { rule ->
                    RuleCard(
                        rule = rule,
                        onToggle = { viewModel.updateRule(rule.copy(enabled = !rule.enabled)) },
                        onEdit = {
                            editingRule = rule
                            showEditor = true
                        },
                        onDelete = { viewModel.deleteRule(rule) }
                    )
                }
            }
        }
    }

    if (showEditor) {
        RuleEditorDialog(
            initial = editingRule,
            currentLocation = currentLocation,
            onDismiss = { showEditor = false },
            onSave = { rule ->
                if (editingRule != null) viewModel.updateRule(rule) else viewModel.addRule(rule)
                showEditor = false
            }
        )
    }
}

@Composable
private fun RuleCard(
    rule: CheckInRule,
    onToggle: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    rule.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                Switch(checked = rule.enabled, onCheckedChange = { onToggle() })
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.Schedule,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    "${formatHM(rule.startHour, rule.startMinute)} - ${formatHM(rule.endHour, rule.endMinute)}",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.Place,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    "%.6f, %.6f（半径 %d 米）".format(rule.latitude, rule.longitude, rule.radiusMeters.toInt()),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.CalendarMonth,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    formatDaysOfWeek(rule.daysOfWeek),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            Spacer(Modifier.height(4.dp))
            Row {
                TextButton(onClick = onEdit) {
                    Icon(Icons.Filled.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("编辑")
                }
                TextButton(onClick = onDelete) {
                    Icon(
                        Icons.Filled.Delete,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.error
                    )
                    Spacer(Modifier.width(4.dp))
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

/** 添加/编辑规则对话框：名称、时间段（时间选择器）、地点（经纬度+半径） */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RuleEditorDialog(
    initial: CheckInRule?,
    currentLocation: Location?,
    onDismiss: () -> Unit,
    onSave: (CheckInRule) -> Unit
) {
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var startHour by remember { mutableIntStateOf(initial?.startHour ?: 9) }
    var startMinute by remember { mutableIntStateOf(initial?.startMinute ?: 0) }
    var endHour by remember { mutableIntStateOf(initial?.endHour ?: 18) }
    var endMinute by remember { mutableIntStateOf(initial?.endMinute ?: 0) }
    var latText by remember { mutableStateOf(initial?.latitude?.toString() ?: "") }
    var lngText by remember { mutableStateOf(initial?.longitude?.toString() ?: "") }
    var radiusText by remember { mutableStateOf(initial?.radiusMeters?.toInt()?.toString() ?: "1000") }
    var errorText by remember { mutableStateOf<String?>(null) }
    var showStartPicker by remember { mutableStateOf(false) }
    var showEndPicker by remember { mutableStateOf(false) }
    var daysMask by remember { mutableIntStateOf(initial?.daysOfWeek ?: 127) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "添加规则" else "编辑规则") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("规则名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
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
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = latText,
                        onValueChange = { latText = it },
                        label = { Text("纬度") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = lngText,
                        onValueChange = { lngText = it },
                        label = { Text("经度") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.weight(1f)
                    )
                }
                OutlinedTextField(
                    value = radiusText,
                    onValueChange = { radiusText = it },
                    label = { Text("允许打卡范围（米）") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth()
                )
                TextButton(
                    onClick = {
                        currentLocation?.let { loc ->
                            latText = "%.6f".format(loc.latitude)
                            lngText = "%.6f".format(loc.longitude)
                        }
                    },
                    enabled = currentLocation != null
                ) {
                    Icon(Icons.Filled.MyLocation, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("使用当前位置填充")
                }
                Text("生效星期", style = MaterialTheme.typography.bodyMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf("一", "二", "三", "四").forEachIndexed { i, label ->
                        FilterChip(
                            selected = daysMask and (1 shl i) != 0,
                            onClick = { daysMask = daysMask xor (1 shl i) },
                            label = { Text(label) }
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf("五", "六", "日").forEachIndexed { i, label ->
                        FilterChip(
                            selected = daysMask and (1 shl (i + 4)) != 0,
                            onClick = { daysMask = daysMask xor (1 shl (i + 4)) },
                            label = { Text(label) }
                        )
                    }
                }
                Row {
                    TextButton(onClick = { daysMask = 0b1111111 }) { Text("每天") }
                    TextButton(onClick = { daysMask = 0b0011111 }) { Text("工作日") }
                    TextButton(onClick = { daysMask = 0b1100000 }) { Text("周末") }
                }
                errorText?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val lat = latText.toDoubleOrNull()
                val lng = lngText.toDoubleOrNull()
                val radius = radiusText.toDoubleOrNull()
                errorText = when {
                    name.isBlank() -> "请填写规则名称"
                    lat == null || lat !in -90.0..90.0 -> "纬度无效（范围 -90 ~ 90）"
                    lng == null || lng !in -180.0..180.0 -> "经度无效（范围 -180 ~ 180）"
                    radius == null || radius <= 0 -> "允许打卡范围必须大于 0"
                    else -> null
                }
                if (errorText == null && lat != null && lng != null && radius != null) {
                    onSave(
                        CheckInRule(
                            id = initial?.id ?: 0,
                            name = name.trim(),
                            startHour = startHour,
                            startMinute = startMinute,
                            endHour = endHour,
                            endMinute = endMinute,
                            latitude = lat,
                            longitude = lng,
                            radiusMeters = radius,
                            enabled = initial?.enabled ?: true,
                            daysOfWeek = daysMask
                        )
                    )
                }
            }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )

    if (showStartPicker) {
        val state = rememberTimePickerState(
            initialHour = startHour,
            initialMinute = startMinute,
            is24Hour = true
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
            initialHour = endHour,
            initialMinute = endMinute,
            is24Hour = true
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
