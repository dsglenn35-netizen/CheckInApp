package com.example.checkin.util

import android.content.Context

/**
 * 打卡反馈的本地持久化：结果通知 / 提示音 / 震动。
 *
 * 三项默认全开：打卡是"不看手机也要知道成没成"的事，
 * 自动打卡成功时用户往往并不在看屏幕，没有反馈就只能事后翻记录。
 */
object FeedbackPrefs {

    private const val NAME = "feedback_prefs"
    private const val KEY_RESULT_NOTIFY = "result_notify"
    private const val KEY_SOUND = "sound"
    private const val KEY_VIBRATION = "vibration"

    /** 自动打卡成功/失败时单独弹一条结果通知 */
    fun resultNotifyEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_RESULT_NOTIFY, true)

    fun setResultNotifyEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_RESULT_NOTIFY, enabled).apply()
    }

    /** 打卡提示音（成功=通知音，失败=闹钟音） */
    fun soundEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_SOUND, true)

    fun setSoundEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_SOUND, enabled).apply()
    }

    /** 打卡震动反馈（成功一记，失败两记） */
    fun vibrationEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_VIBRATION, true)

    fun setVibrationEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_VIBRATION, enabled).apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)
}
