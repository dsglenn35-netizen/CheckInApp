package com.example.checkin.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.checkin.data.CheckInRecord
import com.example.checkin.data.CheckStatus
import com.example.checkin.util.decodeSampledBitmap
import com.example.checkin.util.formatDateTime
import com.example.checkin.util.statusColor
import com.example.checkin.util.statusLabel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 单条打卡记录的展示卡片（主页“今日记录”与日历详情共用）。
 * 支持：一键请假（失败记录）、备注编辑、删除（需传入回调）、打卡照片缩略图与全屏查看。
 */
@Composable
fun RecordRow(
    record: CheckInRecord,
    onDelete: (() -> Unit)? = null,
    onEditNote: ((String) -> Unit)? = null,
    onMarkLeave: (() -> Unit)? = null
) {
    val color = statusColor(record.status)

    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showNoteDialog by remember { mutableStateOf(false) }
    var showPhotoDialog by remember { mutableStateOf(false) }

    Card(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(12.dp),
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
                        statusLabel(record.status),
                        color = color,
                        fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.bodyLarge
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
                Text(
                    formatDateTime(record.timestamp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
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
                record.note?.let {
                    Text(
                        "备注：$it",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                record.photoPath?.let { path ->
                    // 图片解码放后台线程，避免主线程卡顿
                    val thumb by produceState<Bitmap?>(initialValue = null, path) {
                        value = withContext(Dispatchers.IO) { decodeSampledBitmap(path, 200, 200) }
                    }
                    thumb?.let {
                        Spacer(Modifier.height(6.dp))
                        Image(
                            bitmap = it.asImageBitmap(),
                            contentDescription = "打卡照片",
                            modifier = Modifier
                                .size(72.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { showPhotoDialog = true }
                        )
                    }
                }
            }
            // 一键请假：仅失败记录可标记，已标“请假”后变“已请假”禁用
            if (onMarkLeave != null && record.status != CheckStatus.SUCCESS.name) {
                val isLeave = record.note?.contains("请假") == true
                TextButton(
                    onClick = onMarkLeave,
                    enabled = !isLeave,
                    contentPadding = PaddingValues(horizontal = 8.dp)
                ) {
                    Text(
                        if (isLeave) "已请假" else "请假",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (isLeave) MaterialTheme.colorScheme.onSurfaceVariant
                        else MaterialTheme.colorScheme.primary
                    )
                }
            }
            if (onEditNote != null) {
                IconButton(onClick = { showNoteDialog = true }) {
                    Icon(
                        Icons.Filled.Edit,
                        contentDescription = "编辑备注",
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (onDelete != null) {
                IconButton(onClick = { showDeleteConfirm = true }) {
                    Icon(
                        Icons.Filled.Delete,
                        contentDescription = "删除记录",
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("删除记录") },
            text = {
                Text(
                    if (record.photoPath != null) {
                        "确定删除这条打卡记录吗？其打卡照片也会一并删除，此操作不可恢复。"
                    } else {
                        "确定删除这条打卡记录吗？此操作不可恢复。"
                    }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteConfirm = false
                    onDelete?.invoke()
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { showDeleteConfirm = false }) { Text("取消") } }
        )
    }

    if (showNoteDialog) {
        var noteText by remember { mutableStateOf(record.note ?: "") }
        AlertDialog(
            onDismissRequest = { showNoteDialog = false },
            title = { Text("打卡备注") },
            text = {
                OutlinedTextField(
                    value = noteText,
                    onValueChange = { noteText = it },
                    label = { Text("备注内容（如迟到原因）") },
                    minLines = 2,
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showNoteDialog = false
                    onEditNote?.invoke(noteText)
                }) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { showNoteDialog = false }) { Text("取消") } }
        )
    }

    if (showPhotoDialog) {
        record.photoPath?.let { path ->
            // 大图解码同样放后台线程
            val full by produceState<Bitmap?>(initialValue = null, path) {
                value = withContext(Dispatchers.IO) { decodeSampledBitmap(path, 1600, 1600) }
            }
            val fullBitmap = full // 委托属性不可智能转换，先取局部变量
            AlertDialog(
                onDismissRequest = { showPhotoDialog = false },
                confirmButton = { TextButton(onClick = { showPhotoDialog = false }) { Text("关闭") } },
                text = {
                    if (fullBitmap != null) {
                        Image(
                            bitmap = fullBitmap.asImageBitmap(),
                            contentDescription = "打卡照片",
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else {
                        Text("加载中…")
                    }
                }
            )
        }
    }
}
