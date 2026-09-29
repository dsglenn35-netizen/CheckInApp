package com.example.checkin.ui

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.example.checkin.util.BiometricAction
import com.example.checkin.util.BiometricPolicy
import com.example.checkin.util.BiometricPrefs

/** 打卡前生物识别门禁的结果 */
enum class GateOutcome {
    /** 验证通过 */
    VERIFIED,

    /** 未开启身份确认，无需验证 */
    NOT_REQUIRED,

    /** 设备不支持或未录入 → **放行**，但本次打卡未经身份确认（调用方应提示用户） */
    UNAVAILABLE,

    /** 用户主动取消或验证失败 → **不打卡** */
    REJECTED
}

/** 门禁：传入结果回调即发起（或跳过）一次身份确认 */
typealias BiometricGate = (onOutcome: (GateOutcome) -> Unit) -> Unit

/**
 * 创建打卡前的生物识别门禁。
 *
 * 为什么放在 Compose 层而不是 ViewModel：BiometricPrompt 必须绑定
 * [FragmentActivity]，而 ViewModel 不该持有 Activity（会在配置变更后泄漏/失效）。
 *
 * 门禁的判定逻辑全部委托给可单测的 [BiometricPolicy]，
 * 这里只负责"把策略变成一次真实的弹窗"。
 */
@Composable
fun rememberBiometricGate(): BiometricGate {
    val context = LocalContext.current
    // MainActivity 是 FragmentActivity，正常路径下必然拿得到；拿不到也只是放行
    val activity = context as? FragmentActivity
    return remember(context, activity) {
        val gate: BiometricGate = { onOutcome ->
            val action = BiometricPolicy.actionFor(
                enabled = BiometricPrefs.isEnabled(context),
                canAuthenticateCode = BiometricManager.from(context)
                    .canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK)
            )
            when (action) {
                BiometricAction.SKIP_DISABLED -> onOutcome(GateOutcome.NOT_REQUIRED)
                BiometricAction.SKIP_UNSUPPORTED,
                BiometricAction.SKIP_NONE_ENROLLED -> onOutcome(GateOutcome.UNAVAILABLE)
                BiometricAction.PROMPT ->
                    if (activity == null) {
                        // 绝不因为门禁自身的问题挡住打卡
                        onOutcome(GateOutcome.UNAVAILABLE)
                    } else {
                        showPrompt(activity, onOutcome)
                    }
            }
        }
        gate
    }
}

private fun showPrompt(activity: FragmentActivity, onOutcome: (GateOutcome) -> Unit) {
    val prompt = BiometricPrompt(
        activity,
        ContextCompat.getMainExecutor(activity),
        object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(
                result: BiometricPrompt.AuthenticationResult
            ) {
                onOutcome(GateOutcome.VERIFIED)
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                // 取消 / 锁定 / 连续失败等：尊重用户的意思表示，不打卡
                onOutcome(GateOutcome.REJECTED)
            }

            override fun onAuthenticationFailed() {
                // 单次指纹不匹配：系统会自行让用户重试，这里不打断也不视为最终失败
            }
        }
    )
    val info = BiometricPrompt.PromptInfo.Builder()
        .setTitle("打卡身份确认")
        .setSubtitle("验证通过后才记录本次打卡")
        .setNegativeButtonText("取消")
        .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_WEAK)
        .build()
    prompt.authenticate(info)
}
