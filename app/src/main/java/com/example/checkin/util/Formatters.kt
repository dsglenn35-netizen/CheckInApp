package com.example.checkin.util

import androidx.compose.ui.graphics.Color
import com.example.checkin.data.CheckInRecord
import com.example.checkin.data.CheckStatus
import com.example.checkin.data.MatchSource
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

/** Calendar -> LocalDate（与 [toLocalDate] 同一时区口径，供班制/星期判定使用） */
fun java.util.Calendar.toLocalDate(): LocalDate =
    Instant.ofEpochMilli(timeInMillis).atZone(ZoneId.systemDefault()).toLocalDate()

/** 当天分钟数（0..1439） */
fun Long.toMinuteOfDay(): Int {
    val cal = java.util.Calendar.getInstance().apply { timeInMillis = this@toMinuteOfDay }
    return cal.get(java.util.Calendar.HOUR_OF_DAY) * 60 + cal.get(java.util.Calendar.MINUTE)
}

/** 分钟数 -> HH:mm */
fun formatMinuteOfDay(minute: Int): String = formatHM(minute / 60, minute % 60)

/** 时钟偏差的人类可读描述（如"系统时间比 GPS 快 12 分钟"） */
fun formatClockSkew(skewMs: Long): String {
    val minutes = kotlin.math.abs(skewMs) / 60_000
    val direction = if (skewMs > 0) "快" else "慢"
    val human = when {
        minutes >= 60 -> "${minutes / 60} 小时 ${minutes % 60} 分钟"
        minutes >= 1 -> "$minutes 分钟"
        else -> "${kotlin.math.abs(skewMs) / 1000} 秒"
    }
    return "系统时间比定位授时$direction $human"
}

/** 状态的中文标签 */
fun statusLabel(status: String): String = when (runCatching { CheckStatus.valueOf(status) }.getOrNull()) {
    CheckStatus.SUCCESS -> "成功"
    CheckStatus.FIELD_WORK -> "外勤"
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
        // 外勤用青色：既不是"正常"的绿，也不属于任何失败色（红/深橙/紫/灰），
        // 日历上扫一眼就能看出"这天出勤了，但不是正常打卡"
        CheckStatus.FIELD_WORK -> if (dark) Color(0xFF4DD0E1) else Color(0xFF00838F)
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
        CheckStatus.FIELD_WORK -> "外勤打卡" +
            (record.ruleName?.let { "（规则：$it）" } ?: "") +
            (FieldWorkPolicy.reasonOf(record.note)?.let { "：$it" } ?: "")
        CheckStatus.OUT_OF_TIME -> "不在打卡时间段内"
        CheckStatus.OUT_OF_RANGE -> "不在打卡地点范围内"
        CheckStatus.OUT_OF_TIME_AND_RANGE -> "不在打卡时间段和地点范围内"
        CheckStatus.NO_LOCATION -> "无法获取定位，请检查定位权限或 GPS 是否开启"
        CheckStatus.NO_RULE -> "尚未配置打卡规则，请先到「规则」页添加"
        null -> "未知状态"
    }

// ---------- 日历与特殊日标记配色 ----------
//
// 三种"当天状态"必须在日历上一眼分开，因此刻意选了**冷暖对立**的三色：
//   正常 = 绿（statusColor(SUCCESS)）、请假 = 玫红（暖）、放假 = 靛蓝（冷）。
// 先前用"蓝 / 青绿"两色：青绿与正常的绿太接近，蓝与青绿又同属冷色，扫一眼分不清。
// 同时这几个色都与失败原因色（红 / 深橙 / 橙 / 紫 / 灰）保持距离。
//
// 每个色都提供深色模式变体：浅色主题用深色版、深色主题用浅色版，
// 否则深色背景上的深色圆点几乎看不见。

/** 请假标记色（玫红：暖色，与"正常"的绿、"放假"的靛蓝都拉开） */
fun leaveColor(dark: Boolean = false): Color =
    if (dark) Color(0xFFF06292) else Color(0xFFC2185B)

/** 公司放假标记色（靛蓝：冷色，与"正常"的绿、"请假"的玫红都拉开） */
fun holidayColor(dark: Boolean = false): Color =
    if (dark) Color(0xFF7986CB) else Color(0xFF283593)

/** 加班标记色（珊瑚橙） */
fun overtimeColor(dark: Boolean = false): Color =
    if (dark) Color(0xFFFFAB91) else Color(0xFFFF7043)

/** 当天有生效规则但完全未打卡的标记色（灰） */
fun missedColor(dark: Boolean = false): Color =
    if (dark) Color(0xFFBDBDBD) else Color(0xFF9E9E9E)

/** 地点验证方式的中文标签（WiFi 兜底需在记录上明示，便于事后核查） */
fun matchSourceLabel(source: String): String? =
    when (runCatching { MatchSource.valueOf(source) }.getOrNull()) {
        MatchSource.WIFI -> "WiFi 判定"
        else -> null
    }
