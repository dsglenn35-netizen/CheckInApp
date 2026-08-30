package com.example.checkin.service

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.example.checkin.MainActivity
import com.example.checkin.R
import com.example.checkin.core.CheckInEngine
import com.example.checkin.data.AppDatabase
import com.example.checkin.data.CheckInRecord
import com.example.checkin.data.CheckInRepository
import com.example.checkin.data.CheckInRule
import com.example.checkin.location.LocationTracker
import com.example.checkin.util.AutoCheckInPrefs
import com.example.checkin.util.CheckInValidator
import com.example.checkin.util.formatTime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 自动打卡前台服务（智能省电）：
 * - 非打卡时段：仅启用低频网络定位（5 分钟一次），GPS 关闭，不访问数据库/网络；
 * - 打卡时段内：启用 GPS + 网络高精度定位（60 秒一次），满足地点立即自动打卡；
 * - 进入打卡时段前 90 秒：预热闹钟提前开启 GPS 获取定位（不打卡、不产生失败记录），
 *   到点切换后即可用新鲜定位立即打卡，避免 GPS 冷启动导致的首条记录延迟；
 * - 时间窗口通过规则实时判定，状态切换时动态调整定位模式，
 *   并由 AlarmManager 边界闹钟在窗口开始/结束时刻唤醒设备（不依赖低频轮询）。
 */
class AutoCheckInService : Service() {

