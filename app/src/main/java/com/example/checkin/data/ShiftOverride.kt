package com.example.checkin.data

import androidx.room.Entity

/**
 * 调班 / 调休：覆盖某条规则在**某一天**的默认生效状态。
 *
 * 规则自身的"哪几天上班"（星期位掩码 / 上N休M 轮转）表达的是**周期性**安排，
 * 而现实中总有例外：这周六要来上班、下周三调休。为了两天去改规则的班制，
 * 改完还得记得改回来 —— 所以用一张例外表按日期单独覆盖，规则本身保持不动。
 *
 * 复合主键 (date, ruleId)：同一天、同一条规则只会有一条覆盖记录。
 */
@Entity(tableName = "shift_overrides", primaryKeys = ["date", "ruleId"])
data class ShiftOverride(
    /** 考勤日，ISO 格式 yyyy-MM-dd（与 LocalDate.toString() 一致） */
    val date: String,
    /** 规则主键 */
    val ruleId: Long,
    /**
     * true = 当天按该规则上班（即使规则默认不生效）；
     * false = 当天该规则休息（即使规则默认生效）。
     */
    val working: Boolean
) {
    companion object {
        /**
         * 覆盖表的组合键。格式只在这里定义一处 ——
         * 数据层建表与纯逻辑查表若各写一套，改格式时必然漏改一边，
         * 表现为"调休设了但不生效"这种极难查的错。
         */
        fun key(date: String, ruleId: Long): String = date + "#" + ruleId
    }
}
