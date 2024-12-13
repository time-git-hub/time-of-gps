package com.time.gps

import fi.iki.elonen.NanoHTTPD
import org.json.JSONObject
import android.content.Context
import android.util.Log

class WebServer(private val gpsService: GPSService) : NanoHTTPD(
    gpsService.applicationContext.getSharedPreferences("gps_prefs", Context.MODE_PRIVATE)
        .getInt("port", DEFAULT_PORT)
) {
    companion object {
        const val API_PATH = "/time"
        private const val DEFAULT_TOKEN = "123456"
        private const val TAG = "WebServer"
        const val DEFAULT_PORT = 8888
    }

    private fun getValidToken(): String {
        return gpsService.applicationContext.getSharedPreferences("gps_prefs", Context.MODE_PRIVATE)
            .getString("token", DEFAULT_TOKEN) ?: DEFAULT_TOKEN
    }

    override fun serve(session: IHTTPSession): Response {
        val token = session.parameters["token"]?.firstOrNull()
        val validToken = getValidToken()

        if (token != validToken) {
            Log.w(TAG, "Unauthorized access attempt with token: $token")
            return newFixedLengthResponse(
                Response.Status.UNAUTHORIZED,
                MIME_PLAINTEXT,
                "Unauthorized: Invalid token"
            )
        }

        return when (session.uri) {
            API_PATH -> {
                val location = gpsService.lastLocation
                val jsonResponse = JSONObject().apply {
                    put("latitude", location?.latitude ?: 0.0)
                    put("longitude", location?.longitude ?: 0.0)
                    put("timestamp", System.currentTimeMillis())
                    put("accuracy", location?.accuracy ?: 0.0f)
                    put("speed", if (location?.hasSpeed() == true) location.speed else 0.0f)
                    put("status", if(location != null) "active" else "waiting")
                    put("location_time", location?.time ?: 0)
                }

                Log.d(TAG, "Sending location data: ${jsonResponse.toString()}")
                newFixedLengthResponse(
                    Response.Status.OK,
                    "application/json",
                    jsonResponse.toString()
                ).apply {
                    // 添加CORS头，允许跨域访问
                    addHeader("Access-Control-Allow-Origin", "*")
                    addHeader("Access-Control-Allow-Methods", "GET")
                }
            }
            else -> {
                Log.w(TAG, "Not found: ${session.uri}")
                newFixedLengthResponse(
                    Response.Status.NOT_FOUND,
                    MIME_PLAINTEXT,
                    "Not Found"
                )
            }
        }
    }

    override fun start() {
        super.start()
        Log.i(TAG, "WebServer started on port ${listeningPort}")
    }

    override fun stop() {
        super.stop()
        Log.i(TAG, "WebServer stopped")
    }
}