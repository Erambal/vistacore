package com.vistacore.launcher.system

import android.content.Context
import android.provider.Settings
import com.vistacore.launcher.data.PrefsManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Checks device activation status against the admin server.
 * If the server is unreachable, uses a cached result with a 7-day grace period.
 */
class DeviceActivationManager(private val context: Context) {

    private val prefs = PrefsManager(context)

    companion object {
        private const val GRACE_PERIOD_MS = 7L * 24 * 60 * 60 * 1000 // 7 days
        private const val CONNECT_TIMEOUT = 8_000
        private const val READ_TIMEOUT = 8_000
    }

    /** Unique device ID — stable across app reinstalls. */
    fun getDeviceId(): String {
        return Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown"
    }

    /**
     * Check activation status. Returns true if the device is allowed to run.
     *
     * Logic:
     * 1. Hit the server — if it responds, cache the result and return it.
     * 2. If the server is unreachable, use the cached result if it's within
     *    the grace period. After the grace period expires, return false.
     * 3. On very first launch (no cached result, no server configured),
     *    default to active so the app isn't locked out of the box.
     */
    suspend fun isDeviceActive(): Boolean {
        val serverUrl = prefs.activationServer.trimEnd('/')
        if (serverUrl.isBlank()) return true // no server configured

        val deviceId = getDeviceId()

        return try {
            val active = withContext(Dispatchers.IO) { checkServer(serverUrl, deviceId) }
            prefs.deviceActiveCached = active
            prefs.activationLastCheck = System.currentTimeMillis()
            active
        } catch (_: Exception) {
            // Server unreachable — use cached result within grace period
            val lastCheck = prefs.activationLastCheck
            if (lastCheck == 0L) {
                // Never checked before — allow (first launch, server might not exist yet)
                true
            } else if (System.currentTimeMillis() - lastCheck < GRACE_PERIOD_MS) {
                prefs.deviceActiveCached
            } else {
                // Grace period expired, server still unreachable — lock
                false
            }
        }
    }

    /**
     * Registers this device with the server (sends device info so admin can see it).
     * Called on first setup or whenever the app phones home.
     */
    suspend fun registerDevice() {
        val serverUrl = prefs.activationServer.trimEnd('/')
        if (serverUrl.isBlank()) return

        try {
            withContext(Dispatchers.IO) {
                val url = URL("$serverUrl/api/device/register")
                val conn = url.openConnection() as HttpURLConnection
                try {
                    conn.requestMethod = "POST"
                    conn.setRequestProperty("Content-Type", "application/json")
                    conn.connectTimeout = CONNECT_TIMEOUT
                    conn.readTimeout = READ_TIMEOUT
                    conn.doOutput = true

                    val body = JSONObject().apply {
                        put("device_id", getDeviceId())
                        put("device_name", android.os.Build.MODEL)
                        put("app_version", getAppVersion())
                    }
                    conn.outputStream.bufferedWriter().use { it.write(body.toString()) }

                    conn.responseCode // trigger the request
                } finally {
                    // Always disconnect — even when responseCode throws.
                    // Without this an unreachable activation server leaks
                    // an FD per registerDevice() call.
                    try { conn.disconnect() } catch (_: Exception) {}
                }
            }
        } catch (_: Exception) {
            // Silent fail — registration is best-effort
        }
    }

    private fun checkServer(serverUrl: String, deviceId: String): Boolean {
        val url = URL("$serverUrl/api/device/status?device_id=$deviceId")
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.connectTimeout = CONNECT_TIMEOUT
        conn.readTimeout = READ_TIMEOUT

        try {
            if (conn.responseCode != 200) return true // server error → fail open

            val response = conn.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(response)
            return json.optBoolean("active", true)
        } finally {
            try { conn.disconnect() } catch (_: Exception) {}
        }
    }

    private fun getAppVersion(): String {
        return try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "unknown"
        } catch (_: Exception) {
            "unknown"
        }
    }
}
