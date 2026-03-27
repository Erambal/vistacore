package com.vistacore.launcher.data

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.vistacore.launcher.iptv.Channel

/**
 * Manages favorite channels with ordering support.
 * Stores as JSON in SharedPreferences for simplicity.
 */
class FavoritesManager(context: Context) {

    companion object {
        private const val PREFS_NAME = "vistacore_favorites"
        private const val KEY_FAV_CHANNELS = "favorite_channels_list"
        private const val KEY_FAV_APP_ORDER = "favorite_app_order"
    }

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val gson = Gson()

    // --- Favorite Channels ---

    fun getFavoriteChannelIds(): List<String> {
        val json = prefs.getString(KEY_FAV_CHANNELS, null) ?: return emptyList()
        val type = object : TypeToken<List<String>>() {}.type
        return gson.fromJson(json, type)
    }

    fun addFavoriteChannel(channelId: String) {
        val current = getFavoriteChannelIds().toMutableList()
        if (channelId !in current) {
            current.add(channelId)
            saveFavoriteChannels(current)
        }
    }

    fun removeFavoriteChannel(channelId: String) {
        val current = getFavoriteChannelIds().toMutableList()
        current.remove(channelId)
        saveFavoriteChannels(current)
    }

    fun isFavoriteChannel(channelId: String): Boolean {
        return channelId in getFavoriteChannelIds()
    }

    fun toggleFavoriteChannel(channelId: String): Boolean {
        return if (isFavoriteChannel(channelId)) {
            removeFavoriteChannel(channelId)
            false
        } else {
            addFavoriteChannel(channelId)
            true
        }
    }

    fun moveFavoriteChannel(channelId: String, newPosition: Int) {
        val current = getFavoriteChannelIds().toMutableList()
        current.remove(channelId)
        val pos = newPosition.coerceIn(0, current.size)
        current.add(pos, channelId)
        saveFavoriteChannels(current)
    }

    fun filterFavorites(allChannels: List<Channel>): List<Channel> {
        val favIds = getFavoriteChannelIds()
        val channelMap = allChannels.associateBy { it.id }
        return favIds.mapNotNull { channelMap[it] }
    }

    private fun saveFavoriteChannels(ids: List<String>) {
        prefs.edit().putString(KEY_FAV_CHANNELS, gson.toJson(ids)).apply()
    }

    // --- App Order ---

    fun getAppOrder(): List<String> {
        val json = prefs.getString(KEY_FAV_APP_ORDER, null) ?: return emptyList()
        val type = object : TypeToken<List<String>>() {}.type
        return gson.fromJson(json, type)
    }

    fun saveAppOrder(order: List<String>) {
        prefs.edit().putString(KEY_FAV_APP_ORDER, gson.toJson(order)).apply()
    }
}
