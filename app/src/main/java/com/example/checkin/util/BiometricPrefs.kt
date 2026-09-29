package com.example.checkin.util

import android.content.Context

/**
 * 「打卡前需生物识别确认」开关的本地持久化。
 *
 * 默认关闭：开启后每次手动打卡都要过一次指纹/人脸，属于用户明确的选择，
 * 不该默认强加（自动打卡不受它影响，见 [BiometricPolicy]）。
 */
object BiometricPrefs {

    private const val NAME = "biometric_prefs"
    private const val KEY_ENABLED = "enabled"

    fun isEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)
}
