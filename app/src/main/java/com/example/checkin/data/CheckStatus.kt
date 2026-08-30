package com.example.checkin.data

/** 一次打卡尝试的结果状态 */
enum class CheckStatus {
    /** 打卡成功（时间与地点均符合某条规则） */
    SUCCESS,

    /** 不在任何规则的打卡时间段内 */
    OUT_OF_TIME,

    /** 不在任何规则的打卡地点范围内 */
    OUT_OF_RANGE,

    /** 时间与地点均不匹配 */
    OUT_OF_TIME_AND_RANGE,

    /** 无法获取定位 */
    NO_LOCATION,

    /** 尚未配置任何启用中的规则 */
    NO_RULE
}
