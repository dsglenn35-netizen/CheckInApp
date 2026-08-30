package com.example.checkin.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 请假模式：提前把某一天标记为请假。
 * 请假当天不产生自动打卡记录（含失败记录），日历显示请假标记。
 */
@Entity(tableName = "leave_days")
data class LeaveDay(
    /** 日期，ISO 格式 yyyy-MM-dd（与 LocalDate.toString() 一致） */
    @PrimaryKey val date: String
)
