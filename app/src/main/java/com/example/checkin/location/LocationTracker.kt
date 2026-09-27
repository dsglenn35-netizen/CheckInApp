package com.example.checkin.location

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * 基于系统 LocationManager 的单次定位获取器。
 * 优先等待新鲜且精度较好的定位，超时后回退到最近的已知位置。
 * 同时提供当前 WiFi SSID，用于定位不可靠时的打卡兜底判定。
 */
class LocationTracker(context: Context) {

    private val appContext = context.applicationContext

    private val locationManager =
        appContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    private val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)

    fun lastKnownLocation(): Location? =
        providers
            .mapNotNull { provider -> runCatching { locationManager.getLastKnownLocation(provider) }.getOrNull() }
            .maxByOrNull { it.time }

    /**
     * 当前连接的 WiFi SSID（未连接或权限不足时返回 null）。
     *
     * 用于室内/GPS 漂移场景的地点兜底判定：用户连上公司 WiFi 时，
     * 即使坐标因漂移落在半径外，也可按已登记的 SSID 视为到达。
     *
     * Android 8.1+ 读取 SSID 需要定位权限（已声明）；Android 10+ 还要求定位开关打开。
     * `SSID` 在未连接时返回 `<unknown ssid>`，此处统一过滤为 null。
     */
    @SuppressLint("MissingPermission")
    fun currentWifiSsid(): String? = runCatching {
        val wm = appContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        @Suppress("DEPRECATION")
        val info = wm.connectionInfo ?: return null
        @Suppress("DEPRECATION")
        val raw = info.ssid ?: return null
        val ssid = raw.trim().trim('"')
        if (ssid.isEmpty() || ssid == WifiManager.UNKNOWN_SSID) null else ssid
    }.getOrNull()

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION") // requestLocationUpdates 同步版本在 API 30 标记废弃，仍可用且跨版本兼容
    suspend fun requestCurrentLocation(timeoutMs: Long = 10_000L): Location? =
        withContext(Dispatchers.IO) {
            val enabledProviders = providers.filter {
                runCatching { locationManager.isProviderEnabled(it) }.getOrDefault(false)
            }
            if (enabledProviders.isEmpty()) {
                return@withContext lastKnownLocation()
            }

            suspendCancellableCoroutine { continuation ->
                val mainHandler = Handler(Looper.getMainLooper())
                var done = false
                var fallback: Location? = lastKnownLocation()
                var listener: LocationListener? = null
                var timeoutRunnable: Runnable? = null

                // 无论正常完成还是取消，都必须注销定位监听，否则会持续耗电
                fun cleanup() {
                    listener?.let { l ->
                        enabledProviders.forEach { provider ->
                            runCatching { locationManager.removeUpdates(l) }
                        }
                    }
                    timeoutRunnable?.let { mainHandler.removeCallbacks(it) }
                }

                fun finish(location: Location?) {
                    if (done) return
                    done = true
                    cleanup()
                    if (continuation.isActive) continuation.resume(location)
                }

                val locationListener = object : LocationListener {
                    override fun onLocationChanged(location: Location) {
                        if (location.accuracy <= 100f) {
                            finish(location)
                        } else if (fallback == null || location.accuracy < fallback!!.accuracy) {
                            fallback = location
                        }
                    }

                    @Deprecated("Deprecated in API 29")
                    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}

                    override fun onProviderEnabled(provider: String) {}

                    override fun onProviderDisabled(provider: String) {}
                }
                listener = locationListener

                enabledProviders.forEach { provider ->
                    runCatching {
                        locationManager.requestLocationUpdates(
                            provider, 0L, 0f, locationListener, Looper.getMainLooper()
                        )
                    }
                }

                val runnable = Runnable { finish(fallback) }
                timeoutRunnable = runnable
                mainHandler.postDelayed(runnable, timeoutMs)

                continuation.invokeOnCancellation { cleanup() }
            }
        }
}
