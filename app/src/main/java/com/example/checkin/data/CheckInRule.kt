package com.example.checkin.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 一条打卡规则：指定时间段 + 指定地点（主地点圆心 + 允许半径）。
 * 时间窗口支持跨午夜，例如 22:00 - 06:00。
 *
 * 地点支持**一个规则多个打卡点**：主地点即本实体的经纬度，
 * 附加地点存放在 [CheckInSite]（如公司两个门、多园区），
 * 只要落在任一地点的半径内即视为地点符合。
 */
@Entity(tableName = "check_in_rules")
data class CheckInRule(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val startHour: Int,
    val startMinute: Int,
    val endHour: Int,
    val endMinute: Int,
    /** 主地点纬度（规则自带，兼容 v2.4 及更早数据） */
    val latitude: Double,
    /** 主地点经度 */
    val longitude: Double,
    /** 主地点允许打卡的范围半径（米） */
    val radiusMeters: Double,
    val enabled: Boolean = true,
    /**
     * 生效的星期，位掩码：bit0=周一 … bit6=周日。
     * 默认 127（每天）。当 [shiftPattern] 为轮转班制时本字段不参与判定。
     */
    val daysOfWeek: Int = 127,
    /**
     * 班制表达式（[com.example.checkin.util.ShiftPattern] 的序列化形式）：
     * - 空串 / "W"：按 [daysOfWeek] 的每星期几生效；
     * - "R:上N/休M:yyyy-MM-dd"：轮转班制，从锚点日期起 N 天上班、M 天休息循环。
     */
    val shiftPattern: String = "",
    /**
     * 应到时刻（当天分钟数，-1 表示不判定"迟到"）。
     * 仅用于考勤报表的迟到统计，不影响打卡成功与否。
     */
    val requiredStartMinute: Int = -1,
    /**
     * 应离时刻（当天分钟数，-1 表示不判定"早退"）。
     */
    val requiredEndMinute: Int = -1,
    /**
     * 主地点可选的 WiFi SSID（多个用英文逗号分隔）。
     * 定位不可用或精度过差时，连接该 WiFi 视为落在本地点。
     */
    val wifiSsid: String? = null,
    /**
     * 是否需要**下班卡**。
     *
     * - false（默认）：整个时间窗只记一次成功 —— "打卡一次即完成"；
     * - true：时间窗对半分为**上班卡**与**下班卡**两个槽位，各自只记一次，
     *   从而能得到"上班时刻 + 下班时刻"，进而算出真实的在岗时长。
     *
     * 之所以对半分而不是"打完第一次就允许第二次"，是因为自动打卡会周期轮询：
     * 若第二次不限时间，上班卡打完 60 秒后就会被记成下班卡。
     */
    val requireCheckOut: Boolean = false
)
