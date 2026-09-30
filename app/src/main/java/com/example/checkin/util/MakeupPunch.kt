package com.example.checkin.util

import com.example.checkin.data.CheckInRecord
import com.example.checkin.data.CheckInRule
import com.example.checkin.data.CheckStatus
import com.example.checkin.data.MatchSource
import com.example.checkin.data.RecordOrigin
import java.time.LocalDate

/**
 * 补卡（纯逻辑，可单测）。
 *
 * ## 补卡是什么、不是什么
 *
 * 补卡是"**我确实上了班，但当时忘了打**"的事后补录，因此：
 * - **必须填原因**（至少 [MIN_REASON_LENGTH] 个字），原因写进备注；
 * - 记录来源标为 [RecordOrigin.MAKEUP]，报表「数据来源」列显示"补卡" —— 与自动/手动打卡区分开；
 * - **不伪造地点**：补卡记录不带经纬度（0,0 = 未记录），因为补卡时并没有取证定位。
 *   随便填一个规则坐标会让人误以为"当时人确实在那儿"，那是伪造证据，不是补录。
 *
 * 与「记录维护」的**人工修正**是两回事：修正改的是**已有**记录，补卡新增的是**本来没有**的记录。
 */
object MakeupPunch {

    /** 补卡原因的最短字数 */
    const val MIN_REASON_LENGTH = 2

    /** 补卡备注前缀，用于识别与回读 */
    const val NOTE_PREFIX = "补卡："

    /** 原因是否可用 */
    fun isReasonValid(reason: String?): Boolean =
        (reason?.trim()?.length ?: 0) >= MIN_REASON_LENGTH

    /** 统一的备注格式 */
    fun noteFor(reason: String): String = NOTE_PREFIX + reason.trim()

    /** 备注是否已是补卡标注 */
    fun isMakeupNote(note: String?): Boolean = note?.startsWith(NOTE_PREFIX) == true

    /** 从备注取回补卡原因 */
    fun reasonOf(note: String?): String? =
        if (isMakeupNote(note)) note!!.removePrefix(NOTE_PREFIX).trim() else null

    /**
     * 构造一条补卡记录；原因不合规时返回 null。
     *
     * 打卡时刻按槽位取：
     * - 上班卡 [PunchSlot.IN] → 班次窗口**开始**时刻；
     * - 下班卡 [PunchSlot.OUT] → 窗口**结束前 1 分钟**（窗口是 [开始, 结束)，正好落在结束点上会被判为窗口外）。
     */
    fun build(
        rule: CheckInRule,
        date: LocalDate,
        slot: PunchSlot,
        reason: String?,
        now: Long = System.currentTimeMillis()
    ): CheckInRecord? {
        if (!isReasonValid(reason)) return null
        val start = AttendanceCalculator.windowStartFor(rule, date)
        val duration = CheckInValidator.windowDurationMillis(rule)
        val timestamp = when (slot) {
            PunchSlot.IN -> start
            PunchSlot.OUT -> start + (duration - 60_000L).coerceAtLeast(0L)
        }
        return CheckInRecord(
            timestamp = timestamp,
            // 补卡时没有取证定位：留 0,0（未记录），绝不拿规则坐标冒充"当时就在那儿"
            latitude = 0.0,
            longitude = 0.0,
            address = null,
            ruleName = rule.name,
            ruleId = rule.id,
            status = CheckStatus.SUCCESS.name,
            note = noteFor(reason!!),
            matchSource = MatchSource.GPS.name,
            clockSkewMs = null,
            mockLocation = false,
            origin = RecordOrigin.MAKEUP.name
        )
    }

    /**
     * 该班次还缺哪个槽位；不缺返回 null。
     * 用于决定补卡对话框里给出「补上班卡」还是「补下班卡」。
     */
    fun missingSlot(shift: ShiftAttendance): PunchSlot? = when {
        shift.punches.isEmpty() -> PunchSlot.IN
        (shift.rule.requireCheckOut || shift.rule.requiredEndMinute >= 0) &&
            shift.punches.size < 2 -> PunchSlot.OUT
        else -> null
    }
}
