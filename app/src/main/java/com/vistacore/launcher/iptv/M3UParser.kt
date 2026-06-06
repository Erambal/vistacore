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

    // --- Provider noise cleanup -------------------------------------------
    // The fixesto/Dispatcharr feed prefixes names and categories with tags
    // ("US| ", "PRIME| ", "CITY| "), wraps headers in hashes ("##### PRIME #####")
    // and decorates everything with superscript unicode ("ᴿᴬᵂ ⁶⁰ᶠᵖˢ", "ᶜᶦᵗʸ ᴴᴰ")
    // and "VIP" tags. We strip all of that for display.

    // Repeated leading "XXX| " tag on a channel NAME (US|, PRIME|, CITY|,
    // NFL TEAMS|, NHL TEAM|, …). Token is uppercase letters/digits/space/slash,
    // up to 12 chars, so we don't eat the real channel identity that follows.
    private val reNameTag = Regex("""^\s*[A-Za-z0-9/ ]{1,12}\|\s*""")

    // Leading region tag on a CATEGORY ("US| "). 24/7 groups use a space, not a
    // pipe, so this leaves "24/7 …" untouched (kept per design).
    private val reRegionTag = Regex("""^\s*[A-Za-z0-9]{1,4}\|\s*""")

    // Region prefix on a NAME that may carry a parenthetical feed id before the
    // pipe, e.g. "US (ESPN+ 001) | Auckland vs …" -> "Auckland vs …". Anchored
    // to a known region code so we only swallow up to the FIRST pipe when the
    // name genuinely starts with a region tag (not real titles containing "|").
    private val reRegionPrefix = Regex(
        """^\s*(?:US|USA|UK|GB|CA|AU|NZ|IE|FR|DE|ES|IT|PT|NL|BE|SE|NO|DK|FI|PL|BR|MX|AR|IN|PK|TR|EU|LA)\b[^|]*\|\s*"""
    )

    // Decorative codepoints: modifier/superscript letters, super/subscript
    // digits, and '#' border characters.
    private val reDecorations = Regex("[#\\u02B0-\\u02FF\\u1D00-\\u1DBF\\u2070-\\u209F]")
    private val reVip = Regex("""\bVIP\b""", RegexOption.IGNORE_CASE)
    private val reDirecTv = Regex("""DIREC\s*TV""", RegexOption.IGNORE_CASE)
    private val reMultiSpace = Regex("""\s{2,}""")
    // Leftover separators stranded after stripping (e.g. "DirecTV /"). Keeps
    // meaningful trailing "+"/"&" ("AMC +", "BET+").
    private val reStrandedSep = Regex("""^[\s/\-|]+|[\s/\-|]+$""")

    // Tokens kept fully uppercase when title-casing ALL-CAPS provider text.
    private val acronyms = setOf(
        "US", "USA", "UK", "TV", "HD", "HQ", "4K", "PPV", "VOD",
        "ESPN", "NFL", "NHL", "NBA", "MLB", "MLS", "NCAA", "NCAAF", "NCAAB",
        "PGA", "UFC", "WWE", "AEW", "NASCAR", "F1",
        "HBO", "AMC", "BET", "TBS", "TNT", "FX", "FXX", "CNN", "CNBC", "MSNBC",
        "CBS", "NBC", "ABC", "FOX", "PBS", "MTV", "TLC", "IFC", "MGM", "CW",
        "ION", "EPIX", "SHO", "STARZ", "MAX", "PAC", "SEC", "BTN", "ACC",
        "USA", "WE", "OWN", "AXS", "POP", "NHK", "RT", "PBA",
        "BBC", "CMT", "AE", "VH", "HGTV", "TCM", "TMC", "ID", "SD", "UHD", "FHD"
    )

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

    private val client = TlsCompat.applyTrustAll(OkHttpClient.Builder()
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
        // `use` on the response so the underlying socket closes even when
        // parseFromReader throws halfway through the stream — without it
        // the body's input stream would close (the inner `use`) but the
        // OkHttp connection itself would leak its FD until pool eviction.
        client.newCall(request).execute().use { response ->
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

        return ExtInfData(cleanName(name), logoUrl, cleanCategory(group), tvgId)
    }

    /**
     * Channel display name: strip every leading "XXX| " provider tag (US|,
     * PRIME|, CITY|, NFL TEAMS|, …), drop decorative junk and tidy casing.
     * "PRIME| 5STARMAX EAST ᴿᴬᵂ" -> "5Starmax East".
     */
    private fun cleanName(raw: String): String {
        // First peel a region prefix that may include a "(ESPN+ 001)" feed id.
        var s = reRegionPrefix.replaceFirst(raw, "")
        // Then peel any remaining simple "XXX| " tags ("PRIME| CITY| Foo" -> "Foo").
        while (true) {
            val m = reNameTag.find(s) ?: break
            s = s.substring(m.range.last + 1)
        }
        val cleaned = tidy(s)
        // Bare-tag entries ("NHL LIVE|", "MLB 17 |") would clean to nothing —
        // keep the tag text instead of showing a blank name.
        return if (cleaned.isBlank()) tidy(raw) else cleaned
    }

    /**
     * Category label: drop the leading region tag ("US| ") but keep a "24/7"
     * marker, then strip decorations and tidy casing.
     * "US| ESPN PLUS" -> "ESPN Plus"; "24/7 MOVIES & SERIES" -> "24/7 Movies & Series".
     */
    private fun cleanCategory(raw: String): String {
        val s = reRegionTag.replaceFirst(raw, "")
        val cleaned = tidy(s)
        return if (cleaned.isBlank()) "Uncategorized" else cleaned
    }

    /** Shared finishing pass: DirecTV fix, decorations/VIP removal, casing. */
    private fun tidy(input: String): String {
        var s = reDirecTv.replace(input, "DirecTV")
        s = reDecorations.replace(s, " ")
        s = reVip.replace(s, " ")
        s = reMultiSpace.replace(s, " ").trim()
        s = reStrandedSep.replace(s, "").trim()
        return titleCase(s)
    }

    /**
     * Title-case ALL-CAPS provider text while preserving known acronyms
     * (ESPN, NFL, HBO …) and the special-cased "DirecTV". Tokens that already
     * contain lowercase are left as-is so we never mangle real mixed-case names.
     */
    private fun titleCase(text: String): String {
        if (text.any { it.isLowerCase() }) return text
        return text.split(' ').joinToString(" ") { token ->
            when {
                token.isEmpty() -> token
                // Match on letters only so "BET+" / "ESPN," still resolve.
                acronyms.contains(token.filter { it.isLetter() }.uppercase()) -> token.uppercase()
                else -> {
                    // Capitalize the first letter of each alphabetic run, so
                    // "kids/family" -> "Kids/Family", "5starmax" -> "5Starmax".
                    val sb = StringBuilder(token.length)
                    var startOfRun = true
                    for (ch in token.lowercase()) {
                        if (ch.isLetter()) {
                            sb.append(if (startOfRun) ch.uppercaseChar() else ch)
                            startOfRun = false
                        } else {
                            sb.append(ch)
                            startOfRun = true
                        }
                    }
                    sb.toString()
                }
            }
        }
    }

    private data class ExtInfData(
        val name: String,
        val logoUrl: String,
        val group: String,
        val tvgId: String
    )
}
