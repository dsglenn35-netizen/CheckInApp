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
import com.example.checkin.data.CheckInSite
import com.example.checkin.util.ShiftPattern
import com.example.checkin.util.FlexibleWork
import com.example.checkin.util.formatDaysOfWeek
import com.example.checkin.util.formatHM
import com.example.checkin.util.formatMinuteOfDay
import java.time.LocalDate

/** 规则管理页：规则列表 + 添加/编辑/删除 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RulesScreen(viewModel: CheckInViewModel) {
    val rules by viewModel.rules.collectAsState()
    val allSites by viewModel.sites.collectAsState()
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
                        siteCount = allSites.count { it.ruleId == rule.id },
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
            initialSites = editingRule?.let { r -> allSites.filter { it.ruleId == r.id } }.orEmpty(),
            currentLocation = currentLocation,
            onDismiss = { showEditor = false },
            onSave = { rule, sites ->
                viewModel.saveRuleWithSites(rule, sites)
                showEditor = false
            }
        )
    }
}

@Composable
private fun RuleCard(
    rule: CheckInRule,
    siteCount: Int,
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
                val shift = ShiftPattern.parse(rule.shiftPattern)
                val dayText = if (shift.kind == ShiftPattern.Kind.ROTATION) {
                    "${formatDaysOfWeek(rule.daysOfWeek)}（已改为轮班：${shift.label}）"
                } else {
                    formatDaysOfWeek(rule.daysOfWeek)
                }
                Text(dayText, style = MaterialTheme.typography.bodyMedium)
            }
            if (siteCount > 0) {
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
                        "另有 $siteCount 个打卡点",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
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

/** 分钟 → 小时输入框文本（整点不带小数，如 8；7.5 小时保留小数） */
private fun minutesToHoursText(minutes: Int): String =
    if (minutes % 60 == 0) (minutes / 60).toString() else (minutes / 60.0).toString()

/** 小时输入框文本 → 分钟；非法或超出合理范围时返回 null */
private fun workHoursToMinutes(text: String): Int? {
    val hours = text.trim().toDoubleOrNull() ?: return null
    val target = FlexibleWork.sanitizeTarget(Math.round(hours * 60).toInt())
    return if (target == FlexibleWork.NO_TARGET) null else target
}

