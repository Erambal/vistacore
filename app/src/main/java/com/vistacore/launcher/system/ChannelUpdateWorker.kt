package com.vistacore.launcher.system

import android.content.Context
import android.util.Log
import androidx.work.*
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonWriter
import com.vistacore.launcher.data.PrefsManager
import com.vistacore.launcher.iptv.Channel
import com.vistacore.launcher.iptv.ContentSource
import com.vistacore.launcher.iptv.ContentType
import com.vistacore.launcher.iptv.DispatcharrVodClient
import com.vistacore.launcher.iptv.JellyfinAuth
import com.vistacore.launcher.iptv.JellyfinClient
import com.vistacore.launcher.iptv.M3UParser
import com.vistacore.launcher.iptv.XtreamAuth
import com.vistacore.launcher.iptv.XtreamClient
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.FileReader
import java.io.FileWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

class ChannelUpdateWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "ChannelUpdate"
        private const val WORK_NAME = "channel_auto_update"
        // Unique-work name for one-time refreshes triggered from Splash /
        // MainActivity / Settings. Used so that two bootstrap callers
        // racing to enqueue (e.g. Splash timeout + MainActivity onResume)
        // collapse to a single download instead of stacking duplicates.
        private const val ONE_TIME_WORK_NAME = "channel_one_time_refresh"
        private const val CACHE_PREFS = "vistacore_channel_cache"
        private const val KEY_LAST_UPDATE = "last_update_time"
        private const val CACHE_FILE = "channels_cache.json"
        private const val MOVIES_CACHE_FILE = "movies_cache.json"
        private const val SERIES_CACHE_FILE = "series_cache.json"

        private val gson = Gson()

        fun schedule(context: Context, intervalHours: Long = 6) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = PeriodicWorkRequestBuilder<ChannelUpdateWorker>(
                intervalHours, TimeUnit.HOURS
            )
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }

        /**
         * Enqueue a one-time refresh. Multiple bootstrap callers can hit
         * this in quick succession (Splash 45s timeout, MainActivity
         * onCreate noticing missing cache, Settings post-save) — KEEP
         * collapses them to a single in-flight download instead of
         * scheduling duplicates. Settings' cache-wipe path uses [enqueueOneTime]
         * directly with REPLACE so a pending refresh with stale prefs
         * doesn't preempt the new one.
         */
        fun refreshNow(context: Context) {
            enqueueOneTime(context, ExistingWorkPolicy.KEEP)
        }

        private fun enqueueOneTime(context: Context, policy: ExistingWorkPolicy) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val request = OneTimeWorkRequestBuilder<ChannelUpdateWorker>()
                .setConstraints(constraints)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                ONE_TIME_WORK_NAME,
                policy,
                request
            )
        }

        /**
         * Fetch and merge channels from every configured source — exactly the
         * same path the periodic worker uses, exposed as a suspend helper so
         * Splash's cold-start fetch can produce an identical catalog. Without
         * this shared path, Splash's first-run cache differs from what the
         * worker would have built (no Jellyfin merge, no Dispatcharr fallback).
         */
        suspend fun fetchAllSources(context: Context): List<Channel> {
            val prefs = PrefsManager(context)
            if (!prefs.hasIptvConfig() && !prefs.hasJellyfinConfig()) return emptyList()

            val iptvChannels: List<Channel> = if (prefs.hasIptvConfig()) {
                when (prefs.sourceType) {
                    PrefsManager.SOURCE_M3U -> try { M3UParser().parse(prefs.m3uUrl) } catch (_: Exception) { emptyList() }
                    PrefsManager.SOURCE_XTREAM -> {
                        val auth = XtreamAuth(prefs.xtreamServer, prefs.xtreamUsername, prefs.xtreamPassword)
                        val xc = XtreamClient(auth)
                        val live = try { xc.getChannels() } catch (_: Exception) { emptyList() }
                        val dispatcharrKey = prefs.dispatcharrApiKey
                        val (movies, series) = if (dispatcharrKey.isNotBlank()) {
                            val dc = DispatcharrVodClient(prefs.xtreamServer, dispatcharrKey)
                            val dcMovies = try { dc.getMovies() } catch (e: Exception) {
                                Log.w(TAG, "Dispatcharr movies failed, falling back to Xtream: ${e.message}")
                                try { xc.getMovies() } catch (_: Exception) { emptyList() }
                            }
                            val dcSeries = try { dc.getSeries() } catch (e: Exception) {
                                Log.w(TAG, "Dispatcharr series failed, falling back to Xtream: ${e.message}")
                                try { xc.getSeries() } catch (_: Exception) { emptyList() }
                            }
                            dcMovies to dcSeries
                        } else {
                            val xMovies = try { xc.getMovies() } catch (_: Exception) { emptyList() }
                            val xSeries = try { xc.getSeries() } catch (_: Exception) { emptyList() }
                            xMovies to xSeries
                        }
                        live + movies + series
                    }
                    else -> emptyList()
                }
            } else emptyList()

            val jellyfinChannels: List<Channel> = if (prefs.hasJellyfinConfig()) {
                try {
                    val jf = JellyfinClient(
                        JellyfinAuth(prefs.jellyfinServer, prefs.jellyfinUsername, prefs.jellyfinPassword)
                    )
                    jf.authenticate()
                    val movies = try { jf.getMovies() } catch (e: Exception) {
                        Log.e(TAG, "Jellyfin movies failed: ${e.message}"); emptyList()
                    }
                    val series = try { jf.getSeries() } catch (e: Exception) {
                        Log.e(TAG, "Jellyfin series failed: ${e.message}"); emptyList()
                    }
                    movies + series
                } catch (e: Exception) {
                    Log.e(TAG, "Jellyfin fetch failed: ${e.message}")
                    emptyList()
                }
            } else emptyList()

            return mergeWithJellyfinPreference(iptvChannels, jellyfinChannels)
        }

        /**
         * Merge IPTV and Jellyfin channels. On duplicate match (same normalized
         * title+year for movies, same normalized name for series), the Jellyfin
         * entry wins. Live TV from IPTV is always kept untouched since Jellyfin
         * has no live equivalent.
         */
        private fun mergeWithJellyfinPreference(
            iptv: List<Channel>,
            jellyfin: List<Channel>,
        ): List<Channel> {
            if (jellyfin.isEmpty()) return iptv
            val jellyfinKeys = jellyfin
                .filter { it.contentType == ContentType.MOVIE || it.contentType == ContentType.SERIES }
                .map { dedupKey(it) }
                .toHashSet()
            val filteredIptv = iptv.filter { ch ->
                if (ch.contentType == ContentType.LIVE) return@filter true
                dedupKey(ch) !in jellyfinKeys
            }
            return jellyfin + filteredIptv
        }

        private fun dedupKey(ch: Channel): String {
            val normalized = normalizeTitle(ch.name)
            return if (ch.contentType == ContentType.SERIES) {
                "series|$normalized"
            } else {
                val year = if (ch.year > 0) ch.year.toString() else extractYear(ch.name)?.toString() ?: ""
                "movie|$normalized|$year"
            }
        }

        private fun normalizeTitle(raw: String): String {
            var s = raw.lowercase()
            s = s.replace(Regex("\\(\\d{4}\\)"), " ")
            s = s.replace(Regex("\\b(19|20)\\d{2}\\b"), " ")
            s = s.replace(Regex("\\b(4k|uhd|hdr|hdr10|dolby|dv|1080p|720p|480p|2160p|x265|x264|hevc|web[- ]?dl|bluray|bdrip|remux)\\b"), " ")
            s = s.replace(Regex("^the\\s+"), "")
            s = s.replace(Regex("[^a-z0-9]+"), " ").trim()
            s = s.replace(Regex("\\s+"), " ")
            return s
        }

        private fun extractYear(name: String): Int? {
            val m = Regex("(19|20)\\d{2}").find(name) ?: return null
            return m.value.toIntOrNull()
        }

        /** Wipe all IPTV content caches and schedule an immediate re-fetch. */
        fun clearCachesAndRefresh(context: Context) {
            val names = listOf(
                CACHE_FILE, "$CACHE_FILE.gz",
                MOVIES_CACHE_FILE, "$MOVIES_CACHE_FILE.gz",
                SERIES_CACHE_FILE, "$SERIES_CACHE_FILE.gz",
                "show_names.bin"
            )
            names.forEach { File(context.filesDir, it).delete() }
            // Drop the success sentinel too — the warm-path gate uses
            // KEY_LAST_UPDATE to decide whether bootstrap is needed, so
            // wiping caches without clearing this would leave the next
            // launch thinking the cache was fine.
            context.getSharedPreferences(CACHE_PREFS, Context.MODE_PRIVATE)
                .edit().remove(KEY_LAST_UPDATE).apply()
            com.vistacore.launcher.data.ContentCache.clear()
            // REPLACE rather than KEEP — if a refresh is already pending
            // with the previous provider's prefs, we want to cancel it so
            // the next run uses the new config. Otherwise the user can
            // change provider, hit Save, and watch the old provider's
            // refresh land in the just-wiped caches.
            enqueueOneTime(context, ExistingWorkPolicy.REPLACE)
        }

        fun getLastUpdateTime(context: Context): Long {
            return context.getSharedPreferences(CACHE_PREFS, Context.MODE_PRIVATE)
                .getLong(KEY_LAST_UPDATE, 0)
        }

        /** Load channels from file cache using streaming reader. Returns null if no cache exists. */
        fun getCachedChannels(context: Context): List<Channel>? = readCacheFile(context, CACHE_FILE)

        /** Load only movies from cache. */
        fun getCachedMovies(context: Context): List<Channel>? = readCacheFile(context, MOVIES_CACHE_FILE)

        /** Load only series from cache. */
        fun getCachedSeries(context: Context): List<Channel>? = readCacheFile(context, SERIES_CACHE_FILE)

        private fun readCacheFile(context: Context, fileName: String): List<Channel>? {
            // Try gzipped file first, fall back to plain JSON
            val gzFile = File(context.filesDir, "$fileName.gz")
            val plainFile = File(context.filesDir, fileName)
            val file = when {
                gzFile.exists() && gzFile.length() > 0 -> gzFile
                plainFile.exists() && plainFile.length() > 0 -> plainFile
                else -> return null
            }
            val isGzip = file.name.endsWith(".gz")

            return try {
                val channels = mutableListOf<Channel>()
                val inputStream = if (isGzip) {
                    InputStreamReader(GZIPInputStream(FileInputStream(file), 65536))
                } else {
                    java.io.BufferedReader(FileReader(file), 65536)
                }
                JsonReader(inputStream).use { reader ->
                    reader.isLenient = true
                    reader.beginArray()
                    while (reader.hasNext()) {
                        reader.beginObject()
                        var id = ""; var name = ""; var streamUrl = ""; var logoUrl = ""
                        var category = "Uncategorized"; var number = 0
                        var contentType = com.vistacore.launcher.iptv.ContentType.LIVE
                        var epgId = ""
                        var source = ContentSource.IPTV
                        var year = 0
                        while (reader.hasNext()) {
                            val key = reader.nextName()
                            // Handle null JSON values safely
                            if (reader.peek() == com.google.gson.stream.JsonToken.NULL) {
                                reader.nextNull()
                                continue
                            }
                            when (key) {
                                "id" -> id = reader.nextString()
                                "name" -> name = reader.nextString()
                                "streamUrl" -> streamUrl = reader.nextString()
                                "logoUrl" -> logoUrl = reader.nextString()
                                "category" -> category = reader.nextString()
                                "number" -> number = reader.nextInt()
                                "contentType" -> {
                                    val ct = reader.nextString()
                                    contentType = try { com.vistacore.launcher.iptv.ContentType.valueOf(ct) } catch (_: Exception) { com.vistacore.launcher.iptv.ContentType.LIVE }
                                }
                                "epgId" -> epgId = reader.nextString()
                                "source" -> {
                                    val s = reader.nextString()
                                    source = try { ContentSource.valueOf(s) } catch (_: Exception) { ContentSource.IPTV }
                                }
                                "year" -> year = reader.nextInt()
                                else -> reader.skipValue()
                            }
                        }
                        reader.endObject()
                        channels.add(Channel(id, name, streamUrl, logoUrl, category, number, contentType, epgId, source, year))
                    }
                    reader.endArray()
                }
                Log.d(TAG, "Read ${channels.size} channels from $fileName")
                channels
            } catch (e: Exception) {
                Log.e(TAG, "Failed to read cache $fileName", e)
                file.delete()
                null
            }
        }

        private fun writeCacheFile(context: Context, fileName: String, channels: List<Channel>) {
            val gzFileName = "$fileName.gz"
            val tempFile = File(context.filesDir, "$gzFileName.tmp")
            val file = File(context.filesDir, gzFileName)
            // Delete old uncompressed file if exists
            File(context.filesDir, fileName).delete()
            JsonWriter(OutputStreamWriter(GZIPOutputStream(FileOutputStream(tempFile), 65536))).use { writer ->
                writer.beginArray()
                for (ch in channels) {
                    writer.beginObject()
                    writer.name("id").value(ch.id)
                    writer.name("name").value(ch.name)
                    writer.name("streamUrl").value(ch.streamUrl)
                    writer.name("logoUrl").value(ch.logoUrl ?: "")
                    writer.name("category").value(ch.category ?: "Uncategorized")
                    writer.name("number").value(ch.number)
                    writer.name("contentType").value(ch.contentType.name)
                    writer.name("epgId").value(ch.epgId ?: "")
                    writer.name("source").value(ch.source.name)
                    writer.name("year").value(ch.year.toLong())
                    writer.endObject()
                }
                writer.endArray()
            }
            tempFile.renameTo(file)
        }

        /**
         * Save channels to file cache — also splits into movies/series caches.
         * Empty fetches are refused: a transient provider outage can return
         * `[]` for every source, and writing that out would satisfy the
         * warm-path gate forever (cache files exist) while leaving the user
         * with a blank catalog. Returning early without touching disk leaves
         * the previous cache intact — or, on first run, leaves the system
         * in a "no cache yet" state that the bootstrap path retries.
         */
        fun cacheChannels(context: Context, channels: List<Channel>) {
            if (channels.isEmpty()) {
                Log.w(TAG, "Refusing to cache empty channel list — likely a transient outage")
                return
            }
            try {
                // Write main cache (live channels only — keeps file small)
                val liveChannels = channels.filter { it.contentType == com.vistacore.launcher.iptv.ContentType.LIVE }
                writeCacheFile(context, CACHE_FILE, liveChannels)

                // Write separate movie and series caches
                val movies = channels.filter { it.contentType == com.vistacore.launcher.iptv.ContentType.MOVIE }
                writeCacheFile(context, MOVIES_CACHE_FILE, movies)

                val series = channels.filter { it.contentType == com.vistacore.launcher.iptv.ContentType.SERIES }
                writeCacheFile(context, SERIES_CACHE_FILE, series)

                context.getSharedPreferences(CACHE_PREFS, Context.MODE_PRIVATE)
                    .edit()
                    .putLong(KEY_LAST_UPDATE, System.currentTimeMillis())
                    .apply()
                // Drop preload-derived rows so the next splash rebuilds them
                // from the freshly-written disk cache. Without this, a
                // background worker tick that updates the on-disk catalog
                // is silently masked by stale Movies/Shows/Kids rows held
                // in ContentCache from the previous splash pass. EPG is
                // left intact — channel data updating doesn't invalidate
                // the EPG.
                com.vistacore.launcher.data.ContentCache.invalidatePreload()
                // Also invalidate the persisted show-name map. It's keyed
                // by channel id, so a same-sized refresh whose underlying
                // titles changed (provider remapped IDs, episode renames,
                // etc.) would otherwise produce wrong show grouping in
                // the next splash. Cheap to recompute from the new series
                // cache on next launch.
                com.vistacore.launcher.data.ContentCache.deleteShowNameMap(context)
                Log.d(TAG, "Cached ${liveChannels.size} live, ${movies.size} movies, ${series.size} series")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to write channel cache", e)
            }
        }
    }

    override suspend fun doWork(): Result {
        val prefs = PrefsManager(applicationContext)
        // Jellyfin is hybrid-only: it integrates alongside an IPTV provider
        // and is never the sole catalog source. Skip the worker entirely
        // when IPTV isn't configured (even if Jellyfin is) so we don't
        // produce a Jellyfin-only cache that the rest of the app isn't
        // designed to render.
        if (!prefs.hasIptvConfig()) {
            return Result.success()
        }
        return try {
            val merged = fetchAllSources(applicationContext)
            cacheChannels(applicationContext, merged)
            Log.d(TAG, "Channel update complete: ${merged.size} channels cached")
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Channel update failed: ${e.message}")
            Result.retry()
        }
    }
}
