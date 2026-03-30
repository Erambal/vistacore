package com.vistacore.launcher.iptv

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.BufferedInputStream
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream

class EpgParser {

    companion object {
        private const val TAG = "EpgParser"
    }

    private val client = TlsCompat.apply(OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS))
        .build()

    private val dateFormat = SimpleDateFormat("yyyyMMddHHmmss Z", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    private val dateFormatNoTz = SimpleDateFormat("yyyyMMddHHmmss", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    suspend fun parse(url: String): EpgData = withContext(Dispatchers.IO) {
        Log.d(TAG, "Loading EPG from: $url")
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "VistaCore/1.0")
            .build()
        val response = client.newCall(request).execute()

        if (!response.isSuccessful) {
            throw Exception("Failed to load EPG: ${response.code}")
        }

        val body = response.body ?: throw Exception("Empty EPG data")
        // Stream-parse directly from the network — never load full XML into memory
        // Only manually decompress for actual .gz file URLs.
        // OkHttp transparently handles Content-Encoding: gzip for normal HTTP responses,
        // so we must NOT double-decompress those.
        val rawStream = body.byteStream()
        val buffered = BufferedInputStream(rawStream, 8192)

        // Sniff for gzip magic bytes (1F 8B) to auto-detect compressed data
        // that OkHttp didn't decompress (e.g. .gz file downloads or servers
        // that set Content-Type: application/gzip instead of Content-Encoding)
        buffered.mark(2)
        val b1 = buffered.read()
        val b2 = buffered.read()
        buffered.reset()
        val isGzipData = (b1 == 0x1F && b2 == 0x8B)

        val stream: InputStream = if (isGzipData) {
            Log.d(TAG, "EPG data is gzip compressed, decompressing")
            GZIPInputStream(buffered)
        } else {
            Log.d(TAG, "EPG data is plain XML")
            buffered
        }

        val result = stream.use { parseXmlTvStream(it) }
        Log.d(TAG, "EPG loaded: ${result.channels.size} channels, ${result.programs.size} programs")
        result
    }

    private fun parseXmlTvStream(inputStream: InputStream): EpgData {
        val channels = mutableMapOf<String, EpgChannel>()
        val programs = mutableListOf<EpgProgram>()

        // Only keep programs from the last 2 hours onward (skip old history)
        val cutoff = Date(System.currentTimeMillis() - 2 * 3600000L)

        val factory = XmlPullParserFactory.newInstance()
        val parser = factory.newPullParser()
        parser.setInput(inputStream, null)

        var eventType = parser.eventType
        var currentChannelId = ""
        var currentProgramChannelId = ""
        var programStart: Date? = null
        var programEnd: Date? = null
        var programTitle = ""
        var programDescription = ""
        var programCategory = ""
        var currentTag = ""

        while (eventType != XmlPullParser.END_DOCUMENT) {
            when (eventType) {
                XmlPullParser.START_TAG -> {
                    currentTag = parser.name
                    when (parser.name) {
                        "channel" -> {
                            currentChannelId = parser.getAttributeValue(null, "id") ?: ""
                        }
                        "programme" -> {
                            currentProgramChannelId = parser.getAttributeValue(null, "channel") ?: ""
                            val startStr = parser.getAttributeValue(null, "start") ?: ""
                            val endStr = parser.getAttributeValue(null, "stop") ?: ""
                            programStart = parseDate(startStr)
                            programEnd = parseDate(endStr)
                            programTitle = ""
                            programDescription = ""
                            programCategory = ""
                        }
                    }
                }
                XmlPullParser.TEXT -> {
                    val text = parser.text?.trim() ?: ""
                    if (text.isNotEmpty()) {
                        when (currentTag) {
                            "display-name" -> {
                                if (currentChannelId.isNotEmpty()) {
                                    channels[currentChannelId] = EpgChannel(
                                        id = currentChannelId,
                                        displayName = text
                                    )
                                }
                            }
                            "title" -> programTitle = text
                            "desc" -> programDescription = text
                            "category" -> programCategory = text
                        }
                    }
                }
                XmlPullParser.END_TAG -> {
                    when (parser.name) {
                        "channel" -> currentChannelId = ""
                        "programme" -> {
                            if (currentProgramChannelId.isNotEmpty() && programStart != null) {
                                val endTime = programEnd ?: Date(programStart!!.time + 3600000)
                                // Only keep recent/current/future programs
                                if (endTime > cutoff) {
                                    programs.add(
                                        EpgProgram(
                                            channelId = currentProgramChannelId,
                                            title = programTitle,
                                            description = programDescription,
                                            category = programCategory,
                                            startTime = programStart!!,
                                            endTime = endTime
                                        )
                                    )
                                }
                            }
                            currentProgramChannelId = ""
                        }
                    }
                    currentTag = ""
                }
            }
            eventType = parser.next()
        }

        return EpgData(channels, programs)
    }

    private fun parseDate(dateStr: String): Date? {
        if (dateStr.isBlank()) return null
        return try {
            dateFormat.parse(dateStr)
        } catch (_: Exception) {
            try {
                dateFormatNoTz.parse(dateStr)
            } catch (_: Exception) {
                null
            }
        }
    }
}

// --- EPG Data Models ---

data class EpgData(
    val channels: Map<String, EpgChannel>,
    val programs: List<EpgProgram>
) {
    fun getNowPlaying(channelId: String): EpgProgram? {
        val now = Date()
        return programs.firstOrNull { program ->
            program.channelId == channelId &&
                    program.startTime <= now &&
                    program.endTime > now
        }
    }

    fun getUpcoming(channelId: String, hours: Int = 6): List<EpgProgram> {
        val now = Date()
        val cutoff = Date(now.time + hours * 3600000L)
        return programs.filter { program ->
            program.channelId == channelId &&
                    program.startTime >= now &&
                    program.startTime <= cutoff
        }.sortedBy { it.startTime }
    }

    fun getTodaySchedule(channelId: String): List<EpgProgram> {
        val now = Date()
        val startOfDay = Date(now.time - (now.time % 86400000))
        val endOfDay = Date(startOfDay.time + 86400000)
        return programs.filter { program ->
            program.channelId == channelId &&
                    program.endTime > startOfDay &&
                    program.startTime < endOfDay
        }.sortedBy { it.startTime }
    }
}

data class EpgChannel(
    val id: String,
    val displayName: String,
    val iconUrl: String = ""
)

data class EpgProgram(
    val channelId: String,
    val title: String,
    val description: String = "",
    val category: String = "",
    val startTime: Date,
    val endTime: Date
) {
    val durationMinutes: Int
        get() = ((endTime.time - startTime.time) / 60000).toInt()

    val isLive: Boolean
        get() {
            val now = Date()
            return startTime <= now && endTime > now
        }

    val progress: Float
        get() {
            if (!isLive) return 0f
            val now = Date().time
            val total = endTime.time - startTime.time
            val elapsed = now - startTime.time
            return (elapsed.toFloat() / total).coerceIn(0f, 1f)
        }
}
