package com.example.checkin.data

/** 一次打卡尝试的结果状态 */
enum class CheckStatus {
    /** 打卡成功（时间与地点均符合某条规则） */
    SUCCESS,

    /**
     * **外勤打卡**：时间符合、但不在规定地点，经用户填写原因后改判。
     *
     * 它算「**有效出勤**」而不算「正常出勤」：对个人来说这天确实出勤了（不该显示缺卡），
     * 但报表里必须与正常打卡**分开统计** —— 否则"外勤"就成了绕过地点校验的万能借口。
     */
    FIELD_WORK,

    /** 不在任何规则的打卡时间段内 */
    OUT_OF_TIME,

    /** 不在任何规则的打卡地点范围内 */
    OUT_OF_RANGE,

    /** 时间与地点均不匹配 */
    OUT_OF_TIME_AND_RANGE,

    /** 无法获取定位 */
    NO_LOCATION,

    /** 尚未配置任何启用中的规则 */
    NO_RULE;

    companion object {
        /**
         * 该状态是否算**有效出勤**（正常打卡或外勤打卡）。
         *
         * 全项目"这天算不算出勤"的判定都必须走这里，不要各自写
         * `status == SUCCESS.name` —— 加入外勤后那样写会把外勤算成缺勤，
         * 而这种错只会体现在报表数字上，界面上完全看不出来。
         */
        fun isAttended(status: String?): Boolean = when (status) {
            SUCCESS.name, FIELD_WORK.name -> true
            else -> false
        }

        /** 该状态是否算**正常出勤**（外勤不算），用于"正常出勤天数"这类口径 */
        fun isNormal(status: String?): Boolean = status == SUCCESS.name

        /** 该状态是否算**未出勤** */
        fun isAbsent(status: String?): Boolean = !isAttended(status)
    }
}
