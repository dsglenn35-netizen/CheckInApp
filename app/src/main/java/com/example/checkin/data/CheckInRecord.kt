package com.example.checkin.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 一次打卡记录（无论成功或失败都会记录当时的系统时间与地点）。
 */
@Entity(tableName = "check_in_records")
data class CheckInRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** 打卡时的系统时间（毫秒时间戳） */
    val timestamp: Long,
    val latitude: Double,
    val longitude: Double,
    /** 逆地理编码得到的地址描述，可为空 */
    val address: String?,
    /** 命中的规则名称（仅成功打卡时非空） */
    val ruleName: String?,
    /** 结果状态，对应 [CheckStatus] 的 name */
    val status: String,
    /** 用户备注（如迟到原因），可为空 */
    val note: String? = null,
    /** 打卡取证照片的本地路径，可为空 */
    val photoPath: String? = null,
    /**
     * 命中的规则主键（仅成功打卡时非空）。
     * 自动打卡的"同时段只记一次成功"与"失败冷却"按本字段判定，
     * 避免两条同名规则互相污染冷却窗口。
     * v2.5 之前的旧记录为 0（未知），按 [ruleName] 回退匹配。
     */
    val ruleId: Long = 0,
    /**
     * 验证方式，对应 [MatchSource] 的 name：
     * GPS 定位命中 / WiFi：定位不可靠时按已登记的 WiFi 兜底命中。
     */
    val matchSource: String = MatchSource.GPS.name,
    /**
     * 打卡时系统时间与定位时间（GPS 授时）的偏差毫秒数（绝对值），
     * 未取到可信定位时为 null。用于识别系统时间被修改导致的异常打卡。
     */
    val clockSkewMs: Long? = null
)

/** 地点验证方式 */
enum class MatchSource {
    /** 经纬度落在规则地点半径内 */
    GPS,

    /** 定位不可用或精度过差，按已登记的 WiFi SSID 判定命中 */
    WIFI
}
