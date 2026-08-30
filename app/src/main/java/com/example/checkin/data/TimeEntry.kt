package com.example.checkin.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 时间段标注：按时间段请假 / 加班（均支持备注）。
 *
 * 请假时段内自动打卡不产生记录（含失败记录）；
 * 加班时段仅作记录展示，不影响自动打卡。
 */
@Entity(tableName = "time_entries")
data class TimeEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** 日期，ISO 格式 yyyy-MM-dd */
    val date: String,
    /** 类型：[TYPE_LEAVE] 请假 / [TYPE_OVERTIME] 加班 */
    val type: String,
    /** 开始时刻（当天分钟数 0..1439） */
    val startMinute: Int,
    /** 结束时刻（当天分钟数，不含整点，与打卡规则一致） */
    val endMinute: Int,
    val note: String? = null
) {
    companion object {
        const val TYPE_LEAVE = "LEAVE"
        const val TYPE_OVERTIME = "OVERTIME"
    }
}
