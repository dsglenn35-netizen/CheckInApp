package com.example.checkin.util

import androidx.biometric.BiometricManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 打卡身份确认策略的单测。
 *
 * 核心不变量：**门禁永远不能挡住打卡**。
 * 除了"用户主动取消"，其余任何情况（未开启 / 无硬件 / 未录入 / 硬件暂不可用）都必须放行，
 * 否则用户会因为一个辅助安全功能而错过打卡窗口。
 */
class BiometricPolicyTest {

    @Test
    fun `未开启时直接放行`() {
        assertEquals(
            BiometricAction.SKIP_DISABLED,
            BiometricPolicy.actionFor(false, BiometricManager.BIOMETRIC_SUCCESS)
        )
    }

    @Test
    fun `开启且可用时弹窗验证`() {
        assertEquals(
            BiometricAction.PROMPT,
            BiometricPolicy.actionFor(true, BiometricManager.BIOMETRIC_SUCCESS)
        )
    }

    @Test
    fun `开启但未录入指纹时放行以免耽误打卡`() {
        assertEquals(
            BiometricAction.SKIP_NONE_ENROLLED,
            BiometricPolicy.actionFor(true, BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED)
        )
    }

    @Test
    fun `没有生物识别硬件时放行`() {
        assertEquals(
            BiometricAction.SKIP_UNSUPPORTED,
            BiometricPolicy.actionFor(true, BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE)
        )
    }

    @Test
    fun `硬件暂时不可用时放行`() {
        assertEquals(
            BiometricAction.SKIP_UNSUPPORTED,
            BiometricPolicy.actionFor(true, BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE)
        )
    }

    @Test
    fun `只有成功状态才算当下可用`() {
        assertTrue(BiometricPolicy.isUsable(BiometricManager.BIOMETRIC_SUCCESS))
        assertFalse(BiometricPolicy.isUsable(BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED))
        assertFalse(BiometricPolicy.isUsable(BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE))
    }

    @Test
    fun `可用性说明对每种状态都有文案`() {
        val codes = listOf(
            BiometricManager.BIOMETRIC_SUCCESS,
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED,
            BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE,
            BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE,
            Int.MIN_VALUE
        )
        codes.forEach { code ->
            assertTrue(
                "错误码 " + code + " 缺少可用性说明",
                BiometricPolicy.describe(code).isNotBlank()
            )
        }
    }
}