    companion object {
        const val ACTION_START = "com.example.checkin.action.START_AUTO"
        const val ACTION_STOP = "com.example.checkin.action.STOP_AUTO"
        /** 边界闹钟触发：立即重新评估是否进入/离开打卡时段 */
        const val ACTION_REFRESH = "com.example.checkin.action.REFRESH_AUTO"
        /** 预热闹钟触发：进入打卡时段前提前开启 GPS，缩短冷启动定位耗时 */
        const val ACTION_PREWARM = "com.example.checkin.action.PREWARM_AUTO"
        /** 边界闹钟的 PendingIntent 请求码 */
        private const val ALARM_REQUEST_CODE = 1002
        /** 预热闹钟的 PendingIntent 请求码 */
        private const val ALARM_REQUEST_CODE_PREWARM = 1003
        /** 进入打卡时段前提前预热定位的时长（GPS 冷启动通常需 10s~2 分钟） */
        private const val PRE_WARM_MS = 90_000L

        private const val CHANNEL_ID = "auto_checkin_channel"
        private const val NOTIFICATION_ID = 1001

        /** 打卡时段内：时间检查间隔 */
        private const val CHECK_INTERVAL_INSIDE_MS = 60_000L
        /** 非打卡时段：时间检查间隔（省电） */
        private const val CHECK_INTERVAL_OUTSIDE_MS = 300_000L
        /** 打卡时段内：GPS+网络定位更新间隔 */
        private const val LOCATION_INTERVAL_INSIDE_MS = 60_000L
        /** 非打卡时段：仅网络定位更新间隔（省电） */
        private const val LOCATION_INTERVAL_OUTSIDE_MS = 300_000L
        /** 打卡时段内：移动触发距离 */
        private const val MIN_DISTANCE_INSIDE_M = 20f
        /** 非打卡时段：移动触发距离 */
        private const val MIN_DISTANCE_OUTSIDE_M = 50f

        fun start(context: Context) {
            val intent = Intent(context, AutoCheckInService::class.java).setAction(ACTION_START)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(
                Intent(context, AutoCheckInService::class.java).setAction(ACTION_STOP)
            )
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val handler = Handler(Looper.getMainLooper())

    private lateinit var repository: CheckInRepository
    private lateinit var engine: CheckInEngine
    private lateinit var locationManager: LocationManager

    @Volatile
    private var latestLocation: Location? = null

    /** 当前是否处于任一规则的打卡时段内 */
    @Volatile
    private var insideWindow = false

    /**
     * 是否处于定位预热：即将进入打卡时段（90 秒内），提前开启 GPS 获取新鲜定位，
     * 预热期间不打卡、不产生任何失败记录。
     */
    @Volatile
    private var prewarming = false

    /** 是否已注册 GPS 高精度定位 */
    private var gpsActive = false

    private var monitoring = false

    /** 周期性时间检查：动态间隔（时段内 60s，时段外 5min），作为闹钟的兜底 */
    private val checkRunnable = object : Runnable {
        override fun run() {
            if (!monitoring) return
            evaluateAndReschedule()
        }
    }

    /**
     * 统一评估入口：重新判断是否处于打卡时段，必要时切换定位模式，
     * 时段内执行一次自动打卡，最后重排 Handler 轮询与边界闹钟。
     */
    private fun evaluateAndReschedule() {
        scope.launch {
            var rules: List<CheckInRule> = emptyList()
            try {
                val now = System.currentTimeMillis()
                rules = repository.enabledRules()
                val newInside = rules.any { CheckInValidator.isWithinTime(it, now) }
                val newPrewarm = isPrewarmNeeded(rules, now, newInside)
                if (newInside != insideWindow || newPrewarm != prewarming) {
                    insideWindow = newInside
                    prewarming = newPrewarm
                    syncLocationMode()
                    refreshNotification()
                }
                if (insideWindow) {
                    val record = engine.autoCheckIn(location = latestLocation)
                    if (record != null) {
                        updateNotification(record)
                    }
                }
            } finally {
                rescheduleCheck()
                scheduleBoundaryAlarm(rules)
            }
        }
    }

    /**
     * 是否需要定位预热：尚未进入打卡时段，且 90 秒内将有一个"进入时段"边界。
     * 预热只针对从时段外进入时段的边界；离开时段的边界（或已在时段内）不预热。
     */
    private fun isPrewarmNeeded(
        rules: List<CheckInRule>,
        now: Long,
        alreadyInside: Boolean
    ): Boolean {
        if (alreadyInside) return false
        val next = CheckInValidator.nextBoundaryMillis(rules, now) ?: return false
        if (next - now > PRE_WARM_MS) return false
        // 该边界之后是否进入时段（边界 +1 秒判定，边界永远在整分 :00 秒）
        return rules.any { CheckInValidator.isWithinTime(it, next + 1_000L) }
    }

    /** 按当前模式重新调度下一次时间检查 */
    private fun rescheduleCheck() {
        if (!monitoring) return
        handler.postDelayed(
            checkRunnable,
            if (insideWindow) CHECK_INTERVAL_INSIDE_MS else CHECK_INTERVAL_OUTSIDE_MS
        )
    }

    /**
     * 在下一个规则窗口边界（开始/结束）安排闹钟，唤醒设备立即重新评估，
     * 避免省电模式下低频轮询错过切换时机（系统休眠时 Handler 消息会被推迟）。
     * 进入时段的边界会额外提前 [PRE_WARM_MS] 安排预热闹钟，提前开启 GPS 缩短定位耗时。
     * Android 12+ 若未授予精确闹钟权限则降级为 setAndAllowWhileIdle（免权限、仍可在休眠时触发）。
     */
    private fun scheduleBoundaryAlarm(rules: List<CheckInRule>) {
        val alarmManager = getSystemService(AlarmManager::class.java)
        val pi = refreshPendingIntent()
        val prewarmPi = prewarmPendingIntent()
        alarmManager.cancel(pi) // 替换旧闹钟
        alarmManager.cancel(prewarmPi)
        val next = CheckInValidator.nextBoundaryMillis(rules)
            ?: return // 无启用规则或无未来边界
        val now = System.currentTimeMillis()
        // 该边界之后是否进入时段（+1 秒判定）：是则提前预热定位
        val isStartBoundary = rules.any { CheckInValidator.isWithinTime(it, next + 1_000L) }
        if (isStartBoundary && next - now > 0) {
            setAlarm(prewarmPi, next - PRE_WARM_MS)
        }
        setAlarm(pi, next)
    }

    private fun setAlarm(pi: PendingIntent, triggerAt: Long) {
        val alarmManager = getSystemService(AlarmManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            alarmManager.canScheduleExactAlarms()
        ) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
        } else {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
        }
    }

    /** 边界闹钟触发的 PendingIntent：直接投递到本服务（前台服务已在运行） */
    private fun refreshPendingIntent(): PendingIntent =
        PendingIntent.getForegroundService(
            this, ALARM_REQUEST_CODE,
            Intent(this, AutoCheckInService::class.java).setAction(ACTION_REFRESH),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    /** 预热闹钟触发的 PendingIntent（请求码与边界闹钟不同，互不覆盖） */
    private fun prewarmPendingIntent(): PendingIntent =
        PendingIntent.getForegroundService(
            this, ALARM_REQUEST_CODE_PREWARM,
            Intent(this, AutoCheckInService::class.java).setAction(ACTION_PREWARM),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            latestLocation = location
            // 仅在打卡时段内做校验，避免时段外无谓的数据库/网络访问
            if (insideWindow) {
                runAutoCheck()
            }
        }

        @Deprecated("Deprecated in API 29")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}

        override fun onProviderEnabled(provider: String) {}

        override fun onProviderDisabled(provider: String) {}
    }

