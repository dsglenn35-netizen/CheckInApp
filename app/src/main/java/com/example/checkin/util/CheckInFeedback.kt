package com.example.checkin.util

import android.content.Context
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log

/**
 * 一次打卡反馈的呈现方式。
 *
 * 静音与震动是**系统级意图**，优先级高于应用内的开关：
 * 用户在开会时把手机调成静音，应用就不该再响铃；调成震动时只震不响。
 */
enum class FeedbackMode {
    /** 铃声 + 震动（各自还受应用内开关控制） */
    SOUND_AND_VIBRATE,

    /** 仅震动（系统处于"震动"铃声模式） */
    VIBRATE_ONLY,

    /** 完全静默（系统处于"静音"铃声模式） */
    SILENT
}

/**
 * 打卡结果的即时反馈：提示音 + 震动。
 *
 * 手动打卡与自动打卡都用它，保证"点了打卡"和"到点自动打卡"的体感一致。
 *
 * 声音取值遵循系统默认铃声：
 * - **成功**用系统**通知音**（轻快，日常）；
 * - **失败**用系统**闹钟音**（更醒目）—— 失败是需要立刻补救的事（还在窗口内就赶紧手动打一次），
 *   用同一个通知音容易被当成普通消息划掉。
 * 取不到对应铃声时回退到通知音，而不是干脆不响。
 */
object CheckInFeedback {

    private const val TAG = "CheckInFeedback"

    /** 持有当前铃声引用：局部变量被回收会让铃声播到一半就断掉 */
    @Volatile
    private var current: Ringtone? = null

    /**
     * 按系统铃声模式决定反馈方式。
     *
     * 这是本对象唯一需要"判断"的逻辑，抽成纯函数以便单测
     * （真机之外的测试环境拿不到 AudioManager）。
     */
    fun modeFor(ringerMode: Int): FeedbackMode = when (ringerMode) {
        AudioManager.RINGER_MODE_SILENT -> FeedbackMode.SILENT
        AudioManager.RINGER_MODE_VIBRATE -> FeedbackMode.VIBRATE_ONLY
        // 未知取值按"正常"处理：宁可多响一次，也不要因为读不到模式而静默失效
        else -> FeedbackMode.SOUND_AND_VIBRATE
    }

    /** 播放一次打卡反馈；任何失败都只记日志，绝不影响打卡本身 */
    fun play(context: Context, success: Boolean) {
        runCatching {
            val app = context.applicationContext
            val audio = app.getSystemService(AudioManager::class.java)
            val mode = modeFor(audio?.ringerMode ?: AudioManager.RINGER_MODE_NORMAL)
            if (mode == FeedbackMode.SILENT) return
            if (FeedbackPrefs.vibrationEnabled(app)) vibrate(app, success)
            if (mode == FeedbackMode.SOUND_AND_VIBRATE && FeedbackPrefs.soundEnabled(app)) {
                playTone(app, success)
            }
        }.onFailure { Log.w(TAG, "打卡反馈播放失败", it) }
    }

    private fun playTone(context: Context, success: Boolean) {
        val preferred = if (success) {
            RingtoneManager.TYPE_NOTIFICATION
        } else {
            RingtoneManager.TYPE_ALARM
        }
        val uri = RingtoneManager.getDefaultUri(preferred)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            ?: return
        val ringtone = RingtoneManager.getRingtone(context, uri) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            ringtone.isLooping = false
        }
        current = ringtone
        ringtone.play()
    }

    private fun vibrate(context: Context, success: Boolean) {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Vibrator::class.java)
        } ?: return
        if (!vibrator.hasVibrator()) return
        // 成功：一记干脆的短震；失败：两记短震（"出问题了"的体感）
        val pattern = if (success) {
            longArrayOf(0L, 80L)
        } else {
            longArrayOf(0L, 60L, 90L, 60L)
        }
        // repeat = -1：只播一次，不循环
        vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
    }
}
