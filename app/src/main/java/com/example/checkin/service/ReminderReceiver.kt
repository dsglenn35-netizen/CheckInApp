package com.example.checkin.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.example.checkin.MainActivity
import com.example.checkin.R
import com.example.checkin.data.AppDatabase
import com.example.checkin.util.CheckInValidator
import com.example.checkin.util.PunchSlot
import com.example.checkin.util.ReminderPrefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.ZoneId
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 打卡提醒闹钟触发后的处理：**先复核，再提醒**。
 *
 * 闹钟是在排程时就定好的，触发时现场情况可能已经变了，因此必须复核：
 * 1. 提醒总开关是否已被关闭（关掉后已排下的那一条不应再弹）；
 * 2. 该槽位是否**已经打过卡** —— 已经打了就不该再提醒，这是钉钉"打卡提醒"的核心语义；
 * 3. 当天是否已被标记为**请假 / 放假**（标记往往是后补的），或者窗口时刻落在
 *    **时间段请假**内 —— 与自动打卡"该日静默"的口径保持一致；
 * 4. 提醒是否已经**过期**（非精确闹钟可能延后触发）：窗口都结束了再提醒毫无意义。
 *
 * 提醒只是通知，**不产生任何打卡记录**。处理完无论走哪条分支，都要重排下一次提醒。
 */
class ReminderReceiver : BroadcastReceiver() {

    companion object {
        const val EXTRA_RULE_ID = "rule_id"
        const val EXTRA_RULE_NAME = "rule_name"
        const val EXTRA_SLOT = "slot"
        const val EXTRA_SLOT_FROM = "slot_from"
        const val EXTRA_SLOT_TO = "slot_to"
        const val EXTRA_WINDOW_START = "window_start"
        const val EXTRA_WINDOW_END = "window_end"

        /** 打卡提醒通道：用 HIGH 让提醒以横幅弹出，否则"提醒"容易被忽略 */
        private const val CHANNEL_ID = "checkin_reminder_channel"
        private const val CHANNEL_NAME = "打卡提醒"
        private const val NOTIFICATION_ID = 2001
    }

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        if (!ReminderPrefs.isEnabled(app)) return
        if (intent.action != ReminderScheduler.ACTION_REMINDER) return

        val ruleId = intent.getLongExtra(EXTRA_RULE_ID, 0L)
        val ruleName = intent.getStringExtra(EXTRA_RULE_NAME) ?: return
        val slot = if (intent.getStringExtra(EXTRA_SLOT) == PunchSlot.OUT.name) {
            PunchSlot.OUT
        } else {
            PunchSlot.IN
        }
        val slotFrom = intent.getLongExtra(EXTRA_SLOT_FROM, 0L)
        val slotTo = intent.getLongExtra(EXTRA_SLOT_TO, 0L)
        val windowStart = intent.getLongExtra(EXTRA_WINDOW_START, 0L)
        val windowEnd = intent.getLongExtra(EXTRA_WINDOW_END, 0L)
        val now = System.currentTimeMillis()
        // 过期提醒（非精确闹钟延后触发）：窗口都结束了，提醒已无意义
        if (slotTo <= 0L || now >= slotTo) {
            rescheduleAsync(app)
            return
        }

        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val dao = AppDatabase.get(app).checkInDao()
                // 窗口开始日 = 该班次的"考勤日"，请假/放假标记也按这一天记
                val dayKey = Instant.ofEpochMilli(windowStart)
                    .atZone(ZoneId.systemDefault())
                    .toLocalDate()
                    .toString()
                // 请假 / 放假当天不提醒
                if (dao.leaveDay(dayKey) != null) return@launch
                // 时间段请假覆盖到窗口开始时刻时不提醒
                val minuteOfDay = minuteOfDay(windowStart)
                if (CheckInValidator.isWithinTimeOff(
                        dao.timeEntriesForDate(dayKey), minuteOfDay
                    )
                ) {
                    return@launch
                }
                // 该槽位已打卡 → 不提醒
                if (dao.lastSuccessForRuleInRange(ruleId, ruleName, slotFrom, slotTo) != null) {
                    return@launch
                }
                notifyReminder(app, ruleName, slot, windowStart, windowEnd, now)
            } catch (_: Throwable) {
                // 复核失败不提醒，也不影响下一次排程
            } finally {
                runCatching { ReminderScheduler.reschedule(app) }
                pending.finish()
            }
        }
    }

    /** 提前返回的分支也要把下一次提醒排上，否则提醒只会响一次 */
    private fun rescheduleAsync(context: Context) {
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                ReminderScheduler.reschedule(context)
            } finally {
                pending.finish()
            }
        }
    }

    private fun minuteOfDay(millis: Long): Int {
        val cal = Calendar.getInstance().apply { timeInMillis = millis }
        return cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
    }

    private fun notifyReminder(
        context: Context,
        ruleName: String,
        slot: PunchSlot,
        windowStart: Long,
        windowEnd: Long,
        now: Long
    ) {
        val nm = NotificationManagerCompat.from(context)
        // 通知权限未授予时静默跳过（不影响打卡，也不反复试探）
        if (!nm.areNotificationsEnabled()) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    CHANNEL_NAME,
                    NotificationManager.IMPORTANCE_HIGH
                ).apply { description = "上下班打卡前的提醒" }
            )
        }

        val reference = if (slot == PunchSlot.IN) windowStart else windowEnd
        val slotText = if (slot == PunchSlot.IN) "上班卡" else "下班卡"
        val edgeText = if (slot == PunchSlot.IN) "开始" else "结束"
        val timeText = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(reference))
        val minutesLeft = ((reference - now) / 60_000L).coerceAtLeast(0L)
        val title = if (slot == PunchSlot.IN) "记得打上班卡" else "记得打下班卡"
        val body = if (minutesLeft > 0) {
            "「$ruleName」$slotText $timeText $edgeText，还有 $minutesLeft 分钟"
        } else {
            "「$ruleName」$slotText $timeText $edgeText，别忘了打卡"
        }

        val contentIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_check)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    "$body。打开应用即可一键打卡；已打卡或已请假请忽略本条提醒。"
                )
            )
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        runCatching { nm.notify(NOTIFICATION_ID, notification) }
    }
}
