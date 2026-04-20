package com.vistacore.launcher.iptv

import android.util.Log
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.vistacore.launcher.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Fetches cast photos and backdrops from TMDB via our Cloudflare Worker
 * proxy. The Worker holds the TMDB API key server-side so the APK ships
 * without one.
 *
 * Base URL comes from [BuildConfig.TMDB_PROXY_BASE] and is overridable via
 * the `tmdbProxyBase` gradle property. If the Worker isn't configured or
 * returns non-200, every method returns empty results — callers fall back
 * to Xtream's plain cast-as-string.
 */
class TmdbClient {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()
    private val gson = Gson()

    private val base: String = BuildConfig.TMDB_PROXY_BASE

    /**
     * Look up a TMDB id by title + year. Returns null when no match is
     * returned or the proxy is unreachable.
     */
    suspend fun searchId(title: String, year: String, type: TmdbType): Int? =
        withContext(Dispatchers.IO) {
            if (title.isBlank()) return@withContext null
            val qs = StringBuilder()
            qs.append("path=").append(enc("search/${type.path}"))
            qs.append("&query=").append(enc(title))
            if (year.isNotBlank()) qs.append("&year=").append(enc(year.take(4)))
            val body = fetch("$base/api/tmdb?$qs") ?: return@withContext null
            try {
                val resp = gson.fromJson(body, TmdbSearchResponse::class.java)
                resp?.results?.firstOrNull()?.id
            } catch (_: Exception) { null }
        }

    /**
     * Fetch top N cast members with profile photos. Returns an empty list
     * on any failure so callers can degrade gracefully to initials avatars.
     */
    suspend fun getCredits(tmdbId: Int, type: TmdbType, limit: Int = 12): List<CastMember> =
        withContext(Dispatchers.IO) {
            val path = "${type.path}/$tmdbId/credits"
            val body = fetch("$base/api/tmdb?path=${enc(path)}") ?: return@withContext emptyList()
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

    private fun fetch(url: String): String? {
        return try {
            val req = Request.Builder().url(url).build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.d(TAG, "TMDB proxy ${resp.code} for $url")
                    return null
                }
                resp.body?.string()
            }
        } catch (e: Exception) {
            Log.d(TAG, "TMDB proxy fetch failed: ${e.message}")
            null
        }
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
