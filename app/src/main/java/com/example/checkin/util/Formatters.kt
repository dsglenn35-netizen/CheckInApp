package com.example.checkin.util

import androidx.compose.ui.graphics.Color
import com.example.checkin.data.CheckInRecord
import com.example.checkin.data.CheckStatus
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Date
import java.util.Locale

fun formatDateTime(millis: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(millis))

fun formatTime(millis: Long): String =
    SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(millis))

fun formatHM(hour: Int, minute: Int): String =
    String.format(Locale.getDefault(), "%02d:%02d", hour, minute)

fun Long.toLocalDate(): LocalDate =
    Instant.ofEpochMilli(this).atZone(ZoneId.systemDefault()).toLocalDate()

/** 状态的中文标签 */
fun statusLabel(status: String): String = when (runCatching { CheckStatus.valueOf(status) }.getOrNull()) {
    CheckStatus.SUCCESS -> "成功"
    CheckStatus.OUT_OF_TIME -> "时间外"
    CheckStatus.OUT_OF_RANGE -> "地点外"
    CheckStatus.OUT_OF_TIME_AND_RANGE -> "时间地点均不符"
    CheckStatus.NO_LOCATION -> "定位失败"
    CheckStatus.NO_RULE -> "无规则"
    null -> "未知"
}

/** 状态对应的展示颜色（不同失败原因不同颜色） */
fun statusColor(status: String, dark: Boolean = false): Color =
    when (runCatching { CheckStatus.valueOf(status) }.getOrNull()) {
        CheckStatus.SUCCESS -> if (dark) Color(0xFF81C784) else Color(0xFF2E7D32)
        CheckStatus.OUT_OF_TIME -> if (dark) Color(0xFFEF9A9A) else Color(0xFFD32F2F)
        CheckStatus.OUT_OF_RANGE -> if (dark) Color(0xFFFFB74D) else Color(0xFFFF6F00)
        CheckStatus.OUT_OF_TIME_AND_RANGE -> if (dark) Color(0xFFCE93D8) else Color(0xFF8E24AA)
        CheckStatus.NO_LOCATION -> if (dark) Color(0xFFFFB74D) else Color(0xFFF57C00)
        CheckStatus.NO_RULE -> if (dark) Color(0xFF90A4AE) else Color(0xFF607D8B)
        null -> Color.Gray
    }

/** 打卡结果对应的用户提示文案 */
fun statusMessage(record: CheckInRecord): String =
    when (runCatching { CheckStatus.valueOf(record.status) }.getOrNull()) {
        CheckStatus.SUCCESS -> "打卡成功" + (record.ruleName?.let { "（规则：$it）" } ?: "")
        CheckStatus.OUT_OF_TIME -> "不在打卡时间段内"
        CheckStatus.OUT_OF_RANGE -> "不在打卡地点范围内"
        CheckStatus.OUT_OF_TIME_AND_RANGE -> "不在打卡时间段和地点范围内"
        CheckStatus.NO_LOCATION -> "无法获取定位，请检查定位权限或 GPS 是否开启"
        CheckStatus.NO_RULE -> "尚未配置打卡规则，请先到「规则」页添加"
        null -> "未知状态"
    }
