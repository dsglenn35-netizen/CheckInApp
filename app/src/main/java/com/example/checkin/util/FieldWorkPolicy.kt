package com.example.checkin.util

import com.example.checkin.data.CheckInRecord
import com.example.checkin.data.CheckStatus

/**
 * 外勤打卡的判定与改判（纯逻辑，可单测）。
 *
 * ## 为什么是"改判已有记录"而不是"另记一条"
 *
 * 外勤打卡发生时，那条"地点外"的失败记录里已经带着**真实的时间、经纬度、地址与时钟偏差**，
 * 这些正是外勤最需要的证据（你在哪儿、几点、定位可信吗）。
 * 另记一条会把这次取证丢掉，还要重新获取定位；改判则完整保留了原始事实，
 * 只把**判定结果**从"地点外"改成"外勤"，并留下"这是人工改判的"痕迹。
 *
 * ## 只接受"时间对、地点不对"
 *
 * 只有 [CheckStatus.OUT_OF_RANGE] 可以改判。时间也不对的记录
 * （[CheckStatus.OUT_OF_TIME] / [CheckStatus.OUT_OF_TIME_AND_RANGE]）不属于外勤：
 * 那种情况该走补卡或记录修正，否则"外勤"会变成绕过考勤时间的后门。
 */
object FieldWorkPolicy {

    /** 外勤原因的最短字数：报表要能看懂，"外出""办事"这种等于没写 */
    const val MIN_REASON_LENGTH = 2

    /** 备注前缀，用于识别一条记录是否已被标注为外勤 */
    const val NOTE_PREFIX = "外勤："

    /** 这条记录能否改判为外勤（**只认"地点外"**） */
    fun canMarkAsFieldWork(status: String?): Boolean =
        status == CheckStatus.OUT_OF_RANGE.name

    /** 原因是否可用（去掉首尾空白后够长） */
    fun isReasonValid(reason: String?): Boolean =
        (reason?.trim()?.length ?: 0) >= MIN_REASON_LENGTH

    /**
     * 生成改判后的记录；不可改判或原因不合规时返回 null。
     *
     * **留痕口径与「记录维护」一致**：写入 [CheckInRecord.editedAt]，
     * 报表「数据来源」列会显示"人工修正" ——
     * 外勤是**人改判出来的结论**，不能伪装成系统自动判定。
     * 打卡时刻不变（改的是判定结果，不是时间），因此不写 originalTimestamp，
     * 免得界面显示"原打卡 09:03"却和当前时刻一模一样，反而让人以为改过时间。
     */
    fun applyTo(record: CheckInRecord, reason: String?, now: Long): CheckInRecord? {
        if (!canMarkAsFieldWork(record.status)) return null
        val trimmed = reason?.trim().orEmpty()
        if (!isReasonValid(trimmed)) return null
        return record.copy(
            status = CheckStatus.FIELD_WORK.name,
            note = noteFor(trimmed),
            // 已经被修正过的记录保留首次修正时刻
            editedAt = record.editedAt ?: now
        )
    }

    /** 外勤备注的统一格式 */
    fun noteFor(reason: String): String = NOTE_PREFIX + reason.trim()

    /** 备注是否已是外勤标注 */
    fun isFieldWorkNote(note: String?): Boolean =
        note?.startsWith(NOTE_PREFIX) == true

    /** 从备注里取回外勤原因；不是外勤备注时返回 null */
    fun reasonOf(note: String?): String? =
        if (isFieldWorkNote(note)) note!!.removePrefix(NOTE_PREFIX).trim() else null
}
