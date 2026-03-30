package com.vistacore.launcher.iptv

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

class DispatcharrVodClient(
    serverUrl: String,
    private val apiKey: String
) {
    private val base = serverUrl.trimEnd('/')

    private val client = TlsCompat.apply(OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS))
        .build()

    private val gson = Gson()

    suspend fun getMovies(): List<Channel> = withContext(Dispatchers.IO) {
        val all = mutableListOf<DispatcharrMovie>()
        var page = 1
        while (true) {
            val body = fetch("$base/api/vod/movies/?page=$page&page_size=100")
            val result = gson.fromJson(body, DispatcharrMoviesPage::class.java)
            all.addAll(result.results)
            if (result.next == null) break
            page++
        }
        all.filter { !it.name.lowercase().contains("4k") }
            .mapIndexed { idx, m ->
                Channel(
                    id = "dc_movie_${m.uuid}",
                    name = m.name,
                    streamUrl = "$base/proxy/vod/movie/${m.uuid}",
                    logoUrl = m.logo?.cacheUrl ?: m.logo?.url ?: "",
                    category = m.genre.ifBlank { "Movies" },
                    number = idx + 1,
                    contentType = ContentType.MOVIE
                )
            }
    }

    suspend fun getSeries(): List<Channel> = withContext(Dispatchers.IO) {
        val all = mutableListOf<DispatcharrSeriesItem>()
        var page = 1
        while (true) {
            val body = fetch("$base/api/vod/series/?page=$page&page_size=100")
            val result = gson.fromJson(body, DispatcharrSeriesPage::class.java)
            all.addAll(result.results)
            if (result.next == null) break
            page++
        }
        all.mapIndexed { idx, s ->
            Channel(
                id = "dc_series_${s.uuid}",
                name = s.name,
                streamUrl = "$base/proxy/vod/series/${s.uuid}",
                logoUrl = s.logo?.cacheUrl ?: s.logo?.url ?: "",
                category = s.genre.ifBlank { "Series" },
                number = idx + 1,
                contentType = ContentType.SERIES
            )
        }
    }

    fun probeMovieCount(): Int {
        val body = fetch("$base/api/vod/movies/?page=1&page_size=1")
        return gson.fromJson(body, DispatcharrMoviesPage::class.java).count
    }

    fun probeSeriesCount(): Int {
        val body = fetch("$base/api/vod/series/?page=1&page_size=1")
        return gson.fromJson(body, DispatcharrSeriesPage::class.java).count
    }

    private fun fetch(url: String): String {
        val request = Request.Builder()
            .url(url)
            .header("X-API-Key", apiKey)
            .build()
        val response = client.newCall(request).execute()
        if (!response.isSuccessful) throw Exception("HTTP ${response.code}")
        return response.body?.string() ?: throw Exception("Empty response")
    }
}

private data class DispatcharrMoviesPage(
    val count: Int = 0,
    val next: String? = null,
    val results: List<DispatcharrMovie> = emptyList()
)

private data class DispatcharrMovie(
    val uuid: String = "",
    val name: String = "",
    val genre: String = "",
    val logo: DispatcharrLogo? = null
)

private data class DispatcharrSeriesPage(
    val count: Int = 0,
    val next: String? = null,
    val results: List<DispatcharrSeriesItem> = emptyList()
)

private data class DispatcharrSeriesItem(
    val uuid: String = "",
    val name: String = "",
    val genre: String = "",
    val logo: DispatcharrLogo? = null
)

private data class DispatcharrLogo(
    val url: String? = null,
    @SerializedName("cache_url") val cacheUrl: String? = null
)
