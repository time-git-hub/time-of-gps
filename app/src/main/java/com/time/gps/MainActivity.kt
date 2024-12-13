package com.time.gps

import android.Manifest
import android.app.ActivityManager
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.text.format.Formatter
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import android.content.ClipboardManager
import android.content.ClipData
import android.graphics.Color
import android.widget.Toast
import java.net.Inet4Address
import java.net.NetworkInterface
import android.location.Location
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.sqrt

class MainActivity : AppCompatActivity(), SensorEventListener {
    companion object {
        private const val SERVER_ADDRESS_FORMAT = "http://%s:%d%s?token=%s"
        private const val DEFAULT_TOKEN = "123456" // 默认 token
        private const val DEFAULT_PORT = 8888
    }

    private val alpha = 0.8f
    private var token = DEFAULT_TOKEN
    private var gpsService: GPSService? = null
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var sensorManager: SensorManager
    private var accelerometer: Sensor? = null
    private var lastAcceleration = 0f
    private var currentSpeed = 0f

    private val locationUpdateListener: (Location) -> Unit = { location ->
        updateLocationDisplay(location)
        showServerAddress()
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as? GPSService.LocalBinder
            gpsService = binder?.getService()
            gpsService?.addLocationListener(locationUpdateListener)
            startLocationUpdates()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            gpsService?.removeLocationListener(locationUpdateListener)
            gpsService = null
        }
    }

    private fun updateLocationDisplay(location: Location) {
        findViewById<TextView>(R.id.locationText).apply {
            text = buildString {
                append("latitude：${String.format("%.6f", location.latitude)}\n")
                append("longitude：${String.format("%.6f", location.longitude)}\n")
                append("accurate：${String.format("%.1f", location.accuracy)}米\n")
                if (location.hasSpeed()) {
                    append("Gspeed：${String.format("%.1f", location.speed)} m/s\n")
                } else {
                    append("Gspeed：waiting...\n")
                }
                append("Aspeed：${String.format("%.1f", currentSpeed)} m/s")
            }
            setTextColor(Color.BLACK)
        }
    }

    private val statusUpdateRunnable = object : Runnable {
        override fun run() {
            updateGPSStatus()
            handler.postDelayed(this, 1000) // 每秒更新一次
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // 初始化传感器
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)

        if (accelerometer == null) {
            Toast.makeText(this, "设备不支持加速度传感器", Toast.LENGTH_SHORT).show()
        }

        token = getSharedPreferences("gps_prefs", Context.MODE_PRIVATE)
            .getString("token", DEFAULT_TOKEN) ?: DEFAULT_TOKEN

        if (token == DEFAULT_TOKEN) {
            getSharedPreferences("gps_prefs", Context.MODE_PRIVATE)
                .edit()
                .putString("token", DEFAULT_TOKEN)
                .apply()
        }

        updateTokenDisplay()

        findViewById<EditText>(R.id.tokenInput).setText(token)

        findViewById<Button>(R.id.saveTokenButton).setOnClickListener {
            saveToken()
        }

        checkAndRequestPermissions()
        bindGPSService()
        setupServiceMonitor()
        showServerAddress()

        // 设置当前端口显示
        findViewById<EditText>(R.id.portInput).setText(
            getSharedPreferences("gps_prefs", Context.MODE_PRIVATE)
                .getInt("port", DEFAULT_PORT)
                .toString()
        )

        // 保存端口按钮点击事件
        findViewById<Button>(R.id.savePortButton).setOnClickListener {
            savePort()
        }
    }

    private fun bindGPSService() {
        Intent(this, GPSService::class.java).also { intent ->
            bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        }
    }

    private fun saveToken() {
        val newToken = findViewById<EditText>(R.id.tokenInput).text.toString()
        if (newToken.isNotEmpty()) {
            token = newToken
            getSharedPreferences("gps_prefs", Context.MODE_PRIVATE)
                .edit()
                .putString("token", token)
                .apply()

            updateTokenDisplay()
            showServerAddress()

            findViewById<EditText>(R.id.tokenInput).text.clear()
        }
    }

    private fun updateTokenDisplay() {
        findViewById<TextView>(R.id.currentTokenText).text =
            "当前令牌：$token\n(默认令牌：$DEFAULT_TOKEN)"
    }

    private fun showServerAddress() {
        try {
            val ipAddress = getLocalIpAddress()
            val currentPort = getSharedPreferences("gps_prefs", Context.MODE_PRIVATE)
                .getInt("port", WebServer.DEFAULT_PORT)
            val serverAddress = String.format(
                SERVER_ADDRESS_FORMAT,
                ipAddress,
                currentPort,
                WebServer.API_PATH,
                token
            )

            findViewById<TextView>(R.id.serverAddressText).apply {
                text = "$serverAddress"
                setOnClickListener {
                    copyToClipboard(serverAddress)
                }
            }

        } catch (e: Exception) {
            findViewById<TextView>(R.id.serverAddressText).text =
                "服务器地址: 获取失败，请检查网络连接"
            e.printStackTrace()
        }
    }

    private fun getLocalIpAddress(): String {
        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        var ipAddress = wifiManager.connectionInfo.ipAddress

        if (ipAddress == 0) {
            try {
                NetworkInterface.getNetworkInterfaces()?.toList()?.forEach { networkInterface ->
                    networkInterface.inetAddresses?.toList()?.forEach { address ->
                        if (!address.isLoopbackAddress && address is Inet4Address) {
                            return address.hostAddress ?: "127.0.0.1"
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
            return "127.0.0.1"
        }

        return Formatter.formatIpAddress(ipAddress)
    }

    private fun copyToClipboard(text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("", text)
        clipboard.setPrimaryClip(clip)
        showToast("已复制到剪贴板")
    }

    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun checkAndRequestPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val permissions = arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.POST_NOTIFICATIONS,
                Manifest.permission.FOREGROUND_SERVICE,
                Manifest.permission.FOREGROUND_SERVICE_LOCATION,
                Manifest.permission.ACCESS_WIFI_STATE
            )

            val permissionsToRequest = permissions.filter {
                checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
            }

            if (permissionsToRequest.isNotEmpty()) {
                requestPermissions(permissionsToRequest.toTypedArray(), 1)
            }
        }
    }

    private fun startGPSService() {
        Intent(this, GPSService::class.java).also { intent ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        }
    }

    private fun setupServiceMonitor() {
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(this, ServiceMonitor::class.java)
        val pendingIntent = PendingIntent.getBroadcast(
            this,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE
        )

        alarmManager.setRepeating(
            AlarmManager.RTC_WAKEUP,
            System.currentTimeMillis(),
            15 * 60 * 1000,
            pendingIntent
        )
    }

    private fun updateGPSStatus() {
        val isServiceRunning = isServiceRunning(GPSService::class.java)
        findViewById<TextView>(R.id.statusText).apply {
            text = "服务状态：${if (isServiceRunning) "运行中" else "未运行"}"
            setTextColor(if (isServiceRunning) Color.GREEN else Color.RED)
        }

        gpsService?.lastLocation?.let { location ->
            updateLocationDisplay(location)
        } ?: run {
            findViewById<TextView>(R.id.locationText).apply {
                text = "位置信息：等待获取..."
                setTextColor(Color.GRAY)
            }
        }
    }

    private fun startLocationUpdates() {
        handler.post(statusUpdateRunnable)
    }

    private fun isServiceRunning(serviceClass: Class<*>): Boolean {
        val manager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        return manager.getRunningServices(Integer.MAX_VALUE).any {
            serviceClass.name == it.service.className
        }
    }

    override fun onResume() {
        super.onResume()
        accelerometer?.let {
            sensorManager.registerListener(
                this,
                it,
                SensorManager.SENSOR_DELAY_NORMAL
            )
        }
    }

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type == Sensor.TYPE_LINEAR_ACCELERATION) {
            val x = event.values[0]
            val y = event.values[1]
            val z = event.values[2]
            val acceleration = sqrt(x * x + y * y + z * z)

            lastAcceleration = lastAcceleration * alpha + acceleration * (1 - alpha)

            if (lastAcceleration > 0.5f) {
                currentSpeed += lastAcceleration * 0.1f
            } else {
                currentSpeed *= 0.95f
            }

            if (currentSpeed < 0) currentSpeed = 0f

            gpsService?.lastLocation?.let { location ->
                updateLocationDisplay(location)
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {

    }

    override fun onDestroy() {
        super.onDestroy()
        sensorManager.unregisterListener(this)
        gpsService?.removeLocationListener(locationUpdateListener)
        handler.removeCallbacks(statusUpdateRunnable)
        unbindService(serviceConnection)
    }

    private fun savePort() {
        val portStr = findViewById<EditText>(R.id.portInput).text.toString()
        if (portStr.isNotEmpty()) {
            try {
                val port = portStr.toInt()
                if (port in 1024..65535) {  // 有效端口范围
                    getSharedPreferences("gps_prefs", Context.MODE_PRIVATE)
                        .edit()
                        .putInt("port", port)
                        .apply()
                    
                    // 重启服务以应用新端口
                    restartGPSService()
                    showToast("端口已保存，服务重启中...")
                } else {
                    showToast("请输入有效的端口号(1024-65535)")
                }
            } catch (e: NumberFormatException) {
                showToast("请输入有效的端口号")
            }
        }
    }

    private fun restartGPSService() {
        // 停止服务
        stopService(Intent(this, GPSService::class.java))
        // 重新启动服务
        bindGPSService()
    }
}