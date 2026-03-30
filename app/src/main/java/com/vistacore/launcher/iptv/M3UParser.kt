package com.vistacore.launcher.iptv

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

class M3UParser {

    // Precompiled regexes for EXTINF parsing (avoid re-creating per line)
    private val reTvgId = Regex("""tvg-id="([^"]*?)"""")
    private val reTvgName = Regex("""tvg-name="([^"]*?)"""")
    private val reTvgLogo = Regex("""tvg-logo="([^"]*?)"""")
    private val reGroupTitle = Regex("""group-title="([^"]*?)"""")

    // Precompiled regexes for content classification
    private val reEpisodeS = Regex("""[Ss]\d{1,2}\s*[Ee]\d{1,2}""")
    private val reSeason = Regex("""[Ss]eason\s*\d""", RegexOption.IGNORE_CASE)
    private val reEp = Regex("""\bep\.?\s*\d""", RegexOption.IGNORE_CASE)
    private val reEpisodeWord = Regex("""\bepisode\s*\d""", RegexOption.IGNORE_CASE)
    private val reYear = Regex("""\(?(19|20)\d{2}\)?""")
    private val reGenreVod = Regex("""(?:vod|\bmovie|\bfilm)[^a-z]*[|:\-–]""")

    private val vodExtensions = arrayOf(".mp4", ".mkv", ".avi", ".mov", ".wmv", ".flv", ".webm")

    private val seriesGroupKeywords = arrayOf(
        "series", "tv show", "tv shows", "tvshow", "episode", "season",
        "24/7", "24-7", "sitcom", "drama series", "anime", "telenovela", "novela"
    )

    private val movieGroupKeywords = arrayOf(
        "movie", "movies", "film", "films", "vod", "cinema",
        "box office", "ppv", "pay per view", "on demand",
        "bollywood", "hollywood", "documentary", "documentaries",
        "thriller", "horror", "comedy", "action", "romance",
        "sci-fi", "scifi", "adventure", "fantasy", "animation",
        "family movie", "kids movie", "new release", "featured"
    )

    private val client = TlsCompat.apply(OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true))
        .build()

    suspend fun parse(url: String): List<Channel> = withContext(Dispatchers.IO) {
        if (url.isBlank()) throw Exception("Playlist URL is empty")

        val request = Request.Builder()
            .url(url.trim())
            .header("User-Agent", "VistaCore/1.0")
            .build()
        val response = client.newCall(request).execute()

        if (!response.isSuccessful) {
            throw Exception("Failed to load playlist: HTTP ${response.code}")
        }

        val body = response.body ?: throw Exception("Empty playlist response")
        // Stream-parse line by line — never load the full file into memory
        body.byteStream().use { stream ->
            val reader = BufferedReader(InputStreamReader(stream, Charsets.UTF_8), 8192)
            parseFromReader(reader)
        }
    }

    private fun parseFromReader(reader: BufferedReader): List<Channel> {
        val channels = mutableListOf<Channel>()
        var channelNum = 1
        var currentExtInf: String? = null

        reader.forEachLine { rawLine ->
            val line = rawLine.trim()

            when {
                line.startsWith("#EXTINF:") -> {
                    currentExtInf = line
                }
                line.startsWith("#") || line.isEmpty() -> {
                    // Skip other comments and blank lines
                }
                currentExtInf != null -> {
                    // This is the URL line after an #EXTINF
                    val info = parseExtInf(currentExtInf!!)

                    // Skip 4K content — most Android TV devices can't decode it properly
                    val nameLower = info.name.lowercase()
                    val groupLower = info.group.lowercase()
                    if (nameLower.contains("4k") || nameLower.contains("uhd") ||
                        groupLower.contains("4k") || groupLower.contains("uhd")) {
                        currentExtInf = null
                        return@forEachLine
                    }

                    val contentType = classifyContent(info.group, info.name, line)
                    channels.add(
                        Channel(
                            id = "m3u_$channelNum",
                            name = info.name,
                            streamUrl = line,
                            logoUrl = info.logoUrl,
                            category = info.group,
                            number = channelNum,
                            contentType = contentType,
                            epgId = info.tvgId
                        )
                    )
                    channelNum++
                    currentExtInf = null
                }
            }
        }

        return channels
    }

    private fun classifyContent(group: String, name: String, url: String): ContentType {
        val groupLower = group.lowercase()
        val nameLower = name.lowercase()
        val urlLower = url.lowercase()

        // --- URL path detection (most reliable, from Xtream-style URLs) ---
        if (urlLower.contains("/movie/")) return ContentType.MOVIE
        if (urlLower.contains("/series/")) return ContentType.SERIES

        // --- URL extension detection ---
        for (ext in vodExtensions) {
            if (urlLower.endsWith(ext) || urlLower.contains("$ext?") || urlLower.contains("$ext&")) {
                return ContentType.MOVIE
            }
        }

        // --- Group-title keywords ---
        for (keyword in seriesGroupKeywords) {
            if (groupLower.contains(keyword)) return ContentType.SERIES
        }
        for (keyword in movieGroupKeywords) {
            if (groupLower.contains(keyword)) return ContentType.MOVIE
        }

        if (groupLower.startsWith("vod")) return ContentType.MOVIE

        if (reGenreVod.containsMatchIn(groupLower)) return ContentType.MOVIE

        // --- Name-based detection (using precompiled regexes) ---
        if (reEpisodeS.containsMatchIn(nameLower)) return ContentType.SERIES
        if (reSeason.containsMatchIn(nameLower)) return ContentType.SERIES
        if (reEp.containsMatchIn(nameLower)) return ContentType.SERIES
        if (reEpisodeWord.containsMatchIn(nameLower)) return ContentType.SERIES

        if (reYear.containsMatchIn(nameLower) && !groupLower.contains("live") && !groupLower.contains("channel")) {
            if (!urlLower.contains(".m3u8") && !urlLower.contains("/live/")) {
                return ContentType.MOVIE
            }
        }

        return ContentType.LIVE
    }

    private fun parseExtInf(line: String): ExtInfData {
        var name = ""
        var logoUrl = ""
        var group = "Uncategorized"
        var tvgId = ""
        var tvgName = ""

        val idMatch = reTvgId.find(line)
        if (idMatch != null) tvgId = idMatch.groupValues[1]

        val nameMatch = reTvgName.find(line)
        if (nameMatch != null) tvgName = nameMatch.groupValues[1]

        val logoMatch = reTvgLogo.find(line)
        if (logoMatch != null) logoUrl = logoMatch.groupValues[1]

        val groupMatch = reGroupTitle.find(line)
        if (groupMatch != null && groupMatch.groupValues[1].isNotBlank()) {
            group = groupMatch.groupValues[1]
        }

        val commaIndex = line.lastIndexOf(',')
        if (commaIndex >= 0 && commaIndex < line.length - 1) {
            name = line.substring(commaIndex + 1).trim()
        }

        if (name.isBlank() && tvgName.isNotBlank()) name = tvgName

        return ExtInfData(name, logoUrl, group, tvgId)
    }

    private data class ExtInfData(
        val name: String,
        val logoUrl: String,
        val group: String,
        val tvgId: String
    )
}
