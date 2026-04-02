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

    /** Check if the server is configured and reachable. */
    suspend fun isAvailable(): Boolean = withContext(Dispatchers.IO) {
        if (serverUrl.isBlank()) return@withContext false
        try {
            val conn = url("/api/health").openConnection() as HttpURLConnection
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            addAuth(conn)
            val code = conn.responseCode
            conn.disconnect()
            code == 200
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
            val conn = url("/api/filter/$encoded$yearParam").openConnection() as HttpURLConnection
            conn.connectTimeout = TIMEOUT_MS
            conn.readTimeout = TIMEOUT_MS
            addAuth(conn)

            if (conn.responseCode == 200) {
                val json = conn.inputStream.bufferedReader().readText()
                conn.disconnect()
                gson.fromJson(json, ContentFilterFile::class.java)
            } else {
                conn.disconnect()
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get filter: ${e.message}")
            null
        }
    }

    /** Request filter generation for a title. Returns the job ID. */
    suspend fun requestFilter(title: String, year: String, streamUrl: String): FilterJobResponse? = withContext(Dispatchers.IO) {
        try {
            val conn = url("/api/filter/request").openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = TIMEOUT_MS
            conn.readTimeout = TIMEOUT_MS
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            addAuth(conn)

            val body = gson.toJson(mapOf(
                "title" to title,
                "year" to year,
                "stream_url" to streamUrl
            ))
            conn.outputStream.bufferedWriter().use { it.write(body) }

            if (conn.responseCode in 200..299) {
                val json = conn.inputStream.bufferedReader().readText()
                conn.disconnect()
                gson.fromJson(json, FilterJobResponse::class.java)
            } else {
                conn.disconnect()
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to request filter: ${e.message}")
            null
        }
    }

    /** Check the status of a processing job. */
    suspend fun getJobStatus(jobId: String): FilterJobResponse? = withContext(Dispatchers.IO) {
        try {
            val conn = url("/api/filter/status/$jobId").openConnection() as HttpURLConnection
            conn.connectTimeout = TIMEOUT_MS
            conn.readTimeout = TIMEOUT_MS
            addAuth(conn)

            if (conn.responseCode == 200) {
                val json = conn.inputStream.bufferedReader().readText()
                conn.disconnect()
                gson.fromJson(json, FilterJobResponse::class.java)
            } else {
                conn.disconnect()
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to check job status: ${e.message}")
            null
        }
    }

    private fun url(path: String) = URL("$baseUrl$path")

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
