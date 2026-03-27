package com.vistacore.launcher.iptv

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

/**
 * Catch-Up / Timeshift support for IPTV streams.
 *
 * Many IPTV providers support timeshift via URL manipulation:
 * - Xtream Codes: /timeshift/{username}/{password}/{duration}/{start}/{stream_id}.ts
 * - Flussonic: append ?utc={timestamp} to the stream URL
 * - Stalker: Uses a separate timeshift API
 *
 * This manager builds the correct catch-up URL based on the provider type.
 */
class CatchUpManager {

    companion object {
        const val CATCHUP_XTREAM = "xtream"
        const val CATCHUP_FLUSSONIC = "flussonic"
        const val CATCHUP_APPEND = "append"
        const val CATCHUP_SHIFT = "shift"
    }

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd:HH-mm", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    /**
     * Build a catch-up URL for a past program.
     *
     * @param originalUrl The live stream URL
     * @param program The EPG program to catch up on
     * @param auth Xtream auth credentials (if applicable)
     * @param catchUpType The catch-up URL pattern to use
     */
    fun buildCatchUpUrl(
        originalUrl: String,
        program: EpgProgram,
        auth: XtreamAuth? = null,
        catchUpType: String = CATCHUP_XTREAM
    ): String? {
        if (program.isLive) return originalUrl // Still live — use normal URL

        val now = Date()
        if (program.endTime > now) return originalUrl // Still airing

        return when (catchUpType) {
            CATCHUP_XTREAM -> buildXtreamCatchUp(originalUrl, program, auth)
            CATCHUP_FLUSSONIC -> buildFlussonicCatchUp(originalUrl, program)
            CATCHUP_APPEND -> buildAppendCatchUp(originalUrl, program)
            CATCHUP_SHIFT -> buildShiftCatchUp(originalUrl, program)
            else -> null
        }
    }

    /**
     * Xtream Codes timeshift URL:
     * {server}/timeshift/{username}/{password}/{duration}/{start}/{stream_id}.ts
     */
    private fun buildXtreamCatchUp(
        originalUrl: String,
        program: EpgProgram,
        auth: XtreamAuth?
    ): String? {
        if (auth == null) return null

        // Extract stream ID from URL (e.g., /live/user/pass/12345.ts -> 12345)
        val streamIdMatch = Regex("""/(\d+)\.\w+$""").find(originalUrl)
        val streamId = streamIdMatch?.groupValues?.get(1) ?: return null

        val server = auth.server.trimEnd('/')
        val duration = program.durationMinutes
        val start = dateFormat.format(program.startTime)

        return "$server/timeshift/${auth.username}/${auth.password}/$duration/$start/$streamId.ts"
    }

    /**
     * Flussonic-style: append ?utc={unix_timestamp}&lutc={end_timestamp}
     */
    private fun buildFlussonicCatchUp(originalUrl: String, program: EpgProgram): String {
        val startUtc = program.startTime.time / 1000
        val endUtc = program.endTime.time / 1000
        val separator = if (originalUrl.contains("?")) "&" else "?"
        return "${originalUrl}${separator}utc=$startUtc&lutc=$endUtc"
    }

    /**
     * Append-style: replace the stream extension with a timeshift path
     * Common pattern: {url}/timeshift-{start_epoch}-{duration}.ts
     */
    private fun buildAppendCatchUp(originalUrl: String, program: EpgProgram): String {
        val startEpoch = program.startTime.time / 1000
        val durationSec = (program.endTime.time - program.startTime.time) / 1000
        val base = originalUrl.substringBeforeLast(".")
        return "$base/timeshift-$startEpoch-$durationSec.ts"
    }

    /**
     * Shift-style: add start/end params
     */
    private fun buildShiftCatchUp(originalUrl: String, program: EpgProgram): String {
        val start = dateFormat.format(program.startTime)
        val end = dateFormat.format(program.endTime)
        val separator = if (originalUrl.contains("?")) "&" else "?"
        return "${originalUrl}${separator}start=$start&end=$end"
    }

    /**
     * Check if a stream supports catch-up by testing the timeshift URL.
     */
    suspend fun testCatchUp(url: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val client = OkHttpClient.Builder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(5, TimeUnit.SECONDS)
                .build()

            val request = Request.Builder().url(url).head().build()
            val response = client.newCall(request).execute()
            response.isSuccessful
        } catch (_: Exception) {
            false
        }
    }
}
