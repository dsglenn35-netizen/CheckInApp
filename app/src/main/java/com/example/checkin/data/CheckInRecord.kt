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
    val photoPath: String? = null
)
