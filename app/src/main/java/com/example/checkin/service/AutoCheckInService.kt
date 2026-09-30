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
import android.util.Log
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
import com.example.checkin.data.CheckStatus
import com.example.checkin.location.LocationTracker
import com.example.checkin.util.AutoCheckInPrefs
import com.example.checkin.util.CheckInFeedback
import com.example.checkin.util.CheckInValidator
import com.example.checkin.util.FeedbackPrefs
import com.example.checkin.util.formatTime
import com.example.checkin.util.matchSourceLabel
import com.example.checkin.util.statusLabel
import com.example.checkin.util.toLocalDate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 自动打卡前台服务（智能省电）：
 * - 打卡时段内：启用 GPS + 网络高精度定位（60 秒一次），满足地点立即自动打卡；
 * - 进入打卡时段前 90 秒：预热闹钟提前开启 GPS 获取定位（不打卡、不产生失败记录），
 *   到点切换后即可用新鲜定位立即打卡，避免 GPS 冷启动导致的首条记录延迟；
 * - 时段外（如 18:00 下班后到次日上班前）：**完全静默**——不注册任何定位监听、
 *   不安排轮询检查，仅由 AlarmManager 边界闹钟在下一个窗口开始/结束时刻唤醒设备，
 *   夜间零扫描、零唤醒，最大化省电；规则增删改时会主动触发一次重评估（见 [refresh]），
 *   避免静默期规则变更无法及时生效。
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

        private const val TAG = "AutoCheckInService"

        private const val CHANNEL_ID = "auto_checkin_channel"
        /** 服务无法在前台运行时用于提示用户的告警通道 */
        private const val ALERT_CHANNEL_ID = "auto_checkin_alert_channel"
        private const val NOTIFICATION_ID = 1001
        private const val ALERT_NOTIFICATION_ID = 1002

        /**
         * 打卡结果通知：成功/失败时**单独**弹一条。
         * 常驻通知只写"自动打卡监控中"，用户不会盯着它看，结果必须主动告知。
         */
        private const val RESULT_CHANNEL_ID = "auto_checkin_result_channel"
        private const val RESULT_NOTIFICATION_ID = 1003

        /** 打卡时段内：时间检查间隔 */
        private const val CHECK_INTERVAL_INSIDE_MS = 60_000L
        /** 打卡时段内：GPS+网络定位更新间隔 */
        private const val LOCATION_INTERVAL_INSIDE_MS = 60_000L
        /** 打卡时段内：移动触发距离 */
        private const val MIN_DISTANCE_INSIDE_M = 20f

        fun start(context: Context) {
            val intent = Intent(context, AutoCheckInService::class.java).setAction(ACTION_START)
            startForegroundServiceSafely(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(
                Intent(context, AutoCheckInService::class.java).setAction(ACTION_STOP)
            )
        }

        /**
         * 规则发生变化后调用：让运行中的服务立即重排边界闹钟与定位模式，
         * 使静默期（时段外无轮询）也能及时感知新规则。
         * 自动打卡未开启时是空操作。
         */
        fun refresh(context: Context) {
            if (!AutoCheckInPrefs.isEnabled(context)) return
            val intent = Intent(context, AutoCheckInService::class.java).setAction(ACTION_REFRESH)
            startForegroundServiceSafely(context, intent)
        }

        /**
         * 启动前台服务并吸收系统拒绝启动的异常。
         *
         * Android 12+ 限制应用在后台启动前台服务，Android 14+ 对 location 类型
         * 前台服务额外要求后台定位权限。边界闹钟唤醒（应用处于后台）触发启动时，
         * 若条件不满足会抛 [android.app.ForegroundServiceStartNotAllowedException]
         * （或 SecurityException），未捕获会直接崩溃。这里统一兜住并提示用户，
         * 保证"闹钟唤醒失败"只表现为一条可感知的提醒，而不是 crash。
         */
        private fun startForegroundServiceSafely(context: Context, intent: Intent) {
            try {
                ContextCompat.startForegroundService(context, intent)
            } catch (e: Exception) {
                notifyStartBlocked(context, e)
            }
        }

        /**
         * 前台服务无法启动时的用户提示。
         * 多数国产 ROM 在没有后台定位/白名单时会走到这里，用户需要打开应用一次
         * 才能恢复正常监控，因此必须让用户看得见，而不是静默失效。
         */
        private fun notifyStartBlocked(context: Context, error: Exception) {
            runCatching {
                val nm = context.getSystemService(NotificationManager::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    nm.createNotificationChannel(
                        NotificationChannel(
                            ALERT_CHANNEL_ID, "自动打卡提醒", NotificationManager.IMPORTANCE_DEFAULT
                        )
                    )
                }
                val contentIntent = PendingIntent.getActivity(
                    context, 0, Intent(context, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE
                )
                val notification = NotificationCompat.Builder(context, ALERT_CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_stat_check)
                    .setContentTitle("自动打卡未能启动")
                    .setContentText("系统限制了后台启动，请打开应用并检查「后台运行保障」")
                    .setStyle(
                        NotificationCompat.BigTextStyle().bigText(
                            "系统限制了后台启动自动打卡服务（${error.javaClass.simpleName}）。" +
                                "请打开应用一次，并在 设置 → 后台运行保障 中授予后台定位与电池优化白名单。"
                        )
                    )
                    .setContentIntent(contentIntent)
                    .setAutoCancel(true)
                    .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                    .build()
                nm.notify(ALERT_NOTIFICATION_ID, notification)
            }
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

    /**
     * 今天是否被标记为**全天**请假 / 放假。
     * 标记当天与"非打卡时段"同等处理：不注册定位、不轮询、不写任何记录，
     * 否则会在整个规则窗口内白白开着 GPS 却每次都判定为空转。
     */
    @Volatile
    private var dayOff = false

    /** 是否已注册 GPS 高精度定位 */
    private var gpsActive = false

    private var monitoring = false

    /** 周期性时间检查：仅在打卡时段内运行（60s），时段外静默不轮询，由边界闹钟唤醒 */
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
            // 规则读取失败时**保留现有闹钟**：scheduleBoundaryAlarm 会先取消旧闹钟，
            // 若此时用空列表重排，取消后不会再设新闹钟；而时段外完全静默、只靠闹钟唤醒，
            // 这等于让自动打卡永久失效，直到用户下次手动打开应用。
            val rules = try {
                repository.enabledRules()
            } catch (t: Throwable) {
                Log.w(TAG, "读取打卡规则失败，保留现有调度", t)
                rescheduleCheck()
                return@launch
            }
            // 调班 / 调休：自动打卡的时段判断必须与打卡判定共用同一张覆盖表，
            // 否则会出现"调班当天人打得上班、服务却认为自己不在时段内"的静默失效
            val overrides = repository.shiftOverrideTable()
            try {
                val now = System.currentTimeMillis()
                // 全天请假 / 公司放假：当天不需要打卡，按"非打卡时段"处理——
                // 不注册定位、不轮询、不写记录，与时段外一样静默省电。
                val newDayOff = repository.leaveDay(now.toLocalDate().toString()) != null
                val newInside = !newDayOff &&
                    rules.any { CheckInValidator.isWithinTime(it, now, overrides) }
                val newPrewarm = !newDayOff &&
                    isPrewarmNeeded(rules, now, newInside, overrides)
                if (newInside != insideWindow || newPrewarm != prewarming || newDayOff != dayOff) {
                    dayOff = newDayOff
                    insideWindow = newInside
                    prewarming = newPrewarm
                    syncLocationMode()
                    refreshNotification()
                }
                if (insideWindow) {
                    val record = engine.autoCheckIn(location = latestLocation)
                    if (record != null) {
                        handleResult(record)
                    }
                }
            } catch (t: Throwable) {
                // 服务内未捕获的协程异常会交给默认 UncaughtExceptionHandler 直接崩溃进程
                Log.w(TAG, "自动打卡评估失败", t)
            } finally {
                rescheduleCheck()
                scheduleBoundaryAlarm(rules, overrides)
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
        alreadyInside: Boolean,
        overrides: Map<String, Boolean> = emptyMap()
    ): Boolean {
        if (alreadyInside) return false
        val next = CheckInValidator.nextBoundaryMillis(rules, now, overrides) ?: return false
        if (next - now > PRE_WARM_MS) return false
        // 该边界之后是否进入时段（边界 +1 秒判定，边界永远在整分 :00 秒）
        return rules.any { CheckInValidator.isWithinTime(it, next + 1_000L, overrides) }
    }

    /**
     * 调度下一次时间检查：仅打卡时段内排 60s 轮询；
     * 时段外不排任何轮询（完全静默），依靠边界/预热闹钟在窗口时刻唤醒设备。
     */
    private fun rescheduleCheck() {
        if (!monitoring || !insideWindow) return
        handler.postDelayed(checkRunnable, CHECK_INTERVAL_INSIDE_MS)
    }

    /**
     * 在下一个规则窗口边界（开始/结束）安排闹钟，唤醒设备立即重新评估，
     * 避免省电模式下低频轮询错过切换时机（系统休眠时 Handler 消息会被推迟）。
     * 进入时段的边界会额外提前 [PRE_WARM_MS] 安排预热闹钟，提前开启 GPS 缩短定位耗时。
     * Android 12+ 若未授予精确闹钟权限则降级为 setAndAllowWhileIdle（免权限、仍可在休眠时触发）。
     */
    private fun scheduleBoundaryAlarm(
        rules: List<CheckInRule>,
        overrides: Map<String, Boolean> = emptyMap()
    ) {
        val now = System.currentTimeMillis()
        // 先算边界、再动旧闹钟：若先取消、后因「无未来边界」而 return，
        // 会把既有调度一并清空；时段外完全静默，等于自动打卡永久失效。
        val next = CheckInValidator.nextBoundaryMillis(rules, now, overrides)
        val alarmManager = getSystemService(AlarmManager::class.java)
        val pi = refreshPendingIntent()
        val prewarmPi = prewarmPendingIntent()
        alarmManager.cancel(pi) // 替换旧闹钟
        alarmManager.cancel(prewarmPi)
        if (next == null) return // 无启用规则或无未来边界：确需停止调度
        // 该边界之后是否进入时段（+1 秒判定）：是则提前预热定位
        val isStartBoundary = rules.any {
            CheckInValidator.isWithinTime(it, next + 1_000L, overrides)
        }
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
        repository = CheckInRepository(AppDatabase.get(applicationContext))
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
                if (!startForegroundWithNotification()) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                startMonitoring()
                evaluateAndReschedule()
            }
            else -> {
                // ACTION_START 或系统在进程被杀后重启（intent 为 null）
                if (intent == null && !AutoCheckInPrefs.isEnabled(this)) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                if (!startForegroundWithNotification()) {
                    stopSelf()
                    return START_NOT_STICKY
                }
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

    /**
     * 根据是否处于打卡时段/预热状态动态调整定位模式（仅模式变化时重注册，避免频繁操作）。
     * - 时段内或预热中：GPS + 网络高精度定位；
     * - 其余时间（下班后等）：**不注册任何定位监听**，完全静默省电。
     */
    @SuppressLint("MissingPermission")
    private fun syncLocationMode() {
        val wantGps = insideWindow || prewarming
        if (wantGps == gpsActive) return
        gpsActive = wantGps
        runCatching { locationManager.removeUpdates(locationListener) }
        if (wantGps) {
            // 时段内/预热：GPS + 网络，60 秒一次
            registerProvider(LocationManager.GPS_PROVIDER, LOCATION_INTERVAL_INSIDE_MS, MIN_DISTANCE_INSIDE_M)
            registerProvider(LocationManager.NETWORK_PROVIDER, LOCATION_INTERVAL_INSIDE_MS, MIN_DISTANCE_INSIDE_M)
        }
        // 时段外：静默，不注册任何定位
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
            try {
                val record = engine.autoCheckIn(location = latestLocation)
                if (record != null) {
                    handleResult(record)
                }
            } catch (t: Throwable) {
                Log.w(TAG, "自动打卡执行失败", t)
            }
        }
    }

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "自动打卡", NotificationManager.IMPORTANCE_LOW)
        )
        // 启动被系统阻止时用于提示用户的告警通道（BootReceiver 也会用到同名 ID）
        nm.createNotificationChannel(
            NotificationChannel(
                ALERT_CHANNEL_ID, "自动打卡提醒", NotificationManager.IMPORTANCE_DEFAULT
            )
        )
        // 打卡结果通道：IMPORTANCE_HIGH 才会横幅弹出，否则"打上卡了"这件事会被无声吞掉
        nm.createNotificationChannel(
            NotificationChannel(
                RESULT_CHANNEL_ID, "打卡结果", NotificationManager.IMPORTANCE_HIGH
            ).apply { description = "自动打卡成功或失败的结果通知" }
        )
    }

    /**
     * 进入前台并显示常驻通知。
     *
     * 可能抛出的异常在此统一处理：Android 14+ 对 location 类型前台服务要求
     * 后台定位权限，条件不满足时 [startForeground] 会失败；此时必须结束服务，
     * 否则系统会在 5 秒内抛出 ANR/Crash。返回 false 表示未能进入前台。
     */
    private fun startForegroundWithNotification(): Boolean {
        val notification = buildNotification(null)
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceCompat.startForeground(
                    this, NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            true
        } catch (e: Exception) {
            // 已进入前台但类型不被允许时，先退出前台再结束服务，避免系统强杀
            runCatching { ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE) }
            runCatching {
                getSystemService(NotificationManager::class.java)
                    .notify(ALERT_NOTIFICATION_ID, buildBlockedNotification(e))
            }
            false
        }
    }

    /** 前台服务类型被系统拒绝时的告警通知 */
    private fun buildBlockedNotification(error: Exception): Notification {
        val contentIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, ALERT_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_check)
            .setContentTitle("自动打卡无法在后台运行")
            .setContentText("请在 设置 → 后台运行保障 中授予后台定位与电池优化白名单")
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    "系统拒绝了自动打卡服务的定位类型启动（${error.javaClass.simpleName}）。" +
                        "Android 14 起，从后台启动定位服务必须授予「始终允许」定位权限。" +
                        "请打开应用并在 设置 → 后台运行保障 中逐项处理。"
                )
            )
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
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
                        dayOff -> "今日已标记请假 / 放假，无需打卡，已暂停检测"
                        insideWindow -> "打卡时段内，正在监测定位…"
                        prewarming -> "即将进入打卡时段，正在预热定位…"
                        else -> "静默模式：非打卡时段，已暂停检测"
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

    /**
     * 自动打卡产生了新记录（成功或失败）：更新常驻通知，并给出**主动反馈**。
     *
     * 为什么必须有反馈：自动打卡发生时用户并不在看手机，"到底打上了没有"必须有明确交代，
     * 否则只能事后打开应用翻记录 —— 这正是钉钉打完卡要弹通知、响一声的原因。
     * 失败记录在 [CheckInEngine] 里有 30 分钟冷却，因此不会刷屏。
     */
    private fun handleResult(record: CheckInRecord) {
        updateNotification(record)
        val success = record.status == CheckStatus.SUCCESS.name
        if (FeedbackPrefs.resultNotifyEnabled(this)) {
            notifyResult(record, success)
        }
        CheckInFeedback.play(this, success)
    }

    /** 打卡结果通知：成功/失败各一条，点击打开应用 */
    private fun notifyResult(record: CheckInRecord, success: Boolean) {
        val appLabel = record.ruleName ?: "未命中规则"
        val source = matchSourceLabel(record.matchSource)
        val detail = formatTime(record.timestamp) + " · " + appLabel +
            (if (source != null) " · " + source else "")
        val contentIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, RESULT_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_check)
            .setContentTitle(if (success) "打卡成功" else "打卡失败")
            .setContentText(detail)
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText(detail + "\n" + statusLabel(record.status))
            )
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        runCatching {
            getSystemService(NotificationManager::class.java)
                .notify(RESULT_NOTIFICATION_ID, notification)
        }
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
