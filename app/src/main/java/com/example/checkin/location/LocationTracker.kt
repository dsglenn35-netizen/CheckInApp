package com.example.checkin.location

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
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
 */
class LocationTracker(context: Context) {

    private val locationManager =
        context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    private val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)

    fun lastKnownLocation(): Location? =
        providers
            .mapNotNull { provider -> runCatching { locationManager.getLastKnownLocation(provider) }.getOrNull() }
            .maxByOrNull { it.time }

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
