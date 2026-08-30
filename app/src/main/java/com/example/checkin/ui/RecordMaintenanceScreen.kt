package com.example.checkin.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.checkin.data.CheckInRecord
import com.example.checkin.data.CheckStatus
import com.example.checkin.util.formatDateTime
import com.example.checkin.util.formatHM
import com.example.checkin.util.statusColor
import com.example.checkin.util.statusLabel
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.launch

/**
 * 记录维护页（设置页低调入口进入）：
 * 仅用于修正打卡系统产生的录入错误（打卡时间 / 打卡地点），
 * 状态、规则、备注、照片等一律不可修改；修改后统计与日历随之变化。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordMaintenanceScreen(
    viewModel: CheckInViewModel,
    onBack: () -> Unit
) {
    val records by viewModel.records.collectAsState()
    var editing by remember { mutableStateOf<CheckInRecord?>(null) }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("记录维护") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                windowInsets = WindowInsets(0.dp)
            )
        },
        contentWindowInsets = WindowInsets(0.dp)
    ) { padding ->
        if (records.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text(
                    "暂无打卡记录",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(records, key = { it.id }) { record ->
                    RecordMaintenanceRow(record = record, onClick = { editing = record })
                }
            }
        }
    }

    editing?.let { record ->
        EditRecordDialog(
            record = record,
            viewModel = viewModel,
            onDismiss = { editing = null },
            onSaved = {
                viewModel.updateRecord(it)
                editing = null
            }
        )
    }
}

/** 维护页单条记录行：状态点 + 时间 + 规则 + 地址，点击进入修正 */
@Composable
private fun RecordMaintenanceRow(
    record: CheckInRecord,
    onClick: () -> Unit
) {
    val color = statusColor(record.status)
    Card(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(12.dp),
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
                        formatDateTime(record.timestamp),
                        fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        statusLabel(record.status),
                        color = color,
                        style = MaterialTheme.typography.bodySmall
                    )
                    record.ruleName?.let {
                        Spacer(Modifier.width(8.dp))
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                if (record.latitude != 0.0 || record.longitude != 0.0) {
                    Text(
                        "📍 %.6f, %.6f".format(record.latitude, record.longitude),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                record.address?.let {
                    Text(
                        "地址：$it",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

/** 修正对话框：打卡时间（日期+时刻）、打卡地点（经纬度+地址） */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditRecordDialog(
    record: CheckInRecord,
    viewModel: CheckInViewModel,
    onDismiss: () -> Unit,
    onSaved: (CheckInRecord) -> Unit
) {
    val scope = rememberCoroutineScope()
    val zoned = Instant.ofEpochMilli(record.timestamp).atZone(ZoneId.systemDefault())
    val originalDate = zoned.toLocalDate()
    val originalTime = zoned.toLocalTime()

    var date by remember { mutableStateOf(originalDate) }
    var time by remember { mutableStateOf(originalTime) }
    var latText by remember {
        mutableStateOf(
            if (record.latitude == 0.0 && record.longitude == 0.0) "" else "%.6f".format(record.latitude)
        )
    }
    var lngText by remember {
        mutableStateOf(
            if (record.longitude == 0.0 && record.latitude == 0.0) "" else "%.6f".format(record.longitude)
        )
    }
    var addrText by remember { mutableStateOf(record.address ?: "") }
    var showDatePicker by remember { mutableStateOf(false) }
    var showTimePicker by remember { mutableStateOf(false) }
    var errorText by remember { mutableStateOf<String?>(null) }
    val currentLocation by viewModel.currentLocation.collectAsState()

    val originalLat = record.latitude
    val originalLng = record.longitude

    fun buildNewTimestamp(): Long =
        LocalDateTime.of(date, time)
            .atZone(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("修正记录") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "仅用于修正打卡时间与地点；若修正后时间与地点均在规则内，状态将自动变为成功；修改后统计与日历展示随之变化。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    "当前：${statusLabel(record.status)}" +
                        (record.ruleName?.let { " · $it" } ?: ""),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )

                OutlinedTextField(
                    value = date.toString(),
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("打卡日期") },
                    trailingIcon = {
                        IconButton(onClick = { showDatePicker = true }) {
                            Icon(Icons.Filled.CalendarMonth, contentDescription = "选择日期")
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = formatHM(time.hour, time.minute),
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("打卡时间") },
                    trailingIcon = {
                        IconButton(onClick = { showTimePicker = true }) {
                            Icon(Icons.Filled.Schedule, contentDescription = "选择时间")
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = latText,
                        onValueChange = { latText = it },
                        label = { Text("纬度") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = lngText,
                        onValueChange = { lngText = it },
                        label = { Text("经度") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }
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
                    value = addrText,
                    onValueChange = { addrText = it },
                    label = { Text("地址（坐标变化时保存会自动重新解析）") },
                    minLines = 1,
                    maxLines = 3,
                    modifier = Modifier.fillMaxWidth()
                )
                errorText?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val lat = latText.trim().let { if (it.isEmpty()) 0.0 else it.toDoubleOrNull() }
                val lng = lngText.trim().let { if (it.isEmpty()) 0.0 else it.toDoubleOrNull() }
                errorText = when {
                    lat == null -> "纬度无效（留空表示无定位）"
                    lng == null -> "经度无效（留空表示无定位）"
                    lat != 0.0 && lat !in -90.0..90.0 -> "纬度无效（范围 -90 ~ 90）"
                    lng != 0.0 && lng !in -180.0..180.0 -> "经度无效（范围 -180 ~ 180）"
                    else -> null
                }
                if (errorText == null && lat != null && lng != null) {
                    val coordsChanged = lat != originalLat || lng != originalLng
                    val newTimestamp = buildNewTimestamp()
                    scope.launch {
                        // 坐标变化时自动重新逆地理编码；失败则沿用用户填写的地址
                        val address = if (coordsChanged) {
                            viewModel.addressFor(lat, lng) ?: addrText.trim().ifBlank { null }
                        } else {
                            addrText.trim().ifBlank { null }
                        }
                        val updated = record.copy(
                            timestamp = newTimestamp,
                            latitude = lat,
                            longitude = lng,
                            address = address
                        )
                        // 时间+地点均在规则内时，自动把"时间外/地点外"升级为成功
                        onSaved(viewModel.reEvaluateStatus(updated))
                    }
                }
            }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )

    if (showDatePicker) {
        val datePickerState = rememberDatePickerState(
            initialSelectedDateMillis = originalDate
                .atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    datePickerState.selectedDateMillis?.let { millis ->
                        // Material3 DatePicker 以 UTC 零点表示所选日期
                        date = Instant.ofEpochMilli(millis)
                            .atZone(ZoneOffset.UTC).toLocalDate()
                    }
                    showDatePicker = false
                }) { Text("确定") }
            },
            dismissButton = { TextButton(onClick = { showDatePicker = false }) { Text("取消") } }
        ) {
            DatePicker(state = datePickerState)
        }
    }

    if (showTimePicker) {
        val timePickerState = rememberTimePickerState(
            initialHour = originalTime.hour,
            initialMinute = originalTime.minute,
            is24Hour = true
        )
        AlertDialog(
            onDismissRequest = { showTimePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    // 只改小时/分钟，保留原秒
                    time = LocalTime.of(timePickerState.hour, timePickerState.minute)
                        .withSecond(originalTime.second)
                    showTimePicker = false
                }) { Text("确定") }
            },
            dismissButton = { TextButton(onClick = { showTimePicker = false }) { Text("取消") } },
            text = { TimePicker(state = timePickerState) }
        )
    }
}