    override fun onCreate() {
        super.onCreate()
        repository = CheckInRepository(AppDatabase.get(applicationContext).checkInDao())
        engine = CheckInEngine(
            applicationContext,
            repository,
            LocationTracker(applicationContext)
        )
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_REFRESH, ACTION_PREWARM -> {
                // 边界/预热闹钟触发：仍在自动打卡状态则立即重新评估
                // （切换省电/打卡模式、预热定位并重排闹钟）
                if (!AutoCheckInPrefs.isEnabled(this)) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                startForegroundWithNotification()
                startMonitoring()
                evaluateAndReschedule()
            }
            else -> {
                // ACTION_START 或系统在进程被杀后重启（intent 为 null）
                if (intent == null && !AutoCheckInPrefs.isEnabled(this)) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                startForegroundWithNotification()
                startMonitoring()
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startMonitoring() {
        if (monitoring) return
        monitoring = true
        // 立即执行首次评估（含排闹钟），之后由 checkRunnable 周期兜底
        handler.post(checkRunnable)
    }

    /** 根据是否处于打卡时段/预热状态动态调整定位模式（仅模式变化时重注册，避免频繁操作） */
    @SuppressLint("MissingPermission")
    private fun syncLocationMode() {
        val wantGps = insideWindow || prewarming
        if (wantGps == gpsActive) return
        gpsActive = wantGps
        runCatching { locationManager.removeUpdates(locationListener) }
        if (wantGps) {
            // 时段内：GPS + 网络，60 秒一次
            registerProvider(LocationManager.GPS_PROVIDER, LOCATION_INTERVAL_INSIDE_MS, MIN_DISTANCE_INSIDE_M)
            registerProvider(LocationManager.NETWORK_PROVIDER, LOCATION_INTERVAL_INSIDE_MS, MIN_DISTANCE_INSIDE_M)
        } else {
            // 时段外：仅网络定位，5 分钟一次（省电）
            registerProvider(LocationManager.NETWORK_PROVIDER, LOCATION_INTERVAL_OUTSIDE_MS, MIN_DISTANCE_OUTSIDE_M)
        }
    }

    @SuppressLint("MissingPermission")
    private fun registerProvider(provider: String, minTime: Long, minDistance: Float) {
        if (runCatching { locationManager.isProviderEnabled(provider) }.getOrDefault(false)) {
            runCatching {
                locationManager.requestLocationUpdates(
                    provider, minTime, minDistance, locationListener, Looper.getMainLooper()
                )
            }
        }
    }

    private fun runAutoCheck() {
        scope.launch {
            val record = engine.autoCheckIn(location = latestLocation)
            if (record != null) {
                updateNotification(record)
            }
        }
    }

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, "自动打卡", NotificationManager.IMPORTANCE_LOW
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun startForegroundWithNotification() {
        val notification = buildNotification(null)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(
                this, NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(lastRecord: CheckInRecord?): Notification {
        val contentIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_check)
            .setContentTitle("自动打卡监控中")
            .setContentText(
                lastRecord?.let { "最近打卡：${it.ruleName} ${formatTime(it.timestamp)}" }
                    ?: when {
                        insideWindow -> "打卡时段内，正在监测定位…"
                        prewarming -> "即将进入打卡时段，正在预热定位…"
                        else -> "省电模式：非打卡时段，低频监测"
                    }
            )
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(record: CheckInRecord) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, buildNotification(record))
    }

    private fun refreshNotification() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, buildNotification(null))
    }

    override fun onDestroy() {
        monitoring = false
        handler.removeCallbacks(checkRunnable)
        runCatching {
            getSystemService(AlarmManager::class.java).apply {
                cancel(refreshPendingIntent())
                cancel(prewarmPendingIntent())
            }
        }
        if (::locationManager.isInitialized) {
            runCatching { locationManager.removeUpdates(locationListener) }
        }
        scope.cancel()
        super.onDestroy()
    }
}