/**
 * 添加/编辑规则对话框：
 * 名称、时间段、主地点（经纬度+半径+WiFi）、附加打卡点（可多个）、
 * 班制（每周固定 / 上N休M 轮转）、自由工时制、考勤应到应离时刻。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RuleEditorDialog(
    initial: CheckInRule?,
    initialSites: List<CheckInSite>,
    currentLocation: Location?,
    onDismiss: () -> Unit,
    onSave: (CheckInRule, List<CheckInSite>) -> Unit
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
    var wifiText by remember { mutableStateOf(initial?.wifiSsid ?: "") }
    var requireCheckOut by remember { mutableStateOf(initial?.requireCheckOut ?: false) }
    var flexible by remember { mutableStateOf(initial?.flexible ?: false) }
    var workHoursText by remember {
        mutableStateOf(
            minutesToHoursText(
                initial?.requiredWorkMinutes
                    ?.takeIf { it > 0 }
                    ?: FlexibleWork.DEFAULT_TARGET_MINUTES
            )
        )
    }

    // 初始班制
    val initialShift = remember { ShiftPattern.parse(initial?.shiftPattern) }
    var rotationMode by remember { mutableStateOf(initialShift.kind == ShiftPattern.Kind.ROTATION) }
    var workDays by remember { mutableIntStateOf(initialShift.workDays.coerceAtLeast(1)) }
    var restDays by remember { mutableIntStateOf(initialShift.restDays) }
    var anchorDate by remember { mutableStateOf(initialShift.anchor ?: LocalDate.now()) }

    // 附加打卡点（内存中编辑，保存时整体替换）
    var sites by remember { mutableStateOf(initialSites) }

    // 应到 / 应离时刻（-1 表示不判定）
    var requiredStart by remember { mutableIntStateOf(initial?.requiredStartMinute ?: -1) }
    var requiredEnd by remember { mutableIntStateOf(initial?.requiredEndMinute ?: -1) }
    var showRequiredStartPicker by remember { mutableStateOf(false) }
    var showRequiredEndPicker by remember { mutableStateOf(false) }

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
                // 需要下班卡：把时间窗对半分为上班卡 / 下班卡，才能算出真实在岗时长
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("需要下班卡", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            if (!requireCheckOut) {
                                "关闭时：整个时间窗打一次卡即算完成，得不到下班时刻"
                            } else {
                                val startMin = startHour * 60 + startMinute
                                val endMin = endHour * 60 + endMinute
                                val span = if (endMin > startMin) endMin - startMin
                                else endMin - startMin + 24 * 60
                                val mid = (startMin + span / 2) % (24 * 60)
                                "上班卡：本窗口开始起；下班卡：" + formatMinuteOfDay(mid) +
                                    " 起。各记一次，可算出在岗时长"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = requireCheckOut,
                        onCheckedChange = { requireCheckOut = it }
                    )
                }
                // 自由工时制：不判迟到早退，只看当日工时是否达标
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("自由工时制", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "开启后不判迟到 / 早退，只考察当日工时是否达到目标；" +
                                "时间窗变成「允许打卡的时段」，自动打卡仍只在此时段内开定位",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(checked = flexible, onCheckedChange = { flexible = it })
                }
                if (flexible) {
                    OutlinedTextField(
                        value = workHoursText,
                        onValueChange = { workHoursText = it },
                        label = { Text("每日应工作（小时）") },
                        placeholder = { Text("如 8 或 7.5") },
                        singleLine = true
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
                OutlinedTextField(
                    value = wifiText,
                    onValueChange = { wifiText = it },
                    label = { Text("主地点 WiFi 名称（可选，多个用逗号分隔）") },
                    singleLine = true,
                    supportingText = {
                        Text("定位漂移或室内无信号时，连上该 WiFi 即视为在该地点")
                    },
                    modifier = Modifier.fillMaxWidth()
                )

                // ---------- 附加打卡点 ----------
                Text("附加打卡点（同一规则可多个地点）", style = MaterialTheme.typography.bodyMedium)
                if (sites.isEmpty()) {
                    Text(
                        "暂无。公司有两个门 / 多栋楼时可在此添加",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                sites.forEachIndexed { index, site ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    "打卡点 ${index + 1}",
                                    style = MaterialTheme.typography.labelLarge,
                                    modifier = Modifier.weight(1f)
                                )
                                IconButton(onClick = { sites = sites.filterIndexed { i, _ -> i != index } }) {
                                    Icon(
                                        Icons.Filled.Delete,
                                        contentDescription = "删除该打卡点",
                                        tint = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                            OutlinedTextField(
                                value = site.name,
                                onValueChange = { v -> sites = sites.toMutableList().also { it[index] = site.copy(name = v) } },
                                label = { Text("地点名称") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedTextField(
                                    value = "%.6f".format(site.latitude),
                                    onValueChange = { v ->
                                        v.toDoubleOrNull()?.let { d ->
                                            sites = sites.toMutableList().also { it[index] = site.copy(latitude = d) }
                                        }
                                    },
                                    label = { Text("纬度") },
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                    modifier = Modifier.weight(1f)
                                )
                                OutlinedTextField(
                                    value = "%.6f".format(site.longitude),
                                    onValueChange = { v ->
                                        v.toDoubleOrNull()?.let { d ->
                                            sites = sites.toMutableList().also { it[index] = site.copy(longitude = d) }
                                        }
                                    },
                                    label = { Text("经度") },
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                    modifier = Modifier.weight(1f)
                                )
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedTextField(
                                    value = site.radiusMeters.toInt().toString(),
                                    onValueChange = { v ->
                                        v.toDoubleOrNull()?.let { d ->
                                            sites = sites.toMutableList().also { it[index] = site.copy(radiusMeters = d) }
                                        }
                                    },
                                    label = { Text("半径（米）") },
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                    modifier = Modifier.weight(1f)
                                )
                                OutlinedTextField(
                                    value = site.wifiSsid ?: "",
                                    onValueChange = { v ->
                                        sites = sites.toMutableList().also {
                                            it[index] = site.copy(wifiSsid = v.ifBlank { null })
                                        }
                                    },
                                    label = { Text("WiFi（可选）") },
                                    singleLine = true,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }
                }
                TextButton(
                    onClick = {
                        val loc = currentLocation
                        val baseRadius = radiusText.toDoubleOrNull() ?: 1000.0
                        sites = sites + CheckInSite(
                            ruleId = initial?.id ?: 0L,
                            name = "打卡点${sites.size + 1}",
                            latitude = loc?.latitude ?: 0.0,
                            longitude = loc?.longitude ?: 0.0,
                            radiusMeters = baseRadius,
                            wifiSsid = null
                        )
                    }
                ) {
                    Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(if (currentLocation != null) "添加当前位置为打卡点" else "添加打卡点")
                }

                // ---------- 班制 ----------
                Text("班制", style = MaterialTheme.typography.bodyMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = !rotationMode,
                        onClick = { rotationMode = false },
                        label = { Text("每周固定") }
                    )
                    FilterChip(
                        selected = rotationMode,
                        onClick = { rotationMode = true },
                        label = { Text("轮班（上N休M）") }
                    )
                }
                if (rotationMode) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = workDays.toString(),
                            onValueChange = { v -> v.toIntOrNull()?.let { if (it in 1..60) workDays = it } },
                            label = { Text("上几天") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = restDays.toString(),
                            onValueChange = { v -> v.toIntOrNull()?.let { if (it in 0..60) restDays = it } },
                            label = { Text("休几天") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        ShiftPattern.COMMON_ROTATIONS.forEach { (w, r) ->
                            FilterChip(
                                selected = workDays == w && restDays == r,
                                onClick = { workDays = w; restDays = r },
                                label = { Text("上${w}休${r}") }
                            )
                        }
                    }
                    Text(
                        "周期起点：${anchorDate}（该日视为上班第 1 天）",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Row {
                        TextButton(onClick = { anchorDate = anchorDate.minusDays(1) }) { Text("◀ 前一天") }
                        TextButton(onClick = { anchorDate = LocalDate.now() }) { Text("今天") }
                        TextButton(onClick = { anchorDate = anchorDate.plusDays(1) }) { Text("后一天 ▶") }
                    }
                } else {
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
                }

                // ---------- 考勤应到/应离（仅用于报表迟到早退判定） ----------
                // 自由工时制不判迟到/早退，这两项直接隐藏：
                // 让用户设一个永远不会生效的值，比不给这个入口更糟。
                if (!flexible) {
                    Text(
                        "考勤应到 / 应离时刻（可选，仅用于报表迟到早退）",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = if (requiredStart < 0) "未设置" else formatMinuteOfDay(requiredStart),
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("应到时刻") },
                            trailingIcon = {
                                IconButton(onClick = { showRequiredStartPicker = true }) {
                                    Icon(Icons.Filled.Schedule, contentDescription = "选择应到时刻")
                                }
                            },
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = if (requiredEnd < 0) "未设置" else formatMinuteOfDay(requiredEnd),
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("应离时刻") },
                            trailingIcon = {
                                IconButton(onClick = { showRequiredEndPicker = true }) {
                                    Icon(Icons.Filled.Schedule, contentDescription = "选择应离时刻")
                                }
                            },
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Row {
                        TextButton(onClick = { requiredStart = -1; requiredEnd = -1 }) {
                            Text("清除应到/应离")
                        }
                    }
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
                val badSite = sites.firstOrNull {
                    it.name.isBlank() || it.latitude !in -90.0..90.0 ||
                        it.longitude !in -180.0..180.0 || it.radiusMeters <= 0
                }
                val sameMinute = startHour * 60 + startMinute == endHour * 60 + endMinute
                errorText = when {
                    name.isBlank() -> "请填写规则名称"
                    // 开始与结束相同 → 窗口为空集，isWithinTime 恒为 false、
                    // nextBoundaryMillis 也会跳过该规则，规则会静默地永不生效
                    sameMinute -> "开始时间与结束时间不能相同，否则该规则永远不会生效"
                    lat == null || lat !in -90.0..90.0 -> "纬度无效（范围 -90 ~ 90）"
                    lng == null || lng !in -180.0..180.0 -> "经度无效（范围 -180 ~ 180）"
                    radius == null || radius <= 0 -> "允许打卡范围必须大于 0"
                    badSite != null -> "打卡点「${badSite.name.ifBlank { "未命名" }}」的坐标或半径无效"
                    rotationMode && workDays < 1 -> "轮班制「上几天」至少为 1"
                    flexible && workHoursToMinutes(workHoursText) == null ->
                        "每日应工作请填 0.5 ~ 24 之间的小时数（如 8 或 7.5）"
                    else -> null
                }
                if (errorText == null && lat != null && lng != null && radius != null) {
                    val shift = if (rotationMode) {
                        ShiftPattern.rotation(workDays, restDays, anchorDate)
                    } else {
                        ShiftPattern.weekly()
                    }
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
                            daysOfWeek = daysMask,
                            shiftPattern = shift.serialize(),
                            // 自由工时制不使用应到/应离（那两项只服务于迟到早退判定）
                            requiredStartMinute = if (flexible) -1 else requiredStart,
                            requiredEndMinute = if (flexible) -1 else requiredEnd,
                            flexible = flexible,
                            requiredWorkMinutes = if (flexible) {
                                workHoursToMinutes(workHoursText)
                                    ?: FlexibleWork.DEFAULT_TARGET_MINUTES
                            } else {
                                FlexibleWork.NO_TARGET
                            },
                            wifiSsid = wifiText.trim().ifBlank { null },
                            requireCheckOut = requireCheckOut
                        ),
                        sites
                    )
                }
            }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )

    if (showRequiredStartPicker) {
        val state = rememberTimePickerState(
            initialHour = if (requiredStart >= 0) requiredStart / 60 else 9,
            initialMinute = if (requiredStart >= 0) requiredStart % 60 else 0,
            is24Hour = true
        )
        AlertDialog(
            onDismissRequest = { showRequiredStartPicker = false },
            title = { Text("应到时刻") },
            confirmButton = {
                TextButton(onClick = {
                    requiredStart = state.hour * 60 + state.minute
                    showRequiredStartPicker = false
                }) { Text("确定") }
            },
            dismissButton = { TextButton(onClick = { showRequiredStartPicker = false }) { Text("取消") } },
            text = { TimePicker(state = state) }
        )
    }

    if (showRequiredEndPicker) {
        val state = rememberTimePickerState(
            initialHour = if (requiredEnd >= 0) requiredEnd / 60 else 18,
            initialMinute = if (requiredEnd >= 0) requiredEnd % 60 else 0,
            is24Hour = true
        )
        AlertDialog(
            onDismissRequest = { showRequiredEndPicker = false },
            title = { Text("应离时刻") },
            confirmButton = {
                TextButton(onClick = {
                    requiredEnd = state.hour * 60 + state.minute
                    showRequiredEndPicker = false
                }) { Text("确定") }
            },
            dismissButton = { TextButton(onClick = { showRequiredEndPicker = false }) { Text("取消") } },
            text = { TimePicker(state = state) }
        )
    }

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
