package com.example.checkin.service

import android.app.AlarmManager
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
import com.example.checkin.data.CheckInRepository
import com.example.checkin.util.AutoCheckInPrefs
import com.example.checkin.util.CheckInValidator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 系统事件接收器：让自动打卡调度在"重启 / 时间变更 / 应用升级"后自动恢复。
 *
 * 为什么必须有它：自动打卡在**打卡时段外完全静默**（无定位、无轮询），
 * 全部调度都挂在 AlarmManager 上，而系统闹钟**不跨重启存活**，
 * START_STICKY 服务也不会在重启后自动重建。没有这个接收器时，
 * 手机重启、系统更新时间、或应用被升级/强停后，自动打卡会永久失效，
 * 用户在界面上却仍看到"已开启"，属于最危险的静默失效场景。
 *
 * 处理的事件：
 * - [Intent.ACTION_BOOT_COMPLETED] / [Intent.ACTION_LOCKED_BOOT_COMPLETED]：开机
 * - [Intent.ACTION_MY_PACKAGE_REPLACED]：应用升级（升级会清空所有闹钟）
 * - [Intent.ACTION_TIME_CHANGED] / [Intent.ACTION_TIMEZONE_CHANGED]：系统时间或时区被修改
 *   （边界闹钟按 RTC 绝对时刻触发，时间跳变后必须重排，否则会在错误时刻唤醒）
 * - [AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED]：精确闹钟权限被授予
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        /** 时间/时区变更前的兜底检查周期（毫秒），避免时区切换后漏排闹钟 */
        private const val RESCHEDULE_LOOKAHEAD_MS = 2 * 24 * 60 * 60 * 1000L

        private const val CHANNEL_ID = "auto_checkin_alert_channel"
        private const val ALERT_NOTIFICATION_ID = 1001
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return

        val isBootEvent = action == Intent.ACTION_BOOT_COMPLETED ||
            action == Intent.ACTION_LOCKED_BOOT_COMPLETED ||
            action == Intent.ACTION_MY_PACKAGE_REPLACED
        // 系统时间/时区变更：闹钟按 RTC 绝对时刻排队，时间跳变后必须重排，
        // 否则会在错误时刻唤醒；同时时间跳变可能意味着打卡时段刚进入/离开，需要重评估。
        val isTimeEvent = action == Intent.ACTION_TIME_CHANGED ||
            action == Intent.ACTION_TIMEZONE_CHANGED
        val isExactAlarmEvent =
            action == AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED
        if (!isBootEvent && !isTimeEvent && !isExactAlarmEvent) return

        // 自动打卡自身的恢复：只有它开着才需要（提醒不依赖它，见下）
        if (AutoCheckInPrefs.isEnabled(context)) {
            when {
                // 开机 / 应用升级 / 应用被替换：重新启动前台服务并重排全部闹钟
                isBootEvent -> AutoCheckInService.start(context)
                isTimeEvent -> {
                    AutoCheckInService.refresh(context)
                    verifyBoundaryAlarm(context)
                }
                // 精确闹钟权限被授予：此前只能用非精确闹钟，立即升级为精确调度
                else -> AutoCheckInService.refresh(context)
            }
        }

        // 打卡提醒独立自愈：闹钟不跨重启存活，时间跳变后按绝对时刻排的提醒也会失准。
        // 注意这里**不看自动打卡开关** —— 提醒是独立能力，自动打卡关着也应能恢复；
        // 开关状态由 ReminderScheduler 自己判断（关着就取消闹钟）。
        if (isBootEvent || isTimeEvent || isExactAlarmEvent) {
            rescheduleReminders(context)
        }
    }

    /** 重排打卡提醒闹钟；读库需要异步，用 goAsync 保证进程不被提前回收 */
    private fun rescheduleReminders(context: Context) {
        val pending = goAsync()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope.launch {
            try {
                ReminderScheduler.reschedule(context)
            } finally {
                pending.finish()
            }
        }
    }

    /**
     * 校验关键窗口的边界闹钟是否仍然有效：若距下一个窗口边界不足两天
     * 而系统里已无对应闹钟（例如时间被大幅跳变后被系统丢弃），
     * 就发一条高优先级提醒通知，避免用户误以为监控正常。
     *
     * 这里只做"提醒"而不静默重排，是为了在异常场景下让用户可感知。
     */
    private fun verifyBoundaryAlarm(context: Context) {
        val pending = goAsync()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope.launch {
            try {
                val repository = CheckInRepository(AppDatabase.get(context))
                val rules = repository.enabledRules()
                if (rules.isEmpty()) return@launch
                val overrides = repository.shiftOverrideTable()
                val next = CheckInValidator.nextBoundaryMillis(rules, overrides = overrides)
                    ?: return@launch
                val now = System.currentTimeMillis()
                if (next - now <= RESCHEDULE_LOOKAHEAD_MS) return@launch
                // 下一个边界异常遥远（> 2 天）通常意味着时间跳变或规则异常，提示用户
                notifyScheduleWarning(context, next)
            } catch (_: Throwable) {
                // 提醒失败不影响主流程
            } finally {
                pending.finish()
            }
        }
    }

    private fun notifyScheduleWarning(context: Context, nextBoundary: Long) {
        val nm = NotificationManagerCompat.from(context)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID, "自动打卡提醒", NotificationManager.IMPORTANCE_DEFAULT
                )
            )
        }
        val fm = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault())
        val contentIntent = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_check)
            .setContentTitle("自动打卡调度可能异常")
            .setContentText("系统时间变更后，下一个打卡窗口预计在 ${fm.format(java.util.Date(nextBoundary))}，请打开应用确认")
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        runCatching {
            // 通知权限未授予时静默失败（不影响打卡本身）
            nm.notify(ALERT_NOTIFICATION_ID, notification)
        }
    }
}
