package com.example.checkin.util

import android.media.AudioManager
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 打卡反馈方式的单测。
 *
 * 依赖的只是 [AudioManager] 的 int 常量（编译期内联的 static final），
 * 因此不需要 Robolectric 或真机即可运行。
 */
class CheckInFeedbackTest {

    @Test
    fun `静音模式下完全静默`() {
        // 用户在开会时把手机调静音，应用就不该再响铃或震动
        assertEquals(
            FeedbackMode.SILENT,
            CheckInFeedback.modeFor(AudioManager.RINGER_MODE_SILENT)
        )
    }

    @Test
    fun `震动模式下只震不响`() {
        assertEquals(
            FeedbackMode.VIBRATE_ONLY,
            CheckInFeedback.modeFor(AudioManager.RINGER_MODE_VIBRATE)
        )
    }

    @Test
    fun `正常模式下铃声加震动`() {
        assertEquals(
            FeedbackMode.SOUND_AND_VIBRATE,
            CheckInFeedback.modeFor(AudioManager.RINGER_MODE_NORMAL)
        )
    }

    @Test
    fun `未知铃声模式按正常处理`() {
        // 读不到模式时宁可多响一次，也不要静默失效
        assertEquals(
            FeedbackMode.SOUND_AND_VIBRATE,
            CheckInFeedback.modeFor(Int.MIN_VALUE)
        )
    }
}
