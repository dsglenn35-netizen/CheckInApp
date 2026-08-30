package com.example.checkin.ui

import android.net.Uri
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import com.example.checkin.BuildConfig

/** 设置页：数据备份/恢复、清空记录、记录维护、关于 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: CheckInViewModel,
    onOpenMaintenance: () -> Unit = {}
) {
    val context = LocalContext.current
    val settingsMessage by viewModel.settingsMessage.collectAsState()

    var showClearConfirm by remember { mutableStateOf(false) }
    var showRestoreConfirm by remember { mutableStateOf(false) }
    var showMaintenanceConfirm by remember { mutableStateOf(false) }
    var pendingRestoreUri by remember { mutableStateOf<Uri?>(null) }

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
                "数据",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Card(Modifier.fillMaxWidth()) {
                Column {
                    SettingsItem(
                        icon = Icons.Filled.Backup,
                        title = "备份数据",
                        subtitle = "导出全部规则与打卡记录为 JSON 文件",
                        onClick = { viewModel.backupData() }
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

@Composable
private fun SettingsItem(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    danger: Boolean = false
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
    }
}
