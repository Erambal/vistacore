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
        movieRows = null
        movieItems = null
        showRows = null
        showItems = null
        showEpisodesIndex = null
        kidsRows = null
        kidsItems = null
        kidsShowIndex = null
        epgData = null
        epgLoadTime = 0
    }

    /** Precomputed show name map (channelId → showName) */
    var showNameMap: Map<String, String>? = null

    val isReady: Boolean
        get() = movieRows != null && showRows != null && kidsRows != null

    private const val SHOW_NAMES_FILE = "show_names.bin"

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
