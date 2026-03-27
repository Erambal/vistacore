package com.vistacore.launcher.data

import android.content.Context
import android.content.SharedPreferences

/**
 * Tracks user behavior to personalize the experience.
 * - Category usage: how often each movie/show category is accessed
 * - Hidden categories: categories the user has dismissed
 * - App usage: how often each home screen app is opened
 *
 * Data is stored in SharedPreferences as simple counters.
 */
class UsageTracker(context: Context) {

    companion object {
        private const val PREFS_NAME = "vistacore_usage"
        private const val KEY_CATEGORY_PREFIX = "cat_"
        private const val KEY_HIDDEN_PREFIX = "hidden_"
        private const val KEY_APP_PREFIX = "app_"
        private const val KEY_LAST_PLAYED_CATEGORY = "last_played_category"
    }

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // --- Category Usage ---

    /** Record that the user played something from this category */
    fun trackCategoryUsage(category: String) {
        val key = KEY_CATEGORY_PREFIX + category.lowercase().trim()
        val count = prefs.getInt(key, 0)
        prefs.edit()
            .putInt(key, count + 1)
            .putString(KEY_LAST_PLAYED_CATEGORY, category)
            .apply()
    }

    /** Get usage count for a category */
    fun getCategoryUsage(category: String): Int {
        return prefs.getInt(KEY_CATEGORY_PREFIX + category.lowercase().trim(), 0)
    }

    /** Sort categories by usage (most used first), with unused categories at the end in original order */
    fun sortCategoriesByUsage(categories: List<String>): List<String> {
        return categories.sortedByDescending { getCategoryUsage(it) }
    }

    /** Get the last played category */
    fun getLastPlayedCategory(): String? {
        return prefs.getString(KEY_LAST_PLAYED_CATEGORY, null)
    }

    // --- Hidden Categories ---

    /** Hide a category from the browse view */
    fun hideCategory(category: String) {
        prefs.edit().putBoolean(KEY_HIDDEN_PREFIX + category.lowercase().trim(), true).apply()
    }

    /** Unhide a category */
    fun unhideCategory(category: String) {
        prefs.edit().remove(KEY_HIDDEN_PREFIX + category.lowercase().trim()).apply()
    }

    /** Check if a category is hidden */
    fun isCategoryHidden(category: String): Boolean {
        return prefs.getBoolean(KEY_HIDDEN_PREFIX + category.lowercase().trim(), false)
    }

    /** Get all hidden category names */
    fun getHiddenCategories(): List<String> {
        return prefs.all.keys
            .filter { it.startsWith(KEY_HIDDEN_PREFIX) && prefs.getBoolean(it, false) }
            .map { it.removePrefix(KEY_HIDDEN_PREFIX) }
    }

    // --- App Usage ---

    /** Record that the user opened an app */
    fun trackAppUsage(appId: String) {
        val key = KEY_APP_PREFIX + appId
        val count = prefs.getInt(key, 0)
        prefs.edit().putInt(key, count + 1).apply()
    }

    /** Get usage count for an app */
    fun getAppUsage(appId: String): Int {
        return prefs.getInt(KEY_APP_PREFIX + appId, 0)
    }
}
