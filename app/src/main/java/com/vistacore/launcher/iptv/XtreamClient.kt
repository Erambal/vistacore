package com.vistacore.launcher.iptv

import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

class XtreamClient(private val auth: XtreamAuth) {

    private val client = TlsCompat.applyTrustAll(OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS))
        .build()

    // VOD responses can be 10-20MB — needs a much longer timeout
    private val vodClient = TlsCompat.applyTrustAll(OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(300, TimeUnit.SECONDS))
        .build()

    private val gson = Gson()

    /**
     * Authenticate and verify credentials.
     */
    suspend fun authenticate(): XtreamAuthResponse = withContext(Dispatchers.IO) {
        val url = "${auth.baseUrl}?username=${auth.username}&password=${auth.password}"
        val body = fetch(url)
        gson.fromJson(body, XtreamAuthResponse::class.java)
    }

    /**
     * Get all live TV categories.
     */
    suspend fun getCategories(): List<XtreamCategory> = withContext(Dispatchers.IO) {
        val url = "${auth.baseUrl}?username=${auth.username}&password=${auth.password}&action=get_live_categories"
        val body = fetch(url)
        val type = object : TypeToken<List<XtreamCategory>>() {}.type
        gson.fromJson(body, type)
    }

    /**
     * Get live streams, optionally filtered by category.
     */
    suspend fun getStreams(categoryId: String? = null): List<XtreamStream> = withContext(Dispatchers.IO) {
        var url = "${auth.baseUrl}?username=${auth.username}&password=${auth.password}&action=get_live_streams"
        if (categoryId != null) {
            url += "&category_id=$categoryId"
        }
        val body = fetch(url)
        val type = object : TypeToken<List<XtreamStream>>() {}.type
        gson.fromJson(body, type)
    }

    /**
     * Convert Xtream streams to our Channel model.
     */
    suspend fun getChannels(categoryId: String? = null): List<Channel> {
        val streams = getStreams(categoryId)
        val categories = getCategories()
        val categoryMap = categories.associate { it.category_id to it.category_name }

        return streams.map { stream ->
            Channel(
                id = "xt_${stream.stream_id}",
                name = stream.name,
                streamUrl = "${auth.liveStreamUrl}/${stream.stream_id}.${stream.container_extension}",
                logoUrl = stream.stream_icon ?: "",
                category = categoryMap[stream.category_id] ?: "Uncategorized",
                number = stream.num,
                epgId = stream.epg_channel_id ?: ""
            )
        }
    }

    /**
     * Get VOD movies as Channel objects.
     */
    suspend fun getMovies(): List<Channel> = withContext(Dispatchers.IO) {
        val categories = fetchVodCategories()
        val categoryMap = categories.associate { it.category_id to it.category_name }

        val url = "${auth.baseUrl}?username=${auth.username}&password=${auth.password}&action=get_vod_streams"
        val body = fetchWithClient(vodClient, url)
        val type = object : TypeToken<List<XtreamVodStream>>() {}.type
        val streams: List<XtreamVodStream> = gson.fromJson(body, type) ?: emptyList()

        streams
            .filter { vod ->
                val name = (vod.name ?: "").lowercase()
                !name.contains("4k")
            }
            .mapIndexed { idx, vod ->
                Channel(
                    id = "xt_vod_${vod.stream_id}",
                    name = vod.name ?: "",
                    streamUrl = "${auth.movieStreamUrl}/${vod.stream_id}.${vod.container_extension}",
                    logoUrl = vod.stream_icon ?: "",
                    category = categoryMap[vod.category_id] ?: "Movies",
                    number = idx + 1,
                    contentType = ContentType.MOVIE
                )
            }
    }

    /**
     * Get series as Channel objects.
     * Fetches per category (with limited concurrency) to work around server-side
     * limits on the bulk get_series endpoint that cap results at ~30 per category.
     */
    suspend fun getSeries(): List<Channel> = withContext(Dispatchers.IO) {
        val categories = fetchSeriesCategories()
        val categoryMap = categories.associate { it.category_id to it.category_name }
        val seriesType = object : TypeToken<List<XtreamSeries>>() {}.type

        // First try the bulk endpoint — if it returns a suspiciously low number
        // (< 10 per category on average), fall back to per-category fetching.
        val bulkUrl = "${auth.baseUrl}?username=${auth.username}&password=${auth.password}&action=get_series"
        val bulkList: List<XtreamSeries> = try {
            val body = fetchWithClient(vodClient, bulkUrl)
            gson.fromJson(body, seriesType) ?: emptyList()
        } catch (_: Exception) { emptyList() }

        val threshold = categories.size * 10
        val seriesList: List<XtreamSeries> = if (bulkList.size >= threshold) {
            Log.d("XtreamClient", "getSeries: bulk returned ${bulkList.size} (threshold $threshold) — using bulk")
            bulkList
        } else {
            Log.d("XtreamClient", "getSeries: bulk returned ${bulkList.size} < threshold $threshold — fetching per category")
            // Fetch per category with 5 concurrent requests max
            val semaphore = Semaphore(5)
            coroutineScope {
                categories.map { cat ->
                    async {
                        semaphore.withPermit {
                            try {
                                val url = "${auth.baseUrl}?username=${auth.username}&password=${auth.password}&action=get_series&category_id=${cat.category_id}"
                                val body = fetchWithClient(vodClient, url)
                                gson.fromJson<List<XtreamSeries>>(body, seriesType) ?: emptyList()
                            } catch (_: Exception) { emptyList() }
                        }
                    }
                }.awaitAll()
            }.flatten().let { combined ->
                // Deduplicate by series_id
                val seen = mutableSetOf<Int>()
                combined.filter { seen.add(it.series_id) }
            }.also { Log.d("XtreamClient", "getSeries: per-category total = ${it.size}") }
        }

        seriesList.mapIndexed { idx, series ->
            Channel(
                id = "xt_series_${series.series_id}",
                name = series.name,
                streamUrl = "${auth.seriesStreamUrl}/${series.series_id}.${series.container_extension}",
                logoUrl = series.cover ?: "",
                category = categoryMap[series.category_id] ?: "Series",
                number = idx + 1,
                contentType = ContentType.SERIES
            )
        }
    }

    /**
     * Fetch episodes for a specific series via get_series_info.
     * Returns Channel objects for each episode, organized by season.
     *
     * Prefer [getSeriesDetail] for new callers — this one only returns the
     * episode list and is kept for existing code paths that don't need the
     * metadata block.
     */
    suspend fun getSeriesInfo(seriesId: Int): List<Channel> =
        getSeriesDetail(seriesId)?.episodes ?: emptyList()

    /**
     * Fetch both the rich metadata block (plot, cast, rating, etc.) and the
     * episode list for a series. Null on network/parse failure.
     */
    suspend fun getSeriesDetail(seriesId: Int): SeriesDetail? = withContext(Dispatchers.IO) {
        val url = "${auth.baseUrl}?username=${auth.username}&password=${auth.password}&action=get_series_info&series_id=$seriesId"
        try {
            val body = fetchWithClient(vodClient, url)
            val parsed = gson.fromJson(body, XtreamSeriesInfo::class.java) ?: return@withContext null
            val episodes = mutableListOf<Channel>()
            parsed.episodes?.forEach { (seasonNum, episodeList) ->
                for (ep in episodeList) {
                    val ext = ep.container_extension.ifBlank { "mp4" }
                    val epName = if (ep.title.isNotBlank()) {
                        "S${seasonNum.padStart(2, '0')}E${ep.episode_num.toString().padStart(2, '0')} - ${ep.title}"
                    } else {
                        "S${seasonNum.padStart(2, '0')}E${ep.episode_num.toString().padStart(2, '0')}"
                    }
                    episodes.add(
                        Channel(
                            id = "xt_ep_${ep.id}",
                            name = epName,
                            streamUrl = "${auth.seriesStreamUrl}/${ep.id}.$ext",
                            category = "Season $seasonNum",
                            contentType = ContentType.SERIES
                        )
                    )
                }
            }
            XtreamInfoMapper.toSeriesDetail(parsed.info, episodes)
        } catch (e: Exception) {
            Log.e("XtreamClient", "Failed to fetch series info for $seriesId", e)
            null
        }
    }

    /**
     * Fetch rich metadata for a single movie via get_vod_info.
     * Null on network/parse failure.
     */
    suspend fun getVodInfo(vodId: Int): VodDetail? = withContext(Dispatchers.IO) {
        val url = "${auth.baseUrl}?username=${auth.username}&password=${auth.password}&action=get_vod_info&vod_id=$vodId"
        try {
            val body = fetchWithClient(vodClient, url)
            val parsed = gson.fromJson(body, XtreamVodInfoResponse::class.java) ?: return@withContext null
            XtreamInfoMapper.toVodDetail(parsed.info)
        } catch (e: Exception) {
            Log.e("XtreamClient", "Failed to fetch VOD info for $vodId", e)
            null
        }
    }

    private fun fetchVodCategories(): List<XtreamCategory> {
        val url = "${auth.baseUrl}?username=${auth.username}&password=${auth.password}&action=get_vod_categories"
        val body = fetch(url)
        val type = object : TypeToken<List<XtreamCategory>>() {}.type
        return gson.fromJson(body, type) ?: emptyList()
    }

    private fun fetchSeriesCategories(): List<XtreamCategory> {
        val url = "${auth.baseUrl}?username=${auth.username}&password=${auth.password}&action=get_series_categories"
        val body = fetch(url)
        val type = object : TypeToken<List<XtreamCategory>>() {}.type
        return gson.fromJson(body, type) ?: emptyList()
    }

    /**
     * Get channels grouped by category.
     */
    suspend fun getChannelsByCategory(): Map<String, List<Channel>> {
        val channels = getChannels()
        return channels.groupBy { it.category }
    }

    /**
     * Get EPG data using Xtream's native short EPG API.
     * Returns EpgData with programs indexed by stream_id.
     * This is more reliable than xmltv.php which many providers disable.
     */
    suspend fun getEpg(channels: List<Channel>): EpgData = withContext(Dispatchers.IO) {
        val allPrograms = mutableListOf<EpgProgram>()
        val epgChannels = mutableMapOf<String, EpgChannel>()
        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).apply {
            timeZone = java.util.TimeZone.getTimeZone("UTC")
        }

        // Xtream get_simple_data_table returns ALL EPG data — try it first
        try {
            val url = "${auth.baseUrl}?username=${auth.username}&password=${auth.password}&action=get_simple_data_table&stream_id=all"
            val body = fetchWithClient(vodClient, url)
            val parsed = parseXtreamEpgResponse(body, sdf)
            if (parsed.programs.isNotEmpty()) {
                Log.d("XtreamClient", "get_simple_data_table returned ${parsed.programs.size} programs")
                return@withContext parsed
            }
        } catch (e: Exception) {
            Log.d("XtreamClient", "get_simple_data_table failed, falling back to per-channel: ${e.message}")
        }

        // Fallback: fetch per-channel short EPG (batched, limited concurrency)
        val semaphore = kotlinx.coroutines.sync.Semaphore(10)
        val liveChannels = channels.filter { it.contentType == ContentType.LIVE && it.epgId.isNotBlank() }
        Log.d("XtreamClient", "Per-channel EPG: ${channels.size} total channels, ${liveChannels.size} live with epgId")
        // Only fetch EPG for first 200 channels to avoid hammering the server
        val batch = liveChannels.take(200)

        var successCount = 0
        var failCount = 0

        coroutineScope {
            val jobs = batch.map { channel ->
                async {
                    semaphore.withPermit {
                        try {
                            val streamId = channel.id.removePrefix("xt_")
                            val url = "${auth.baseUrl}?username=${auth.username}&password=${auth.password}&action=get_short_epg&stream_id=$streamId&limit=6"
                            val body = fetch(url)
                            val parsed = parseXtreamEpgResponse(body, sdf)
                            synchronized(allPrograms) {
                                // Map programs to use the channel's epgId for consistency
                                for (p in parsed.programs) {
                                    allPrograms.add(p.copy(channelId = channel.epgId))
                                }
                                epgChannels[channel.epgId] = EpgChannel(channel.epgId, channel.name)
                                successCount++
                            }
                        } catch (e: Exception) {
                            synchronized(allPrograms) { failCount++ }
                            Log.d("XtreamClient", "EPG fetch failed for ${channel.name}: ${e.message}")
                        }
                    }
                }
            }
            jobs.awaitAll()
        }

        Log.d("XtreamClient", "Per-channel EPG: ${allPrograms.size} programs for ${epgChannels.size} channels (${successCount} ok, ${failCount} failed)")
        EpgData(epgChannels, allPrograms)
    }

    private fun parseXtreamEpgResponse(json: String, sdf: java.text.SimpleDateFormat): EpgData {
        val channels = mutableMapOf<String, EpgChannel>()
        val programs = mutableListOf<EpgProgram>()
        val now = System.currentTimeMillis()
        val cutoff = now - 2 * 3600000L // 2 hours ago

        try {
            val root = com.google.gson.JsonParser.parseString(json).asJsonObject
            val listings = root.getAsJsonArray("epg_listings") ?: return EpgData(channels, programs)

            for (elem in listings) {
                val obj = elem.asJsonObject
                val channelId = obj.get("epg_id")?.asString ?: obj.get("stream_id")?.asString ?: continue
                val title = obj.get("title")?.asString?.let {
                    // Xtream often base64-encodes titles
                    try { String(android.util.Base64.decode(it, android.util.Base64.DEFAULT)) } catch (_: Exception) { it }
                } ?: continue
                val desc = obj.get("description")?.asString?.let {
                    try { String(android.util.Base64.decode(it, android.util.Base64.DEFAULT)) } catch (_: Exception) { it }
                } ?: ""
                val startStr = obj.get("start")?.asString ?: continue
                val endStr = obj.get("end")?.asString ?: obj.get("stop")?.asString ?: continue

                val start = try { sdf.parse(startStr) } catch (_: Exception) { null } ?: continue
                val end = try { sdf.parse(endStr) } catch (_: Exception) { null } ?: continue

                if (end.time < cutoff) continue

                channels[channelId] = EpgChannel(channelId, "")
                programs.add(EpgProgram(
                    channelId = channelId,
                    title = title,
                    description = desc,
                    startTime = start,
                    endTime = end
                ))
            }
        } catch (e: Exception) {
            Log.e("XtreamClient", "EPG parse error", e)
        }

        return EpgData(channels, programs)
    }

    fun fetchPublic(url: String): String = fetch(url)

    private fun fetch(url: String): String = fetchWithClient(client, url)

    private fun fetchWithClient(httpClient: OkHttpClient, url: String): String {
        val request = Request.Builder().url(url).build()
        val response = httpClient.newCall(request).execute()
        if (!response.isSuccessful) {
            throw Exception("Request failed: ${response.code}")
        }
        return response.body?.string() ?: throw Exception("Empty response")
    }
}
