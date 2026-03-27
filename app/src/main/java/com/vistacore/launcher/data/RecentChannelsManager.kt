package com.vistacore.launcher.data

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.vistacore.launcher.iptv.Channel

/**
 * Tracks the last 10 channels watched for quick resume.
 * Most recent first, no duplicates.
 */
class RecentChannelsManager(context: Context) {

    companion object {
        private const val PREFS_NAME = "vistacore_recents"
        private const val KEY_RECENT_IDS = "recent_channel_ids"
        private const val MAX_RECENT = 10
    }

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val gson = Gson()

    fun getRecentIds(): List<String> {
        val json = prefs.getString(KEY_RECENT_IDS, null) ?: return emptyList()
        val type = object : TypeToken<List<String>>() {}.type
        return gson.fromJson(json, type)
    }

    /**
     * Add a channel to the top of the recent list.
     * Removes duplicates and caps at MAX_RECENT.
     */
    fun addRecent(channelId: String) {
        val current = getRecentIds().toMutableList()
        current.remove(channelId)
        current.add(0, channelId)

        // Trim to max
        val trimmed = current.take(MAX_RECENT)
        prefs.edit().putString(KEY_RECENT_IDS, gson.toJson(trimmed)).apply()
    }

    /**
     * Get recent channels as full Channel objects, preserving order.
     */
    fun getRecentChannels(allChannels: List<Channel>): List<Channel> {
        val recentIds = getRecentIds()
        val channelMap = allChannels.associateBy { it.id }
        return recentIds.mapNotNull { channelMap[it] }
    }

    fun clearRecents() {
        prefs.edit().remove(KEY_RECENT_IDS).apply()
    }

    fun hasRecents(): Boolean = getRecentIds().isNotEmpty()
}
