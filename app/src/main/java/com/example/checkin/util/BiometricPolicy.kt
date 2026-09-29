package com.example.checkin.util

import androidx.biometric.BiometricManager

/** 打卡前生物识别门禁应当采取的动作 */
enum class BiometricAction {
    /** 未开启身份确认 → 直接打卡 */
    SKIP_DISABLED,

    /** 弹窗验证，通过才打卡 */
    PROMPT,

    /** 设备没有生物识别硬件（或暂时不可用）→ 放行 */
    SKIP_UNSUPPORTED,

    /** 已开启但没有录入指纹/人脸 → 放行 */
    SKIP_NONE_ENROLLED
}

/**
 * 打卡身份确认的策略（纯逻辑，可单测）。
 *
 * ## 一条硬规则：门禁永远不能挡住打卡
 *
 * 打卡是有时效的：错过窗口就是缺卡 / 迟到。因此**任何"验证做不了"的情况都放行**，
 * 只在界面上明确告知"本次打卡未经身份确认"。反过来，如果用户**主动取消**了验证，
 * 那是明确的意思表示，就不打卡。
 *
 * 这与自动打卡的关系：自动打卡在后台无人值守，**不可能**要求生物识别，
 * 因此本门禁只作用于手动打卡；导出表里用「数据来源」列区分手动与自动。
 */
object BiometricPolicy {

    /**
     * @param enabled 用户是否开启了"打卡前需生物识别确认"
     * @param canAuthenticateCode [BiometricManager.canAuthenticate] 的返回值
     */
    fun actionFor(enabled: Boolean, canAuthenticateCode: Int): BiometricAction = when {
        !enabled -> BiometricAction.SKIP_DISABLED
        canAuthenticateCode == BiometricManager.BIOMETRIC_SUCCESS -> BiometricAction.PROMPT
        canAuthenticateCode == BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED ->
            BiometricAction.SKIP_NONE_ENROLLED
        else -> BiometricAction.SKIP_UNSUPPORTED
    }

    /** 本机是否**当下就能**弹出生识别验证 */
    fun isUsable(canAuthenticateCode: Int): Boolean =
        canAuthenticateCode == BiometricManager.BIOMETRIC_SUCCESS

    /** 设置页展示的可用性说明 */
    fun describe(canAuthenticateCode: Int): String = when (canAuthenticateCode) {
        BiometricManager.BIOMETRIC_SUCCESS -> "已录入指纹或人脸，可用"
        BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> "尚未录入指纹或人脸"
        BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE -> "本机没有指纹/人脸硬件"
        BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE -> "指纹/人脸硬件暂时不可用"
        else -> "生物识别不可用"
    }
}
