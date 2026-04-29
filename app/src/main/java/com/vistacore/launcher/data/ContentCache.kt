package com.vistacore.launcher.data

import android.content.Context
import android.util.Log
import com.vistacore.launcher.iptv.Channel
import com.vistacore.launcher.iptv.EpgData
import com.vistacore.launcher.ui.NetflixRow
import java.io.*

/**
 * In-memory cache for pre-built Netflix rows and EPG data.
 * Populated during splash screen so Movies/Shows/Kids open instantly.
 */
object ContentCache {
    var movieRows: List<NetflixRow>? = null
    var movieItems: List<Channel>? = null

    var showRows: List<NetflixRow>? = null
    var showItems: List<Channel>? = null
    var showEpisodesIndex: Map<String, List<Channel>>? = null

    var kidsRows: List<NetflixRow>? = null
    var kidsItems: List<Channel>? = null
    var kidsShowIndex: Map<String, List<Channel>>? = null

    /** Shared EPG data — parsed once, used by home screen + Live TV */
    var epgData: EpgData? = null
    var epgLoadTime: Long = 0

    fun clear() {
        invalidatePreload()
        epgData = null
        epgLoadTime = 0
    }

    /**
     * Drop preload-derived rows + the isReady flag. Called by the channel
     * update worker after it rewrites the on-disk cache, so the next splash
     * pass rebuilds Movies/Shows/Kids rows from the fresh data instead of
     * reusing stale in-memory rows from the previous build. EPG and the
     * persisted show-name map are intentionally preserved — channel data
     * updating doesn't invalidate either.
     */
    fun invalidatePreload() {
        movieRows = null
        movieItems = null
        showRows = null
        showItems = null
        showEpisodesIndex = null
        kidsRows = null
        kidsItems = null
        kidsShowIndex = null
        isReady = false
    }

    /** Precomputed show name map (channelId → showName) */
    var showNameMap: Map<String, String>? = null

    /**
     * True once Splash has run preloadContent at least once for the current
     * cache. Set by the preloader at the end of its pass; cleared by
     * [clear]. Replaces a getter that required movieRows/showRows/kidsRows
     * to all be non-null — that was over-restrictive: when the user has
     * shows or kids preload disabled (or those caches are empty), the rows
     * legitimately stay null, so the getter never returned true and
     * preloadContent re-ran on every launch.
     */
    var isReady: Boolean = false

    private const val SHOW_NAMES_FILE = "show_names.bin"

    /**
     * Delete the persisted show-name map and drop its in-memory copy.
     * Called by the channel update worker after a cache rewrite — the map
     * is keyed by channel id, so a refreshed catalog whose IDs match the
     * old size but whose underlying titles changed would otherwise group
     * episodes into the wrong shows on the next splash. The next preload
     * pass will recompute the map and re-save it.
     */
    fun deleteShowNameMap(context: Context) {
        try {
            File(context.filesDir, SHOW_NAMES_FILE).delete()
        } catch (e: Exception) {
            Log.e("ContentCache", "Failed to delete show name map", e)
        }
        showNameMap = null
    }

    /** Save show name map to disk as simple key=value lines */
    fun saveShowNameMap(context: Context, map: Map<String, String>) {
        try {
            val file = File(context.filesDir, SHOW_NAMES_FILE)
            BufferedWriter(FileWriter(file), 65536).use { writer ->
                for ((id, name) in map) {
                    writer.write(id)
                    writer.write('\t'.code)
                    writer.write(name)
                    writer.newLine()
                }
            }
            Log.d("ContentCache", "Saved ${map.size} show names to disk")
        } catch (e: Exception) {
            Log.e("ContentCache", "Failed to save show names", e)
        }
    }

    /** Load show name map from disk */
    fun loadShowNameMap(context: Context): Map<String, String>? {
        val file = File(context.filesDir, SHOW_NAMES_FILE)
        if (!file.exists()) return null
        return try {
            val map = HashMap<String, String>()
            BufferedReader(FileReader(file), 65536).use { reader ->
                reader.forEachLine { line ->
                    val tab = line.indexOf('\t')
                    if (tab > 0) {
                        map[line.substring(0, tab)] = line.substring(tab + 1)
                    }
                }
            }
            Log.d("ContentCache", "Loaded ${map.size} show names from disk")
            map
        } catch (e: Exception) {
            Log.e("ContentCache", "Failed to load show names", e)
            null
        }
    }
}
