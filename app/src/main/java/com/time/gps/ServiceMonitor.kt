package com.time.gps
import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
class ServiceMonitor : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!isServiceRunning(context, GPSService::class.java)) {
            Intent(context, GPSService::class.java).also { serviceIntent ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent)
                } else {
                    context.startService(serviceIntent)
                }
            }
        }
    }

    private fun isServiceRunning(context: Context, serviceClass: Class<*>): Boolean {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        return manager.getRunningServices(Integer.MAX_VALUE).any {
            serviceClass.name == it.service.className
        }
    }
}