package com.time.gps

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.TrafficStats
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

class GPSService : Service() {
    private lateinit var locationManager: LocationManager
    private val handler = Handler(Looper.getMainLooper())
    var lastLocation: Location? = null
        private set
    private val locationListeners = mutableListOf<(Location) -> Unit>()
    private lateinit var webServer: WebServer

    // 流量统计相关变量
    private var lastTotalRxBytes: Long = 0
    private var lastTotalTxBytes: Long = 0
    private var lastUpdateTime: Long = 0
    private val updateInterval = 1000L // 1秒更新一次

    companion object {
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "ServiceChannel"
        private const val TAG = "Service"
    }

    inner class LocalBinder : Binder() {
        fun getService(): GPSService = this@GPSService
    }

    private val binder = LocalBinder()

    override fun onBind(intent: Intent): IBinder {
        return binder
    }

    fun addLocationListener(listener: (Location) -> Unit) {
        locationListeners.add(listener)
    }

    fun removeLocationListener(listener: (Location) -> Unit) {
        locationListeners.remove(listener)
    }

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            lastLocation = location
            locationListeners.forEach { it(location) }
        }

        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    // 获取流量统计
    private fun getTrafficStats(): Pair<String, String> {
        val currentRxBytes = TrafficStats.getTotalRxBytes()
        val currentTxBytes = TrafficStats.getTotalTxBytes()
        val currentTime = System.currentTimeMillis()

        val timeDiff = (currentTime - lastUpdateTime) / 1000.0
        if (timeDiff > 0) {
            val rxSpeed = (currentRxBytes - lastTotalRxBytes) / timeDiff
            val txSpeed = (currentTxBytes - lastTotalTxBytes) / timeDiff

            lastTotalRxBytes = currentRxBytes
            lastTotalTxBytes = currentTxBytes
            lastUpdateTime = currentTime

            return Pair(formatSpeed(rxSpeed.toLong()), formatSpeed(txSpeed.toLong()))
        }
        return Pair("0 B/s", "0 B/s")
    }

    private fun formatSpeed(bytesPerSecond: Long): String {
        return when {
            bytesPerSecond < 1024 -> "$bytesPerSecond B/s"
            bytesPerSecond < 1024 * 1024 -> String.format("%.1f KB/s", bytesPerSecond / 1024.0)
            else -> String.format("%.1f MB/s", bytesPerSecond / (1024.0 * 1024.0))
        }
    }

    private fun updateNotification() {
        val (rxSpeed, txSpeed) = getTrafficStats()
        val notification = createNotification(rxSpeed, txSpeed)
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    private val retryRunnable = object : Runnable {
        override fun run() {
            if (!checkPermission() || lastLocation == null) {
                startLocationUpdates()
                handler.postDelayed(this, 5000)
            }
        }
    }

    private fun startLocationUpdates() {
        if (checkPermission()) {
            try {
                locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    1000L,
                    0f,
                    locationListener
                )
                lastLocation = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            } catch (e: SecurityException) {
                e.printStackTrace()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()

        // 初始化流量统计基准值
        lastTotalRxBytes = TrafficStats.getTotalRxBytes()
        lastTotalTxBytes = TrafficStats.getTotalTxBytes()
        lastUpdateTime = System.currentTimeMillis()

        startForeground(NOTIFICATION_ID, createNotification("0 B/s", "0 B/s"))

        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        startLocationUpdates()
        handler.post(retryRunnable)

        // 启动流量统计更新
        startTrafficUpdates()

        webServer = WebServer(this)
        try {
            webServer.start()
        } catch (e: Exception) {
            Log.e(TAG, "WebServer start failed", e)
        }
    }

    private fun startTrafficUpdates() {
        handler.postDelayed(object : Runnable {
            override fun run() {
                updateNotification()
                handler.postDelayed(this, updateInterval)
            }
        }, updateInterval)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Service Channel",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                setShowBadge(false)
                enableLights(false)
                enableVibration(false)
                setSound(null, null)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(rxSpeed: String = "0 B/s", txSpeed: String = "0 B/s"): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("系统服务运行中")
            .setContentText("↓$rxSpeed   |   ↑$txSpeed")
            .setSmallIcon(R.drawable.ic_notification_transparent)//自定义通知图标

            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(pendingIntent)
            .build()
    }

    private fun checkPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        if (checkPermission()) {
            locationManager.removeUpdates(locationListener)
        }
        webServer.stop()
    }
}