package com.vistacore.launcher.data

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * Tracks playback position for movies and shows so users can resume where they left off.
 * Stores: stream URL → WatchEntry (name, logo, position, duration, timestamp)
 */
data class WatchEntry(
    val streamUrl: String,
    val name: String,
    val logoUrl: String,
    val positionMs: Long,
    val durationMs: Long,
    val timestamp: Long = System.currentTimeMillis()
) {
    val progressPercent: Int
        get() = if (durationMs > 0) ((positionMs * 100) / durationMs).toInt().coerceIn(0, 100) else 0

    val isFinished: Boolean
        get() = progressPercent >= 95

    val displayPosition: String
        get() {
            val mins = (positionMs / 60000).toInt()
            val hours = mins / 60
            val remainMins = mins % 60
            return if (hours > 0) "${hours}h ${remainMins}m" else "${remainMins}m"
        }

    val displayRemaining: String
        get() {
            val remaining = ((durationMs - positionMs) / 60000).toInt().coerceAtLeast(0)
            val hours = remaining / 60
            val mins = remaining % 60
            return if (hours > 0) "${hours}h ${mins}m left" else "${mins}m left"
        }
}

class WatchHistoryManager(context: Context) {

    companion object {
        private const val PREFS_NAME = "vistacore_watch_history"
        private const val KEY_HISTORY = "watch_history"
        private const val MAX_ENTRIES = 30
    }

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val gson = Gson()

    /** Get all continue-watching entries, sorted by most recent. Excludes finished items. */
    fun getContinueWatching(): List<WatchEntry> {
        return getAllEntries()
            .filter { !it.isFinished && it.positionMs > 30000 } // At least 30 seconds watched
            .sortedByDescending { it.timestamp }
    }

    /** Save/update playback position for a stream. */
    fun savePosition(streamUrl: String, name: String, logoUrl: String, positionMs: Long, durationMs: Long) {
        val entries = getAllEntries().toMutableList()

        // Remove existing entry for this URL
        entries.removeAll { it.streamUrl == streamUrl }

        // Add new entry at the top
        entries.add(0, WatchEntry(streamUrl, name, logoUrl, positionMs, durationMs))

        // Trim to max
        while (entries.size > MAX_ENTRIES) entries.removeAt(entries.size - 1)

        saveAll(entries)
    }

    /** Get saved position for a specific stream URL. Returns 0 if not found. */
    fun getPosition(streamUrl: String): Long {
        return getAllEntries().find { it.streamUrl == streamUrl }?.positionMs ?: 0
    }

    /** Remove an entry. */
    fun remove(streamUrl: String) {
        val entries = getAllEntries().toMutableList()
        entries.removeAll { it.streamUrl == streamUrl }
        saveAll(entries)
    }

    /** Remove all entries. */
    fun clearAll() {
        prefs.edit().remove(KEY_HISTORY).apply()
    }

    /**
     * Most-recently-watched entries, finished or not, capped at [limit].
     * Used by the Top Picks hero to rank the user's preferred categories.
     */
    fun getRecent(limit: Int = MAX_ENTRIES): List<WatchEntry> =
        getAllEntries()
            .sortedByDescending { it.timestamp }
            .take(limit)

    private fun getAllEntries(): List<WatchEntry> {
        val json = prefs.getString(KEY_HISTORY, null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<WatchEntry>>() {}.type
            gson.fromJson(json, type) ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun saveAll(entries: List<WatchEntry>) {
        prefs.edit().putString(KEY_HISTORY, gson.toJson(entries)).apply()
    }
}
