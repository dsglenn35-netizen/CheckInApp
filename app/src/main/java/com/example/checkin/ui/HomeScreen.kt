package com.example.checkin.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
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
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.example.checkin.data.CheckStatus
import com.example.checkin.data.TimeEntry
import com.example.checkin.ui.RecordRow
import com.example.checkin.util.computeStats
import com.example.checkin.util.formatDateTime
import com.example.checkin.util.statusColor
import com.example.checkin.util.statusMessage
import com.example.checkin.util.toLocalDate
import java.io.File
import java.time.LocalDate
import kotlinx.coroutines.delay

/** 打卡主页：实时时间、当前位置、打卡按钮、上次结果、今日记录 */
@Composable
fun HomeScreen(viewModel: CheckInViewModel) {
    val context = LocalContext.current
    val records by viewModel.records.collectAsState()
    val currentLocation by viewModel.currentLocation.collectAsState()
    val lastResult by viewModel.lastResult.collectAsState()
    val isChecking by viewModel.isChecking.collectAsState()
    val autoEnabled by viewModel.autoEnabled.collectAsState()
    val photoEnabled by viewModel.photoEnabled.collectAsState()
    val leaveDays by viewModel.leaveDays.collectAsState()
    val timeEntries by viewModel.timeEntries.collectAsState()

    val hasPermission = ContextCompat.checkSelfPermission(
        context, Manifest.permission.ACCESS_FINE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED

    var pendingAutoEnable by remember { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            viewModel.refreshLocation()
            if (pendingAutoEnable) {
                pendingAutoEnable = false
                viewModel.setAutoCheckIn(true)
            }
        }
    }
    val notifPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* 通知权限非必需，不影响自动打卡功能 */ }

    // 拍照打卡：先打开相机拍照，再执行打卡
    var pendingPhotoFile by remember { mutableStateOf<File?>(null) }
    val takePictureLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { success ->
        val file = pendingPhotoFile
        pendingPhotoFile = null
        viewModel.checkIn(photoPath = if (success) file?.absolutePath else null)
    }

    LaunchedEffect(Unit) {
        if (!hasPermission) {
            permissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    val today = LocalDate.now()
    val todayLeave = leaveDays.any { it.date == today.toString() } ||
        timeEntries.any { it.date == today.toString() && it.type == TimeEntry.TYPE_LEAVE }
    val todayRecords = records
        .filter { it.timestamp.toLocalDate() == today }
        .sortedByDescending { it.timestamp }

    val stats = remember(records) { computeStats(records) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        ClockText()

        Spacer(Modifier.height(16.dp))

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text(
                    "当前位置",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(8.dp))
                val loc = currentLocation
                if (loc != null) {
                    Text(
                        "纬度 %.6f\n经度 %.6f".format(loc.latitude, loc.longitude),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        "定位精度 ±${loc.accuracy.toInt()} 米",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Text(
                        "未获取到定位",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (!hasPermission) {
                        Text(
                            "未授予定位权限，无法校验打卡地点",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { viewModel.refreshLocation() }) {
                        Icon(
                            Icons.Filled.MyLocation,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text("刷新定位")
                    }
                    if (!hasPermission) {
                        TextButton(
                            onClick = { permissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION) }
                        ) {
                            Text("授予权限", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        Card(Modifier.fillMaxWidth()) {
            Column {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "自动打卡",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            "满足时间段与地点后自动记录打卡，无需手动点击",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = autoEnabled,
                        onCheckedChange = { enabled ->
                            if (enabled && !hasPermission) {
                                // 先请求定位权限，授权后自动开启
                                pendingAutoEnable = true
                                permissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                            } else {
                                if (enabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                                    ContextCompat.checkSelfPermission(
                                        context, Manifest.permission.POST_NOTIFICATIONS
                                    ) != PackageManager.PERMISSION_GRANTED
                                ) {
                                    notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                }
                                viewModel.setAutoCheckIn(enabled)
                            }
                        }
                    )
                }
                HorizontalDivider()
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "打卡时拍照",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            "手动打卡前先打开相机拍照取证，照片随记录保存",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = photoEnabled,
                        onCheckedChange = { viewModel.setPhotoEnabled(it) }
                    )
                }
            }
        }

        Spacer(Modifier.height(24.dp))

        Button(
            onClick = {
                if (photoEnabled) {
                    val dir = File(context.getExternalFilesDir(null), "photos").apply { mkdirs() }
                    val file = File(dir, "checkin_${System.currentTimeMillis()}.jpg")
                    val uri = FileProvider.getUriForFile(
                        context, "${context.packageName}.fileprovider", file
                    )
                    pendingPhotoFile = file
                    takePictureLauncher.launch(uri)
                } else {
                    viewModel.checkIn()
                }
            },
            enabled = !isChecking,
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp)
        ) {
            if (isChecking) {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    color = MaterialTheme.colorScheme.onPrimary,
                    strokeWidth = 3.dp
                )
            } else {
                Text("立即打卡", style = MaterialTheme.typography.titleLarge)
            }
        }

        Spacer(Modifier.height(16.dp))

        lastResult?.let { record ->
            val color = statusColor(record.status)
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.12f))
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        statusMessage(record),
                        color = color,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "打卡时间：${formatDateTime(record.timestamp)}",
                        style = MaterialTheme.typography.bodySmall
                    )
                    if (record.latitude != 0.0 || record.longitude != 0.0) {
                        Text(
                            "打卡地点：%.6f, %.6f".format(record.latitude, record.longitude),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    record.address?.let {
                        Text(
                            "地址：$it",
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 2
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(24.dp))

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text(
                    "今日与本月统计",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(12.dp))

                // 今日状态横幅（请假模式优先显示）
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(
                                when {
                                    todayLeave -> LeaveBlue
                                    stats.todayCheckedIn -> statusColor(CheckStatus.SUCCESS.name)
                                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                                }
                            )
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        when {
                            todayLeave -> "请假"
                            stats.todayCheckedIn -> "已打卡"
                            else -> "未打卡"
                        },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = when {
                            todayLeave -> LeaveBlue
                            stats.todayCheckedIn -> MaterialTheme.colorScheme.primary
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        if (todayLeave) "今日无需打卡"
                        else "成功 ${stats.todaySuccess} 次 · 失败 ${stats.todayFail} 次",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                HorizontalDivider(Modifier.padding(vertical = 12.dp))

                // 本月三项统计（等宽三列）
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatItem("${stats.monthAttendanceDays} 天", "本月出勤", Modifier.weight(1f))
                    VerticalDivider(Modifier.height(36.dp))
                    StatItem("${(stats.monthRate * 100).toInt()}%", "本月按时率", Modifier.weight(1f))
                    VerticalDivider(Modifier.height(36.dp))
                    StatItem("${stats.streakDays} 天", "连续打卡", Modifier.weight(1f))
                }
            }
        }

        Spacer(Modifier.height(24.dp))

        Text(
            "今日记录（${todayRecords.size}）",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Start
        )
        Spacer(Modifier.height(8.dp))
        if (todayRecords.isEmpty()) {
            Text(
                "今天还没有打卡记录",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            todayRecords.forEach { record ->
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
}

/** 请假标记颜色（蓝） */
private val LeaveBlue = Color(0xFF1E88E5)

/** 每秒刷新的时钟：独立状态，避免整个主页每秒重组 */
@Composable
private fun ClockText() {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1_000)
        }
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            "现在时间",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            formatDateTime(now),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
    }
}

@Composable
private fun StatItem(value: String, label: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
