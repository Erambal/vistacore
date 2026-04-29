package com.vistacore.launcher.iptv

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Client for the OpenSubtitles REST API (v1).
 * Searches and downloads subtitle files (.srt) for movies and shows.
 *
 * Free tier: 5 req/sec, 100 downloads/day with an API key.
 * Get a key at https://www.opensubtitles.com/consumers
 */
class OpenSubtitlesClient(private val apiKey: String) {

    companion object {
        private const val TAG = "OpenSubtitles"
        private const val BASE_URL = "https://api.opensubtitles.com/api/v1"

        // Regex to extract year from title like "The Matrix (1999)"
        private val YEAR_REGEX = Regex("""\((\d{4})\)""")
        // Common junk in IPTV titles
        private val QUALITY_TAGS = Regex(
            """\b(1080[pi]|720p|480p|4K|UHD|HDR|BluRay|BRRip|WEB-?DL|WEBRip|HDRip|DVDRip|HDTV|AMZN|NF|REPACK|PROPER|x264|x265|HEVC|AAC|DD5\.1|DTS|MULTI|DUAL)\b""",
            RegexOption.IGNORE_CASE
        )
        // Brackets and their contents
        private val BRACKETS = Regex("""\[.*?]""")

        /**
         * Clean an IPTV movie title for subtitle search.
         * "The Matrix (1999) 1080p BluRay" → "The Matrix"
         * Returns Pair(cleanTitle, extractedYear)
         */
        fun cleanTitle(raw: String): Pair<String, String?> {
            val yearMatch = YEAR_REGEX.find(raw)
            val year = yearMatch?.groupValues?.get(1)

            var clean = raw
            // Remove year in parens
            if (yearMatch != null) clean = clean.replace(yearMatch.value, "")
            // Remove bracketed content
            clean = BRACKETS.replace(clean, "")
            // Remove quality tags
            clean = QUALITY_TAGS.replace(clean, "")
            // Remove leftover dots/underscores used as separators
            clean = clean.replace('.', ' ').replace('_', ' ')
            // Collapse whitespace
            clean = clean.trim().replace(Regex("\\s+"), " ")

            return clean to year
        }
    }

    private val client = TlsCompat.applyTrustAll(OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS))
        .build()

    private val gson = Gson()

    /**
     * Search for subtitles by title and optional year/language.
     */
    suspend fun search(
        query: String,
        year: String? = null,
        languages: String? = null
    ): List<SubtitleResult> = withContext(Dispatchers.IO) {
        val urlBuilder = StringBuilder("$BASE_URL/subtitles?query=${encode(query)}")
        if (!year.isNullOrBlank()) urlBuilder.append("&year=$year")
        if (!languages.isNullOrBlank()) urlBuilder.append("&languages=$languages")

        val request = Request.Builder()
            .url(urlBuilder.toString())
            .addHeader("Api-Key", apiKey)
            .addHeader("User-Agent", "VistaCore v1.1")
            .build()

        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "Search failed: HTTP ${response.code}")
                    return@withContext emptyList()
                }
                val body = response.body?.string() ?: return@withContext emptyList()
                val parsed = gson.fromJson(body, SearchResponse::class.java)
                parsed.data?.mapNotNull { item ->
                    val attrs = item.attributes ?: return@mapNotNull null
                    val file = attrs.files?.firstOrNull() ?: return@mapNotNull null
                    SubtitleResult(
                        fileId = file.file_id ?: 0,
                        language = attrs.language ?: "unknown",
                        title = attrs.release ?: attrs.feature_details?.title ?: query,
                        downloadCount = attrs.download_count ?: 0,
                        year = attrs.feature_details?.year?.toString()
                    )
                } ?: emptyList()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Search error", e)
            emptyList()
        }
    }

    /**
     * Download a subtitle file and save it to the cache directory.
     * Returns the local file path, or null on failure.
     */
    suspend fun download(
        fileId: Int,
        cacheDir: File
    ): String? = withContext(Dispatchers.IO) {
        try {
            // Step 1: Request download link
            val jsonBody = """{"file_id": $fileId}"""
            val request = Request.Builder()
                .url("$BASE_URL/download")
                .addHeader("Api-Key", apiKey)
                .addHeader("User-Agent", "VistaCore v1.1")
                .addHeader("Content-Type", "application/json")
                .post(jsonBody.toRequestBody("application/json".toMediaType()))
                .build()

            val downloadUrl = client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "Download request failed: HTTP ${response.code}")
                    return@withContext null
                }
                val body = response.body?.string() ?: return@withContext null
                val parsed = gson.fromJson(body, DownloadResponse::class.java)
                parsed.link
            }
            if (downloadUrl.isNullOrBlank()) {
                Log.w(TAG, "No download link in response")
                return@withContext null
            }

            // Step 2: Download the actual subtitle file
            val fileRequest = Request.Builder().url(downloadUrl).build()
            val subtitleDir = File(cacheDir, "subtitles").also { it.mkdirs() }
            val outFile = File(subtitleDir, "sub_${fileId}.srt")
            client.newCall(fileRequest).execute().use { fileResponse ->
                if (!fileResponse.isSuccessful) {
                    Log.w(TAG, "File download failed: HTTP ${fileResponse.code}")
                    return@withContext null
                }
                outFile.writeBytes(fileResponse.body?.bytes() ?: return@withContext null)
            }

            Log.d(TAG, "Subtitle downloaded: ${outFile.absolutePath}")
            outFile.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "Download error", e)
            null
        }
    }

    private fun encode(s: String): String =
        java.net.URLEncoder.encode(s, "UTF-8")

    // --- API Response Models ---

    data class SearchResponse(val data: List<SearchItem>?)
    data class SearchItem(val attributes: SearchAttributes?)
    data class SearchAttributes(
        val language: String?,
        val release: String?,
        val download_count: Int?,
        val files: List<SubFile>?,
        val feature_details: FeatureDetails?
    )
    data class FeatureDetails(val title: String?, val year: Int?)
    data class SubFile(val file_id: Int?)
    data class DownloadResponse(val link: String?)
}

/** A subtitle search result shown to the user. */
data class SubtitleResult(
    val fileId: Int,
    val language: String,
    val title: String,
    val downloadCount: Int,
    val year: String? = null
)
