package com.example.checkin.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 规则的**附加打卡地点**（主地点仍是 [CheckInRule] 自带的经纬度）。
 *
 * 用途：一个规则可能对应多个可打卡位置，例如公司两个大门、
 * 同一园区的多栋楼、或临时办公点。只要落在任一地点半径内即视为地点符合。
 *
 * [wifiSsid] 为可选的辅助判定：当定位不可用或精度过差时，
 * 连接该 WiFi 即视为在该地点（室内/GPS 漂移场景的兜底）。
 * 多个 SSID 用英文逗号分隔。
 */
@Entity(tableName = "check_in_sites")
data class CheckInSite(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** 所属规则主键（对应 [CheckInRule.id]） */
    val ruleId: Long,
    /** 地点名称，如"公司南门" */
    val name: String,
    val latitude: Double,
    val longitude: Double,
    /** 允许打卡半径（米） */
    val radiusMeters: Double,
    /** 可选的 WiFi SSID（逗号分隔），用于定位不可靠时兜底判定 */
    val wifiSsid: String? = null
)
