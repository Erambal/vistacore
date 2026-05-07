package com.vistacore.launcher.data

import android.content.Context
import android.util.Log
import com.vistacore.launcher.iptv.Channel
import com.vistacore.launcher.iptv.ContentType
import com.vistacore.launcher.iptv.Discovery
import com.vistacore.launcher.iptv.TmdbClient
import com.vistacore.launcher.iptv.TmdbType
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.FileReader
import java.io.FileWriter

/**
 * Disk-backed cache of TMDB keyword tags per VOD title. Used to score how
 * similar an unwatched movie/show is to what the user has already watched
 * so each category row can lead with "more like what I've watched."
 *
 * Key: streamUrl (stable across sessions, also what WatchHistoryManager keys
 *       on, so a watched item lines up with its keywords without an extra map).
 * Value: KeywordEntry { tmdbId, keywords lowercased }
 *
 * The cache fills progressively — every time the user opens a movie/show
 * detail, the activity fires getKeywords in the background and writes here.
 * On first launch of the keyword-aware build, [backfillFromWatchHistory]
 * enriches the existing watch history so the user sees the benefit
 * immediately rather than only after re-clicking everything.
 */
object KeywordCache {

    data class Entry(val tmdbId: Int, val keywords: List<String>)

    private const val FILE_NAME = "keyword_cache.tsv"
    private const val FIELD_SEP = '\t'
    private const val KW_SEP = '|'

    private val map: HashMap<String, Entry> = HashMap()

    @Volatile
    private var loaded: Boolean = false

    @Synchronized
    fun ensureLoaded(context: Context) {
        if (loaded) return
        val file = File(context.filesDir, FILE_NAME)
        if (file.exists()) {
            try {
                BufferedReader(FileReader(file), 65536).use { reader ->
                    reader.forEachLine { line ->
                        val parts = line.split(FIELD_SEP, limit = 3)
                        if (parts.size == 3) {
                            val streamUrl = parts[0]
                            val tmdbId = parts[1].toIntOrNull() ?: return@forEachLine
                            val keywords = if (parts[2].isEmpty()) emptyList()
                                else parts[2].split(KW_SEP)
                            map[streamUrl] = Entry(tmdbId, keywords)
                        }
                    }
                }
                Log.d(TAG, "Loaded ${map.size} keyword entries from disk")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load keyword cache", e)
            }
        }
        loaded = true
    }

    fun get(streamUrl: String): Entry? = map[streamUrl]

    fun has(streamUrl: String): Boolean = map.containsKey(streamUrl)

    @Synchronized
    fun put(context: Context, streamUrl: String, entry: Entry) {
        ensureLoaded(context)
        map[streamUrl] = entry
        appendToDisk(context, streamUrl, entry)
    }

    /** Iterate cached entries in insertion order. Snapshot is fine — callers
     *  use this to build read-only similarity profiles. */
    fun snapshot(): Map<String, Entry> = HashMap(map)

    private fun appendToDisk(context: Context, streamUrl: String, entry: Entry) {
        // Simple append-only log. New writes for an existing key shadow the
        // earlier line on the next load (last one wins because the loader
        // overwrites map[streamUrl] as it scans).
        try {
            val file = File(context.filesDir, FILE_NAME)
            BufferedWriter(FileWriter(file, true), 4096).use { writer ->
                writer.write(streamUrl)
                writer.write(FIELD_SEP.code)
                writer.write(entry.tmdbId.toString())
                writer.write(FIELD_SEP.code)
                writer.write(entry.keywords.joinToString(KW_SEP.toString()))
                writer.newLine()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write keyword cache entry", e)
        }
    }

    /** Drop everything (e.g. from Settings). */
    @Synchronized
    fun clear(context: Context) {
        try {
            File(context.filesDir, FILE_NAME).delete()
        } catch (_: Exception) {}
        map.clear()
        loaded = true
    }

    /**
     * One-shot enrichment of the cache from existing watch history. Lets a
     * fresh install (or an upgrade from a build that had no keyword cache)
     * benefit from similarity ranking on the very first session instead of
     * waiting for the user to re-open detail screens for each watched item.
     *
     * For each watch entry we resolve a Channel (movie streamUrl matches
     * directly; series episode URLs map to a show via [showNameMap]), call
     * TMDB search → keywords once, and write under the streamUrl that's
     * actually in history. For series we additionally write the same entry
     * under every other episode URL of the same show so the dedup
     * representative the catalog browser hands us also hits the cache.
     *
     * Capped to the watch history's own MAX_ENTRIES (currently 30), so this
     * is at most ~60 TMDB calls and runs once per install — subsequent
     * launches see the populated cache and no-op.
     */
    suspend fun backfillFromHistory(
        context: Context,
        movies: List<Channel>?,
        series: List<Channel>?,
        showNameMap: Map<String, String>?
    ) {
        ensureLoaded(context)
        val history = WatchHistoryManager(context).getRecent(30)
        if (history.isEmpty()) return

        val moviesByUrl = movies?.associateBy { it.streamUrl } ?: emptyMap()
        val seriesByUrl = series?.associateBy { it.streamUrl } ?: emptyMap()
        // Group all known episodes by show name once so we can fan a single
        // TMDB lookup out across every episode URL of the matching show.
        val episodesByShow: Map<String, List<Channel>> =
            if (series != null && showNameMap != null) {
                series.groupBy { showNameMap[it.id] ?: it.name }
            } else emptyMap()

        val tmdb = TmdbClient(context)

        for (entry in history) {
            val url = entry.streamUrl
            if (url.isBlank() || has(url)) continue

            val movie = moviesByUrl[url]
            val episode = seriesByUrl[url]

            try {
                if (movie != null) {
                    val title = stripYear(movie.name)
                    val year = (movie.year.takeIf { it > 0 }
                        ?: Discovery.extractYear(movie.name) ?: 0)
                        .takeIf { it > 0 }?.toString().orEmpty()
                    val tmdbId = tmdb.searchId(title, year, TmdbType.MOVIE) ?: continue
                    val kws = tmdb.getKeywords(tmdbId, TmdbType.MOVIE)
                    put(context, url, Entry(tmdbId, kws))
                } else if (episode != null) {
                    val showName = showNameMap?.get(episode.id) ?: episode.name
                    val year = (episode.year.takeIf { it > 0 } ?: 0)
                        .takeIf { it > 0 }?.toString().orEmpty()
                    val tmdbId = tmdb.searchId(showName, year, TmdbType.TV) ?: continue
                    val kws = tmdb.getKeywords(tmdbId, TmdbType.TV)
                    val newEntry = Entry(tmdbId, kws)
                    // Write across the whole show so the catalog representative
                    // (whichever episode the dedup chose) hits the cache too.
                    val sameShow = episodesByShow[showName].orEmpty()
                    if (sameShow.isEmpty()) {
                        put(context, url, newEntry)
                    } else {
                        for (ep in sameShow) {
                            if (ep.streamUrl.isNotBlank()) put(context, ep.streamUrl, newEntry)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.d(TAG, "Backfill skipped one entry: ${e.message}")
            }
        }
    }

    private val YEAR_SUFFIX = Regex("""\s*\((19|20)\d{2}\)\s*$""")
    private fun stripYear(name: String): String = YEAR_SUFFIX.replace(name, "").trim()

    private const val TAG = "KeywordCache"
}
