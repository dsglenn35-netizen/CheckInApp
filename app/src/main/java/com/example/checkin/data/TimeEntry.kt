package com.example.checkin.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 时间段标注：按时间段的请假 / 放假 / 加班（均支持备注）。
 *
 * 请假与放假期段内自动打卡不产生记录（含失败记录），语义相同、仅统计口径不同；
 * 加班时段仅作记录展示，不影响自动打卡。
 */
@Entity(tableName = "time_entries")
data class TimeEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** 日期，ISO 格式 yyyy-MM-dd */
    val date: String,
    /** 类型：[TYPE_LEAVE] 请假 / [TYPE_HOLIDAY] 放假 / [TYPE_OVERTIME] 加班 */
    val type: String,
    /** 开始时刻（当天分钟数 0..1439） */
    val startMinute: Int,
    /** 结束时刻（当天分钟数，不含整点，与打卡规则一致） */
    val endMinute: Int,
    val note: String? = null
) {
    /**
     * 是否为"不用打卡"的时段（请假或放假）。
     * 加班不属于：加班时段仍应正常打卡。
     */
    val isTimeOff: Boolean get() = type == TYPE_LEAVE || type == TYPE_HOLIDAY

    companion object {
        const val TYPE_LEAVE = "LEAVE"
        const val TYPE_HOLIDAY = "HOLIDAY"
        const val TYPE_OVERTIME = "OVERTIME"
    }
}
