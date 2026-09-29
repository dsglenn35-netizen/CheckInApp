package com.example.checkin.util

import android.content.Context

/**
 * 打卡提醒的本地持久化。
 *
 * 个人使用场景下提醒设置做成**全局**（总开关 + 提前量），不下发到每条规则：
 * 逐规则覆盖需要给 check_in_rules 加列、改数据库迁移，还要同步改 JSON 备份格式，
 * 而它带来的收益对单人考勤很小。全局设置同样能覆盖"多条规则不同时间窗"的情况 ——
 * 提醒是按**下一次窗口边界**动态计算的，不绑定某一条规则。
 */
object ReminderPrefs {

    private const val NAME = "reminder_prefs"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_LEAD_MINUTES = "lead_minutes"

    /** 默认提前量（分钟） */
    const val DEFAULT_LEAD_MINUTES = 15

    /** 设置页可选的提前量（分钟），按此顺序展示 */
    val LEAD_CHOICES = listOf(5, 10, 15, 30, 60)

    /** 提前量的合法区间：1 分钟 ~ 24 小时 */
    private const val MIN_LEAD_MINUTES = 1
    private const val MAX_LEAD_MINUTES = 24 * 60

    fun isEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun leadMinutes(context: Context): Int =
        prefs(context).getInt(KEY_LEAD_MINUTES, DEFAULT_LEAD_MINUTES)

    /**
     * 保存提前量。越界值回退到默认值而不是抛异常：
     * 该值直接决定闹钟时刻，脏数据（0 或负数）会让提醒与打卡时刻重叠而失去意义。
     */
    fun setLeadMinutes(context: Context, minutes: Int) {
        val safe = if (minutes in MIN_LEAD_MINUTES..MAX_LEAD_MINUTES) {
            minutes
        } else {
            DEFAULT_LEAD_MINUTES
        }
        prefs(context).edit().putInt(KEY_LEAD_MINUTES, safe).apply()
    }

    fun leadMillis(context: Context): Long = leadMinutes(context) * 60_000L

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)
}
