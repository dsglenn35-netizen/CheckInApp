package com.example.checkin.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.checkin.util.FieldWorkPolicy

/**
 * 填写外勤原因的对话框。
 *
 * 原因**必填且有最短长度**：外勤是"地点不符但仍算出勤"的判断，
 * 报表上必须留下能看懂的理由，否则这一列对 HR（或对几个月后的自己）毫无意义。
 */
@Composable
fun FieldWorkReasonDialog(
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var reason by remember { mutableStateOf("") }
    val valid = FieldWorkPolicy.isReasonValid(reason)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("记为外勤打卡") },
        text = {
            Column {
                Text(
                    "这次打卡时间符合、地点不在规定范围内。记为外勤后：",
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "• 计入**有效出勤**，当天不再显示缺卡；\n" +
                        "• 报表里与正常打卡分开统计，并标记为人工改判；\n" +
                        "• 打卡时间与地点保持原样，不做修改。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = reason,
                    onValueChange = { reason = it },
                    label = { Text("外勤原因（必填）") },
                    placeholder = { Text("如：在客户现场驻场") },
                    singleLine = false,
                    minLines = 2,
                    isError = reason.isNotEmpty() && !valid
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(reason.trim()) },
                enabled = valid
            ) { Text("确认外勤") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}
