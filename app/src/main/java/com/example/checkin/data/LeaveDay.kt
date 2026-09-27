package com.example.checkin.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 特殊日标记：**按天**的请假 / 公司放假。
 *
 * - [KIND_LEAVE] 请假：个人请假，日历蓝色标记；
 * - [KIND_HOLIDAY] 放假：公司统一休假（如国庆、春节），日历青绿标记。
 *
 * 两者语义相同——**当天不需要打卡**：自动打卡在该日完全静默（不注册定位、
 * 不轮询、不产生任何记录，含失败记录）；报表里分开计数，且都不计入"应打卡"。
 *
 * 表名沿用 v5 的 leave_days：旧数据只有请假一种，v6 迁移补上 [kind] 列并默认请假。
 */
@Entity(tableName = "leave_days")
data class LeaveDay(
    /** 日期，ISO 格式 yyyy-MM-dd（与 LocalDate.toString() 一致） */
    @PrimaryKey val date: String,
    /** 标记类型：[KIND_LEAVE] 请假 / [KIND_HOLIDAY] 放假 */
    val kind: String = KIND_LEAVE
) {
    /** 是否公司放假（否则为请假） */
    val isHoliday: Boolean get() = kind == KIND_HOLIDAY

    companion object {
        const val KIND_LEAVE = "LEAVE"
        const val KIND_HOLIDAY = "HOLIDAY"
    }
}
