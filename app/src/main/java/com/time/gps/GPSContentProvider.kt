package com.time.gps
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri

class GPSContentProvider : ContentProvider() {
    companion object {
        private const val AUTHORITY = "com.time.gps.gpsprovider"
        val CONTENT_URI: Uri = Uri.parse("content://$AUTHORITY/location")
    }

    private var gpsService: GPSService? = null

    override fun onCreate(): Boolean {
        // 通过 bindService 获取 GPSService 实例
        val serviceIntent = Intent(context, GPSService::class.java)
        context?.bindService(
            serviceIntent,
            object : android.content.ServiceConnection {
                override fun onServiceConnected(name: android.content.ComponentName?, service: android.os.IBinder?) {
                    gpsService = (service as? GPSService.LocalBinder)?.getService()
                }

                override fun onServiceDisconnected(name: android.content.ComponentName?) {
                    gpsService = null
                }
            },
            Context.BIND_AUTO_CREATE
        )
        return true
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor {
        return MatrixCursor(arrayOf("latitude", "longitude", "accuracy", "time")).apply {
            gpsService?.lastLocation?.let { location ->
                addRow(arrayOf(
                    location.latitude,
                    location.longitude,
                    location.accuracy,
                    location.time
                ))
            }
        }
    }

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}