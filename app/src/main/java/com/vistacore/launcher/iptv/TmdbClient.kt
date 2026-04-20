package com.vistacore.launcher.iptv

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.vistacore.launcher.BuildConfig
import com.vistacore.launcher.data.PrefsManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Fetches cast photos, backdrops, and trailer video IDs from TMDB.
 *
 * Two transport modes:
 *  1. Direct — if the user has entered their own TMDB v3 API key under
 *     Settings → Connections → "TMDB API Key", every request goes to
 *     api.themoviedb.org with `?api_key=…`. This is the recommended path.
 *  2. Worker proxy — if no key is set, requests go to
 *     [BuildConfig.TMDB_PROXY_BASE]/api/tmdb which attaches the key
 *     server-side. Useful when you don't want to ship a key in the APK.
 *
 * Both paths return empty results on any failure so callers degrade
 * gracefully to Xtream-only data / initial avatars.
 */
class TmdbClient(context: Context? = null) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()
    private val gson = Gson()

    // Resolution order: user's own key in Settings → baked-in default from
    // BuildConfig → Worker proxy (if TMDB_PROXY_BASE is reachable). The
    // default key lets every install light up cast photos + trailers out
    // of the box without any user setup.
    private val userKey: String = context?.let { PrefsManager(it).tmdbApiKey }.orEmpty()
        .ifBlank { BuildConfig.TMDB_DEFAULT_KEY }
    private val proxyBase: String = BuildConfig.TMDB_PROXY_BASE

    suspend fun searchId(title: String, year: String, type: TmdbType): Int? =
        withContext(Dispatchers.IO) {
            if (title.isBlank()) return@withContext null
            val path = "search/${type.path}"
            val extra = buildMap<String, String> {
                put("query", title)
                if (year.isNotBlank()) put("year", year.take(4))
            }
            val body = get(path, extra) ?: return@withContext null
            try {
                val resp = gson.fromJson(body, TmdbSearchResponse::class.java)
                resp?.results?.firstOrNull()?.id
            } catch (_: Exception) { null }
        }

    suspend fun getCredits(tmdbId: Int, type: TmdbType, limit: Int = 12): List<CastMember> =
        withContext(Dispatchers.IO) {
            val body = get("${type.path}/$tmdbId/credits") ?: return@withContext emptyList()
            try {
                val resp = gson.fromJson(body, TmdbCreditsResponse::class.java)
                resp?.cast?.take(limit)?.map {
                    CastMember(
                        name = it.name.orEmpty(),
                        character = it.character.orEmpty(),
                        profileUrl = it.profile_path?.let { p -> "https://image.tmdb.org/t/p/w300$p" }.orEmpty()
                    )
                } ?: emptyList()
            } catch (_: Exception) { emptyList() }
        }

    /**
     * Best YouTube trailer video id for a title. Prefers official Trailer
     * videos, falls back to Teaser, then any YouTube video. Returns null
     * when nothing matches.
     */
    suspend fun getTrailerYoutubeId(tmdbId: Int, type: TmdbType): String? =
        withContext(Dispatchers.IO) {
            val body = get("${type.path}/$tmdbId/videos") ?: return@withContext null
            try {
                val resp = gson.fromJson(body, TmdbVideosResponse::class.java)
                val videos = resp?.results.orEmpty()
                    .filter { (it.site ?: "").equals("YouTube", true) && !it.key.isNullOrBlank() }
                val trailer = videos.firstOrNull { (it.type ?: "").equals("Trailer", true) && it.official == true }
                    ?: videos.firstOrNull { (it.type ?: "").equals("Trailer", true) }
                    ?: videos.firstOrNull { (it.type ?: "").equals("Teaser", true) }
                    ?: videos.firstOrNull()
                trailer?.key
            } catch (_: Exception) { null }
        }

    private fun get(path: String, params: Map<String, String> = emptyMap()): String? {
        val url = buildUrl(path, params) ?: return null
        return try {
            val req = Request.Builder().url(url).build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.d(TAG, "TMDB ${resp.code} for $path")
                    return null
                }
                resp.body?.string()
            }
        } catch (e: Exception) {
            Log.d(TAG, "TMDB fetch failed for $path: ${e.message}")
            null
        }
    }

    private fun buildUrl(path: String, params: Map<String, String>): String? {
        val clean = path.trimStart('/')
        val qs = StringBuilder()
        if (userKey.isNotBlank()) {
            // Direct to TMDB.
            qs.append("api_key=").append(enc(userKey))
            for ((k, v) in params) qs.append('&').append(enc(k)).append('=').append(enc(v))
            return "https://api.themoviedb.org/3/$clean?$qs"
        }
        if (proxyBase.isBlank()) return null
        // Via Worker proxy: it prepends its own api_key server-side, we just
        // pass `path` and any extra query params.
        qs.append("path=").append(enc(clean))
        for ((k, v) in params) qs.append('&').append(enc(k)).append('=').append(enc(v))
        return "${proxyBase.trimEnd('/')}/api/tmdb?$qs"
    }

    private fun enc(s: String) = URLEncoder.encode(s, Charsets.UTF_8.name())

    companion object {
        private const val TAG = "TmdbClient"
    }
}

enum class TmdbType(val path: String) {
    MOVIE("movie"),
    TV("tv")
}

// --- Wire format ---

private data class TmdbSearchResponse(val results: List<TmdbSearchResult>?)
private data class TmdbSearchResult(val id: Int)

private data class TmdbCreditsResponse(val cast: List<TmdbCastMember>?)
private data class TmdbCastMember(
    val name: String? = null,
    val character: String? = null,
    @SerializedName("profile_path") val profile_path: String? = null
)

private data class TmdbVideosResponse(val results: List<TmdbVideo>?)
private data class TmdbVideo(
    val key: String? = null,
    val site: String? = null,
    val type: String? = null,
    val official: Boolean? = null
)
