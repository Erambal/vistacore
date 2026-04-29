package com.vistacore.launcher.data

import android.util.Log
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * Client for the VistaFilter companion server.
 * Handles requesting filter generation, polling status, and downloading filter files.
 */
class FilterServerClient(
    private val serverUrl: String,
    private val apiKey: String = ""
) {
    companion object {
        private const val TAG = "FilterServer"
        private const val TIMEOUT_MS = 15000
    }

    private val gson = Gson()
    private val baseUrl get() = serverUrl.trimEnd('/')

    /**
     * Run a connection through a try/finally that always disconnects, even
     * on exception or non-2xx responses. The previous methods only called
     * `disconnect()` inside the success branch — when `responseCode` threw
     * (or any other exception leaked out of the try) the connection was
     * left open and its FD held until GC, which on IPTVPlayerActivity's
     * 240-iteration poll loop chewed through the per-process FD limit and
     * surfaced as "Too many open streams" on a subsequent movie open.
     */
    private inline fun <T> withConnection(path: String, block: (HttpURLConnection) -> T): T {
        val conn = URL("$baseUrl$path").openConnection() as HttpURLConnection
        conn.connectTimeout = TIMEOUT_MS
        conn.readTimeout = TIMEOUT_MS
        addAuth(conn)
        try {
            return block(conn)
        } finally {
            try { conn.disconnect() } catch (_: Exception) {}
        }
    }

    /** Check if the server is configured and reachable. */
    suspend fun isAvailable(): Boolean = withContext(Dispatchers.IO) {
        if (serverUrl.isBlank()) return@withContext false
        try {
            withConnection("/api/health") { conn ->
                conn.connectTimeout = 5000
                conn.readTimeout = 5000
                conn.responseCode == 200
            }
        } catch (e: Exception) {
            Log.w(TAG, "Server not reachable: ${e.message}")
            false
        }
    }

    /** Check if a filter already exists on the server. Returns the filter or null. */
    suspend fun getFilter(title: String, year: String = ""): ContentFilterFile? = withContext(Dispatchers.IO) {
        try {
            val encoded = java.net.URLEncoder.encode(title, "UTF-8")
            val yearParam = if (year.isNotBlank()) "?year=$year" else ""
            withConnection("/api/filter/$encoded$yearParam") { conn ->
                if (conn.responseCode == 200) {
                    val json = conn.inputStream.bufferedReader().use { it.readText() }
                    gson.fromJson(json, ContentFilterFile::class.java)
                } else null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get filter: ${e.message}")
            null
        }
    }

    /** Request filter generation for a title. Returns the job ID. */
    suspend fun requestFilter(title: String, year: String, streamUrl: String): FilterJobResponse? = withContext(Dispatchers.IO) {
        try {
            withConnection("/api/filter/request") { conn ->
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")

                val body = gson.toJson(mapOf(
                    "title" to title,
                    "year" to year,
                    "stream_url" to streamUrl
                ))
                conn.outputStream.bufferedWriter().use { it.write(body) }

                if (conn.responseCode in 200..299) {
                    val json = conn.inputStream.bufferedReader().use { it.readText() }
                    gson.fromJson(json, FilterJobResponse::class.java)
                } else null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to request filter: ${e.message}")
            null
        }
    }

    /** Check the status of a processing job. */
    suspend fun getJobStatus(jobId: String): FilterJobResponse? = withContext(Dispatchers.IO) {
        try {
            withConnection("/api/filter/status/$jobId") { conn ->
                if (conn.responseCode == 200) {
                    val json = conn.inputStream.bufferedReader().use { it.readText() }
                    gson.fromJson(json, FilterJobResponse::class.java)
                } else null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to check job status: ${e.message}")
            null
        }
    }

    private fun addAuth(conn: HttpURLConnection) {
        if (apiKey.isNotBlank()) {
            conn.setRequestProperty("Authorization", "Bearer $apiKey")
        }
    }
}

data class FilterJobResponse(
    val id: String,
    val title: String,
    val year: String = "",
    val status: String,
    val progress: String = "",
    val created_at: Double = 0.0,
    val completed_at: Double? = null,
    val error: String? = null
)
