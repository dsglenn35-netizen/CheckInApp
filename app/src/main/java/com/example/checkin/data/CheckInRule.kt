package com.example.checkin.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 一条打卡规则：指定时间段 + 指定地点（圆心 + 允许半径）。
 * 时间窗口支持跨午夜，例如 22:00 - 06:00。
 */
@Entity(tableName = "check_in_rules")
data class CheckInRule(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val startHour: Int,
    val startMinute: Int,
    val endHour: Int,
    val endMinute: Int,
    val latitude: Double,
    val longitude: Double,
    /** 允许打卡的范围半径（米） */
    val radiusMeters: Double,
    val enabled: Boolean = true,
    /**
     * 生效的星期，位掩码：bit0=周一 … bit6=周日。
     * 默认 127（每天）。
     */
    val daysOfWeek: Int = 127
)
