package com.example.checkin.util

import android.Manifest
import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat

/**
 * 「后台运行保障」自检项：某一项系统能力是否已满足自动打卡的可靠运行要求。
 */
data class ReadinessItem(
    /** 是否已满足 */
    val ok: Boolean,
    /** 系统是否根本不需要该项（如 Android 11 及以下的精确闹钟权限） */
    val notRequired: Boolean,
    /** 状态说明（给用户看的一行话） */
    val detail: String,
    /** 一键跳转系统设置页（null 表示该项无需跳转，或跳到应用详情页） */
    val settingsIntent: Intent?
) {
    /** 是否需要用户处理 */
    val needsAction: Boolean get() = !ok && !notRequired
}

/**
 * 自动打卡「后台运行保障」自检。
 *
 * 自动打卡时段外完全静默、仅靠 AlarmManager 唤醒，因此以下四项系统能力
 * 任何一项不满足，都会表现为"开关开着但没打卡"的静默失效：
 * 1. 精确闹钟权限（Android 12+）——未授予时降级为非精确闹钟，边界可偏差数分钟；
 * 2. 电池优化白名单——被省电冻结时闹钟与定位都会被推迟（国产 ROM 尤其严格）；
 * 3. 后台定位权限（Android 10+）——Android 14+ 从后台启动 location 前台服务时的硬性要求；
 * 4. 通知权限（Android 13+）——没有通知用户无法察觉服务已被系统停止。
 */
object ReadinessChecks {

    /** 精确闹钟权限（Android 12+ 需要，其它版本恒为已满足） */
    fun exactAlarm(context: Context): ReadinessItem {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return ReadinessItem(
                ok = true, notRequired = true,
                detail = "当前系统无需该权限", settingsIntent = null
            )
        }
        val am = context.getSystemService(AlarmManager::class.java)
        val can = runCatching { am?.canScheduleExactAlarms() == true }.getOrDefault(false)
        return ReadinessItem(
            ok = can,
            notRequired = false,
            detail = if (can) "已授予，窗口边界可精确唤醒"
            else "未授予：打卡时段边界只能近似唤醒（可能偏差数分钟）",
            settingsIntent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                data = Uri.fromParts("package", context.packageName, null)
            }
        )
    }

    /** 电池优化白名单 */
    fun batteryOptimization(context: Context): ReadinessItem {
        val pm = context.getSystemService(PowerManager::class.java)
        val ignoring = runCatching {
            pm?.isIgnoringBatteryOptimizations(context.packageName) == true
        }.getOrDefault(false)
        return ReadinessItem(
            ok = ignoring,
            notRequired = false,
            detail = if (ignoring) "已加入白名单，后台不被省电冻结"
            else "未加入白名单：省电模式可能冻结闹钟与定位，导致漏打卡",
            settingsIntent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        )
    }

    /** 后台定位权限（Android 10+） */
    fun backgroundLocation(context: Context): ReadinessItem {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return ReadinessItem(
                ok = true, notRequired = true,
                detail = "当前系统无需该权限", settingsIntent = null
            )
        }
        val granted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_BACKGROUND_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        return ReadinessItem(
            ok = granted,
            notRequired = false,
            detail = if (granted) "已授予「始终允许」，后台可读取定位"
            else "未授予：息屏或后台时定位可能被拒绝（Android 14+ 还会阻止后台启动定位服务）",
            settingsIntent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", context.packageName, null)
            }
        )
    }

    /** 通知权限（Android 13+） */
    fun notifications(context: Context): ReadinessItem {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return ReadinessItem(
                ok = true, notRequired = true,
                detail = "当前系统无需该权限", settingsIntent = null
            )
        }
        val granted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
        return ReadinessItem(
            ok = granted,
            notRequired = false,
            detail = if (granted) "已授予，服务状态可见"
            else "未授予：自动打卡通知不可见，服务被系统停止时无法察觉",
            settingsIntent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            }
        )
    }

    /** 全部自检项（用于设置页「后台运行保障」卡片） */
    fun all(context: Context): List<ReadinessItem> = listOf(
        exactAlarm(context),
        batteryOptimization(context),
        backgroundLocation(context),
        notifications(context)
    )

    /** 需要用户处理的项数 */
    fun pendingCount(context: Context): Int = all(context).count { it.needsAction }
}
