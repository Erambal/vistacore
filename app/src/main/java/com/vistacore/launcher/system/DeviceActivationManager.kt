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
 * Fails open on any server or network problem — only an explicit `active: false`
 * response locks the device. See [DeviceActivationManager.isDeviceActive].
 */
class DeviceActivationManager(private val context: Context) {

    private val prefs = PrefsManager(context)

    companion object {
        // No grace period any more: unreachable always fails open, so there is nothing
        // to expire. See isDeviceActive().
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
     * Only an explicit `active: false` from the server ever locks the device.
     *
     * Every other outcome fails OPEN — unreachable host, DNS failure, expired cert,
     * timeout, non-200. This is deliberate: the lock screen is an inescapable dead end
     * (Back is a no-op and Retry cannot succeed without the server), and the people
     * using this launcher cannot diagnose or work around it. A lapsed domain, an ISP
     * outage, or a moved router must never be able to brick a TV in someone's house.
     * `checkServer` already failed open on a non-200 (see below); this makes the
     * transport-failure path agree with it.
     *
     * Consequence worth knowing: a deactivated device that never reaches the server
     * again keeps working. That is the intended trade — losing a revocation is
     * recoverable, bricking a senior's television is not.
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
            // Server unreachable. Never lock on this — we cannot tell "revoked" from
            // "the internet is down", and only one of those should stop playback.
            true
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
