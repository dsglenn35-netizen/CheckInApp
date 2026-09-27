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
    val clockSkewMs: Long? = null,

    // ---------- 审计留痕 ----------
    // 打卡记录原则上不可改，但「记录维护」允许修正录入错误。
    // 如果修正不留痕，这张表就能被随意篡改，交给 HR 时毫无证据价值；
    // 因此这里记录来源、首次修正时刻与原始打卡时刻，让"哪些是人改过的"可被核查。

    /** 记录来源，对应 [RecordOrigin] 的 name */
    val origin: String = RecordOrigin.AUTO.name,

    /** 首次被人工修正的时刻（毫秒），未修正过为 null */
    val editedAt: Long? = null,

    /** 被修正前的**原始**打卡时刻（毫秒），未修正过为 null。用于回溯与核对 */
    val originalTimestamp: Long? = null
) {
    /** 是否被人工修正过 */
    val isEdited: Boolean get() = editedAt != null || origin == RecordOrigin.EDITED.name

    /** 是否人工打卡（非自动监控产生） */
    val isManual: Boolean get() = origin == RecordOrigin.MANUAL.name

    companion object {
        /** 来源标签，用于报表「数据来源」列 */
        fun originLabel(origin: String, isEdited: Boolean): String = when {
            isEdited -> "人工修正"
            origin == RecordOrigin.MANUAL.name -> "手动打卡"
            else -> "自动打卡"
        }
    }
}

/**
 * 记录来源。
 * - [AUTO]：自动打卡服务在满足时间+地点时写入；
 * - [MANUAL]：用户在主页点"立即打卡"写入；
 * - [EDITED]：原始记录经「记录维护」修正过（原始时刻见 originalTimestamp）。
 */
enum class RecordOrigin { AUTO, MANUAL, EDITED }

/** 地点验证方式 */
enum class MatchSource {
    /** 经纬度落在规则地点半径内 */
    GPS,

    /** 定位不可用或精度过差，按已登记的 WiFi SSID 判定命中 */
    WIFI
}
