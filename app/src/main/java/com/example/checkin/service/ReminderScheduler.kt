package com.example.checkin.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.example.checkin.data.AppDatabase
import com.example.checkin.util.ReminderPlan
import com.example.checkin.util.ReminderPlanner
import com.example.checkin.util.ReminderPrefs

/**
 * 「打卡提醒」闹钟的排程。
 *
 * 与自动打卡的边界闹钟分开管理，理由：
 * - 提醒要在**窗口开始之前**触发（如 08:45 提醒 09:00 上班卡），落在打卡时段之外，
 *   而自动打卡在时段外是刻意完全静默的（不注册定位、不轮询），不能把提醒塞进它的轮询里；
 * - 提醒只读数据库、只发通知，**不需要任何定位权限**，因此自动打卡关闭时也应能独立工作。
 *
 * 全局只有一个待触发提醒（下一次），每次重排都用同一个 PendingIntent 请求码覆盖，
 * 因此不会堆积闹钟；关闭提醒时取消该闹钟。
 */
object ReminderScheduler {

    private const val TAG = "ReminderScheduler"

    /** 提醒闹钟的 PendingIntent 请求码（与自动打卡的 1002 / 1003 错开） */
    private const val REQUEST_CODE = 1004

    internal const val ACTION_REMINDER = "com.example.checkin.action.REMINDER"

    /**
     * 重新计算并安排下一次提醒。
     *
     * 提醒关闭、没有启用规则或没有未来提醒时取消已有闹钟
     * （否则用户关掉提醒后仍会收到已排下的那一条）。
     * 全程不抛出：提醒属**尽力而为**的辅助能力，失败绝不能影响打卡本身，
     * 也不能让调用方（开机接收器、ViewModel）因此崩溃。
     */
    suspend fun reschedule(context: Context) {
        val app = context.applicationContext
        runCatching {
            if (!ReminderPrefs.isEnabled(app)) {
                cancel(app)
                return
            }
            val dao = AppDatabase.get(app).checkInDao()
            val rules = dao.enabledRules()
            val skipped = dao.allLeaveDays().map { it.date }.toSet()
            val plan = ReminderPlanner.nextReminder(
                rules = rules,
                leadMillis = ReminderPrefs.leadMillis(app),
                skippedDates = skipped
            )
            if (plan == null) {
                cancel(app)
                return
            }
            setAlarm(app, plan)
        }.onFailure { Log.w(TAG, "重排打卡提醒失败", it) }
    }

    /**
     * 取消待触发的提醒闹钟。
     *
     * 这里用**不带 extras** 的 Intent 取消：PendingIntent 的匹配只看
     * action / component / data / requestCode（extras 不参与 filterEquals），
     * 所以能精确命中排程时用的那个 PendingIntent。
     */
    fun cancel(context: Context) {
        val app = context.applicationContext
        runCatching {
            val alarmManager = app.getSystemService(AlarmManager::class.java) ?: return
            alarmManager.cancel(pendingIntent(app, baseIntent(app)))
        }.onFailure { Log.w(TAG, "取消打卡提醒失败", it) }
    }

    private fun setAlarm(context: Context, plan: ReminderPlan) {
        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
        val intent = baseIntent(context)
            .putExtra(ReminderReceiver.EXTRA_RULE_ID, plan.ruleId)
            .putExtra(ReminderReceiver.EXTRA_RULE_NAME, plan.ruleName)
            .putExtra(ReminderReceiver.EXTRA_SLOT, plan.slot.name)
            .putExtra(ReminderReceiver.EXTRA_SLOT_FROM, plan.slotFrom)
            .putExtra(ReminderReceiver.EXTRA_SLOT_TO, plan.slotTo)
            .putExtra(ReminderReceiver.EXTRA_WINDOW_START, plan.windowStart)
            .putExtra(ReminderReceiver.EXTRA_WINDOW_END, plan.windowEnd)
        val pi = pendingIntent(context, intent)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                !alarmManager.canScheduleExactAlarms()
            ) {
                // 未授予精确闹钟权限：降级为非精确（可能延后几分钟），提醒仍会到达
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, plan.triggerAt, pi)
            } else {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP, plan.triggerAt, pi
                )
            }
        } catch (e: SecurityException) {
            // Android 12+ 在权限被撤销的竞态下仍可能拒绝精确闹钟，退一步用非精确
            Log.w(TAG, "精确闹钟被拒绝，降级为非精确", e)
            runCatching {
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, plan.triggerAt, pi)
            }
        }
    }

    private fun baseIntent(context: Context): Intent =
        Intent(context, ReminderReceiver::class.java).setAction(ACTION_REMINDER)

    private fun pendingIntent(context: Context, intent: Intent): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
}
