package com.example.checkin.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.Badge
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.biometric.BiometricManager
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Vibration
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.checkin.BuildConfig
import com.example.checkin.util.BiometricPolicy
import com.example.checkin.util.EmployeeInfo
import com.example.checkin.util.ReadinessChecks

/** 设置页：数据备份/恢复、清空记录、记录维护、关于 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: CheckInViewModel,
    onOpenMaintenance: () -> Unit = {}
) {
    val context = LocalContext.current
    val settingsMessage by viewModel.settingsMessage.collectAsState()
    val backupWithPhotos by viewModel.backupWithPhotos.collectAsState()
    val employeeInfo by viewModel.employee.collectAsState()
    val reminderEnabled by viewModel.reminderEnabled.collectAsState()
    val reminderLeadMinutes by viewModel.reminderLeadMinutes.collectAsState()
    val resultNotifyEnabled by viewModel.resultNotifyEnabled.collectAsState()
    val soundEnabled by viewModel.soundEnabled.collectAsState()
    val vibrationEnabled by viewModel.vibrationEnabled.collectAsState()
    val biometricEnabled by viewModel.biometricEnabled.collectAsState()

    var showClearConfirm by remember { mutableStateOf(false) }
    var showRestoreConfirm by remember { mutableStateOf(false) }
    var showMaintenanceConfirm by remember { mutableStateOf(false) }
    var showEmployeeDialog by remember { mutableStateOf(false) }
    var pendingRestoreUri by remember { mutableStateOf<Uri?>(null) }

    // 后台运行保障自检：回到前台时重算（用户可能刚从系统设置页授权回来）
    var readiness by remember { mutableStateOf(ReadinessChecks.all(context)) }
    // 生物识别可用性：用户可能刚从系统设置里录完指纹回来，同样在 ON_RESUME 重算
    var biometricStatus by remember { mutableStateOf(biometricStatusOf(context)) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                readiness = ReadinessChecks.all(context)
                biometricStatus = biometricStatusOf(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val pendingCount = readiness.count { it.needsAction }

    /** 打开系统设置页；个别 ROM 缺少对应 Activity 时降级到应用详情页 */
    fun openSettings(intent: Intent) {
        runCatching { context.startActivity(intent) }
            .onFailure {
                runCatching {
                    context.startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                            data = Uri.fromParts("package", context.packageName, null)
                        }
                    )
                }
            }
    }

    val openDocLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            pendingRestoreUri = uri
            showRestoreConfirm = true
        }
    }

    LaunchedEffect(settingsMessage) {
        settingsMessage?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            viewModel.consumeSettingsMessage()
        }
    }

    Scaffold(
        // 外层导航已处理系统栏内边距，内层不再重复预留，避免顶部空白
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("设置") },
                windowInsets = WindowInsets(0.dp)
            )
        },
        contentWindowInsets = WindowInsets(0.dp)
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                "后台运行保障",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = if (pendingCount > 0) {
                        MaterialTheme.colorScheme.errorContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    }
                )
            ) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            if (pendingCount == 0) Icons.Filled.CheckCircle else Icons.Filled.Security,
                            contentDescription = null,
                            tint = if (pendingCount == 0) {
                                ReadinessOkGreen
                            } else {
                                MaterialTheme.colorScheme.error
                            }
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            if (pendingCount == 0) "全部就绪，自动打卡可稳定在后台运行"
                            else "有 $pendingCount 项需要处理",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "自动打卡在非打卡时段完全静默，只靠系统闹钟唤醒。" +
                            "下列任一能力缺失都会导致「开关开着却没打卡」，且不会报错。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    readiness.forEach { item ->
                        ReadinessRow(
                            title = readinessTitle(item),
                            detail = item.detail,
                            ok = item.ok,
                            notRequired = item.notRequired,
                            onFix = item.settingsIntent?.let { intent -> { openSettings(intent) } }
                        )
                    }
                    if (pendingCount > 0) {
                        Spacer(Modifier.height(4.dp))
                        TextButton(onClick = { readiness = ReadinessChecks.all(context) }) {
                            Text("重新检测")
                        }
                    }
                }
            }

            Text(
                "打卡提醒",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Card(Modifier.fillMaxWidth()) {
                Column {
                    SettingsItem(
                        icon = Icons.Filled.NotificationsActive,
                        title = "打卡提醒",
                        subtitle = if (reminderEnabled) {
                            "上班卡提前 $reminderLeadMinutes 分钟提醒；" +
                                "已打卡、请假或放假日不提醒"
                        } else {
                            "关闭：打卡前不会收到提醒（不影响自动打卡）"
                        },
                        onClick = { viewModel.setReminderEnabled(!reminderEnabled) },
                        trailing = {
                            Switch(
                                checked = reminderEnabled,
                                onCheckedChange = { viewModel.setReminderEnabled(it) }
                            )
                        }
                    )
                    if (reminderEnabled) {
                        HorizontalDivider()
                        Column(Modifier.padding(16.dp)) {
                            Text("提前量（分钟）", style = MaterialTheme.typography.titleMedium)
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "上班卡按窗口开始时刻倒推；下班卡按窗口结束时刻倒推，" +
                                    "且只对开启了「需要下班卡」的规则生效。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(8.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                com.example.checkin.util.ReminderPrefs.LEAD_CHOICES.forEach { minutes ->
                                    FilterChip(
                                        selected = minutes == reminderLeadMinutes,
                                        onClick = { viewModel.setReminderLeadMinutes(minutes) },
                                        label = { Text(minutes.toString()) }
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Text(
                "打卡反馈",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Card(Modifier.fillMaxWidth()) {
                Column {
                    SettingsItem(
                        icon = Icons.Filled.CheckCircle,
                        title = "打卡结果通知",
                        subtitle = "自动打卡成功或失败时单独弹一条通知；手动打卡有结果卡片，不重复弹",
                        onClick = { viewModel.setResultNotifyEnabled(!resultNotifyEnabled) },
                        trailing = {
                            Switch(
                                checked = resultNotifyEnabled,
                                onCheckedChange = { viewModel.setResultNotifyEnabled(it) }
                            )
                        }
                    )
                    HorizontalDivider()
                    SettingsItem(
                        icon = Icons.Filled.VolumeUp,
                        title = "提示音",
                        subtitle = "成功用系统通知音，失败用系统闹钟音（失败更需要立刻补救）",
                        onClick = { viewModel.setSoundEnabled(!soundEnabled) },
                        trailing = {
                            Switch(
                                checked = soundEnabled,
                                onCheckedChange = { viewModel.setSoundEnabled(it) }
                            )
                        }
                    )
                    HorizontalDivider()
                    SettingsItem(
                        icon = Icons.Filled.Vibration,
                        title = "震动",
                        subtitle = "成功震一下，失败震两下",
                        onClick = { viewModel.setVibrationEnabled(!vibrationEnabled) },
                        trailing = {
                            Switch(
                                checked = vibrationEnabled,
                                onCheckedChange = { viewModel.setVibrationEnabled(it) }
                            )
                        }
                    )
                    HorizontalDivider()
                    Text(
                        "手机处于「静音」模式时不响也不震，「震动」模式下只震不响 —— " +
                            "系统设置优先于这里的开关；无论哪种模式，结果通知照常送达。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }

            Text(
                "打卡身份确认",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Card(Modifier.fillMaxWidth()) {
                Column {
                    SettingsItem(
                        icon = Icons.Filled.Fingerprint,
                        title = "打卡前需生物识别确认",
                        subtitle = when {
                            !biometricEnabled -> "关闭：点「立即打卡」直接记录"
                            BiometricPolicy.isUsable(biometricStatus) ->
                                "已开启：手动打卡前需验证指纹或人脸，通过才记录"
                            else ->
                                "已开启，但本机暂不可用（" +
                                    BiometricPolicy.describe(biometricStatus) +
                                    "）—— 为免耽误打卡会直接放行"
                        },
                        onClick = { viewModel.setBiometricEnabled(!biometricEnabled) },
                        trailing = {
                            Switch(
                                checked = biometricEnabled,
                                onCheckedChange = { viewModel.setBiometricEnabled(it) }
                            )
                        }
                    )
                    HorizontalDivider()
                    Text(
                        "自动打卡在后台无人值守，无法做生物识别，因此不受此项约束；" +
                            "导出表的「数据来源」列可区分手动打卡与自动打卡。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }

            Text(
                "数据",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Card(Modifier.fillMaxWidth()) {
                Column {
                    SettingsItem(
                        icon = Icons.Filled.Badge,
                        title = "考勤表人员信息",
                        subtitle = if (employeeInfo.isEmpty) {
                            "未填写：导出的考勤表表头留空，可自行手写"
                        } else {
                            employeeInfo.headerLine() ?: "未填写"
                        },
                        onClick = { showEmployeeDialog = true }
                    )
                    HorizontalDivider()
                    SettingsItem(
                        icon = Icons.Filled.Backup,
                        title = "备份数据",
                        subtitle = "导出全部规则、附加打卡点与记录为 JSON 文件",
                        onClick = { viewModel.backupData() },
                        trailing = {
                            Switch(
                                checked = backupWithPhotos,
                                onCheckedChange = { viewModel.setBackupWithPhotos(it) }
                            )
                        }
                    )
                    HorizontalDivider()
                    Text(
                        if (backupWithPhotos) "备份将包含取证照片（体积显著增大，换机可完整恢复）"
                        else "备份不含照片（体积小；换机后照片不显示）。右侧开关可切换",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 56.dp, end = 16.dp, bottom = 12.dp)
                    )
                    HorizontalDivider()
                    SettingsItem(
                        icon = Icons.Filled.Restore,
                        title = "恢复数据",
                        subtitle = "从备份文件覆盖恢复（将清空现有数据）",
                        onClick = {
                            openDocLauncher.launch(arrayOf("application/json", "text/plain", "*/*"))
                        }
                    )
                    HorizontalDivider()
                    SettingsItem(
                        icon = Icons.Filled.DeleteSweep,
                        title = "清空打卡记录",
                        subtitle = "删除全部打卡记录（保留规则）",
                        onClick = { showClearConfirm = true },
                        danger = true
                    )
                    HorizontalDivider()
                    SettingsItem(
                        icon = Icons.Filled.Build,
                        title = "记录维护",
                        subtitle = "修正打卡时间与地点（仅限录入错误）",
                        onClick = { showMaintenanceConfirm = true }
                    )
                }
            }

            Text(
                "关于",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("打卡助手", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "v${BuildConfig.VERSION_NAME} · 第 ${BuildConfig.VERSION_CODE} 次修订 · 数据仅保存在本机",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }

    if (showEmployeeDialog) {
        var name by remember { mutableStateOf(employeeInfo.name) }
        var empId by remember { mutableStateOf(employeeInfo.employeeId) }
        var dept by remember { mutableStateOf(employeeInfo.department) }
        AlertDialog(
            onDismissRequest = { showEmployeeDialog = false },
            title = { Text("考勤表人员信息") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "导出 Excel 时写进「考勤日报」的表头，HR 拿到即可直接归档。" +
                            "信息只保存在本机，不会上传。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("姓名") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = empId,
                        onValueChange = { empId = it },
                        label = { Text("工号") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = dept,
                        onValueChange = { dept = it },
                        label = { Text("部门") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.setEmployee(
                        EmployeeInfo(name.trim(), empId.trim(), dept.trim())
                    )
                    showEmployeeDialog = false
                }) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { showEmployeeDialog = false }) { Text("取消") }
            }
        )
    }

    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text("清空打卡记录") },
            text = { Text("确定删除全部打卡记录吗？记录附带的打卡照片也会一并删除，此操作不可恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    showClearConfirm = false
                    viewModel.clearAllRecords()
                }) { Text("清空", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { showClearConfirm = false }) { Text("取消") } }
        )
    }

    if (showRestoreConfirm) {
        AlertDialog(
            onDismissRequest = { showRestoreConfirm = false },
            title = { Text("恢复数据") },
            text = { Text("恢复将清空当前全部数据并导入备份内容，确定继续吗？") },
            confirmButton = {
                TextButton(onClick = {
                    showRestoreConfirm = false
                    pendingRestoreUri?.let { viewModel.restoreData(it) }
                }) { Text("恢复") }
            },
            dismissButton = { TextButton(onClick = { showRestoreConfirm = false }) { Text("取消") } }
        )
    }

    if (showMaintenanceConfirm) {
        AlertDialog(
            onDismissRequest = { showMaintenanceConfirm = false },
            title = { Text("记录维护") },
            text = { Text("打卡记录原则上不可修改。此功能仅用于修正系统打卡产生的录入错误（时间/地点），修改后统计与日历展示将随之变化。确定继续吗？") },
            confirmButton = {
                TextButton(onClick = {
                    showMaintenanceConfirm = false
                    onOpenMaintenance()
                }) { Text("继续") }
            },
            dismissButton = { TextButton(onClick = { showMaintenanceConfirm = false }) { Text("取消") } }
        )
    }
}

/** 自检项标题（顺序与 [ReadinessChecks.all] 一致） */
/** 当前设备能否弹出生识别验证（设置页展示用；无硬件/未录入都会返回错误码） */
private fun biometricStatusOf(context: android.content.Context): Int =
    BiometricManager.from(context)
        .canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK)

private fun readinessTitle(item: com.example.checkin.util.ReadinessItem): String = when {
    item.notRequired -> "系统无需该项设置"
    item.ok -> "已就绪"
    else -> "待处理"
}

/** 就绪状态绿色 */
private val ReadinessOkGreen = androidx.compose.ui.graphics.Color(0xFF2E7D32)

/** 单项自检行：状态图标 + 说明 + 一键修复 */
@Composable
private fun ReadinessRow(
    title: String,
    detail: String,
    ok: Boolean,
    notRequired: Boolean,
    onFix: (() -> Unit)?
) {
    val okColor = if (ok) ReadinessOkGreen else MaterialTheme.colorScheme.error
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.Top
    ) {
        Icon(
            if (ok) Icons.Filled.CheckCircle else Icons.Filled.ErrorOutline,
            contentDescription = null,
            tint = okColor,
            modifier = Modifier.size(18.dp)
        )
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = if (ok) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.error
            )
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (!ok && !notRequired && onFix != null) {
            TextButton(onClick = { onFix() }) { Text("去设置") }
        }
    }
}

@Composable
private fun SettingsItem(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    danger: Boolean = false,
    trailing: (@Composable () -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        trailing?.invoke()
    }
}
