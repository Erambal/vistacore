package com.vistacore.launcher.iptv

import android.util.Log
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

data class JellyfinAuth(
    val server: String,
    val username: String,
    val password: String
) {
    val baseUrl: String
        get() = server.trimEnd('/')
}

class JellyfinClient(private val auth: JellyfinAuth) {

    private val client = TlsCompat.applyTrustAll(
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
    ).build()

    private val gson = Gson()

    private var accessToken: String = ""
    private var userId: String = ""

    // Client identification header required by Jellyfin
    private val authHeader: String
        get() {
            val base = "MediaBrowser Client=\"VistaCore\", Device=\"AndroidTV\", " +
                "DeviceId=\"$DEVICE_ID\", Version=\"1.0\""
            return if (accessToken.isNotBlank()) "$base, Token=\"$accessToken\"" else base
        }

    suspend fun authenticate() = withContext(Dispatchers.IO) {
        val url = "${auth.baseUrl}/Users/AuthenticateByName"
        val body = gson.toJson(mapOf("Username" to auth.username, "Pw" to auth.password))
            .toRequestBody("application/json".toMediaType())

        val req = Request.Builder()
            .url(url)
            .header("X-Emby-Authorization", authHeader)
            .post(body)
            .build()

        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw RuntimeException("Jellyfin auth failed: HTTP ${resp.code}")
            }
            val json = resp.body?.string() ?: throw RuntimeException("Empty auth response")
            val result = gson.fromJson(json, JellyfinAuthResponse::class.java)
            accessToken = result.AccessToken ?: ""
            userId = result.User?.Id ?: ""
            if (accessToken.isBlank() || userId.isBlank()) {
                throw RuntimeException("Jellyfin auth missing token or user")
            }
            Log.d(TAG, "Jellyfin authenticated as user $userId")
        }
    }

    suspend fun getMovies(): List<Channel> = fetchItems("Movie", ContentType.MOVIE)

    suspend fun getSeries(): List<Channel> = fetchItems("Series", ContentType.SERIES)

    private suspend fun fetchItems(
        includeItemTypes: String,
        contentType: ContentType
    ): List<Channel> = withContext(Dispatchers.IO) {
        requireAuth()
        val url = "${auth.baseUrl}/Users/$userId/Items" +
            "?IncludeItemTypes=$includeItemTypes" +
            "&Recursive=true" +
            "&Fields=ProductionYear,Genres,Overview" +
            "&SortBy=SortName" +
            "&SortOrder=Ascending"

        val req = Request.Builder()
            .url(url)
            .header("X-Emby-Authorization", authHeader)
            .get()
            .build()

        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw RuntimeException("Jellyfin $includeItemTypes fetch failed: HTTP ${resp.code}")
            }
            val json = resp.body?.string() ?: return@use emptyList<Channel>()
            val result = gson.fromJson(json, JellyfinItemsResponse::class.java)
            val items = result.Items ?: return@use emptyList<Channel>()

            items.mapIndexedNotNull { idx, item ->
                val id = item.Id ?: return@mapIndexedNotNull null
                val name = item.Name ?: return@mapIndexedNotNull null
                val category = item.Genres?.firstOrNull()
                    ?: if (contentType == ContentType.MOVIE) "Movies" else "Series"

                val streamUrl = if (contentType == ContentType.MOVIE) {
                    "${auth.baseUrl}/Videos/$id/stream?api_key=$accessToken&static=true"
                } else {
                    // Series playback is handled by drilling into episodes — we store
                    // a marker URL; the browser opens episode selection via getEpisodes().
                    "jellyfin://series/$id"
                }

                val logoUrl = "${auth.baseUrl}/Items/$id/Images/Primary?maxWidth=400&quality=90"

                Channel(
                    id = "jf_${contentType.name.lowercase()}_$id",
                    name = name,
                    streamUrl = streamUrl,
                    logoUrl = logoUrl,
                    category = category,
                    number = idx + 1,
                    contentType = contentType,
                    source = ContentSource.JELLYFIN,
                    year = item.ProductionYear ?: 0
                )
            }
        }
    }

    /** Fetch episodes for a Jellyfin series. Used when the user opens a series tile. */
    suspend fun getEpisodes(seriesId: String): List<Channel> = withContext(Dispatchers.IO) {
        requireAuth()
        val url = "${auth.baseUrl}/Shows/$seriesId/Episodes" +
            "?UserId=$userId&Fields=Overview&SortBy=ParentIndexNumber,IndexNumber"

        val req = Request.Builder()
            .url(url)
            .header("X-Emby-Authorization", authHeader)
            .get()
            .build()

        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return@use emptyList<Channel>()
            val json = resp.body?.string() ?: return@use emptyList<Channel>()
            val result = gson.fromJson(json, JellyfinItemsResponse::class.java)
            val items = result.Items ?: return@use emptyList<Channel>()

            items.mapNotNull { ep ->
                val id = ep.Id ?: return@mapNotNull null
                val season = ep.ParentIndexNumber ?: 0
                val number = ep.IndexNumber ?: 0
                val title = ep.Name ?: ""
                val label = "S${season.toString().padStart(2, '0')}E${number.toString().padStart(2, '0')}" +
                    if (title.isNotBlank()) " - $title" else ""
                Channel(
                    id = "jf_ep_$id",
                    name = label,
                    streamUrl = "${auth.baseUrl}/Videos/$id/stream?api_key=$accessToken&static=true",
                    logoUrl = "${auth.baseUrl}/Items/$id/Images/Primary?maxWidth=400&quality=90",
                    category = "Season $season",
                    contentType = ContentType.SERIES,
                    source = ContentSource.JELLYFIN
                )
            }
        }
    }

    private fun requireAuth() {
        if (accessToken.isBlank() || userId.isBlank()) {
            throw RuntimeException("Jellyfin client not authenticated — call authenticate() first")
        }
    }

    companion object {
        private const val TAG = "JellyfinClient"
        private const val DEVICE_ID = "vistacore-android-tv"
    }
}

// --- Jellyfin API response models ---

data class JellyfinAuthResponse(
    val AccessToken: String? = null,
    val User: JellyfinUser? = null
)

data class JellyfinUser(
    val Id: String? = null,
    val Name: String? = null
)

data class JellyfinItemsResponse(
    val Items: List<JellyfinItem>? = null,
    val TotalRecordCount: Int = 0
)

data class JellyfinItem(
    val Id: String? = null,
    val Name: String? = null,
    val Type: String? = null,
    val ProductionYear: Int? = null,
    val Genres: List<String>? = null,
    val Overview: String? = null,
    val ParentIndexNumber: Int? = null,
    val IndexNumber: Int? = null
)
