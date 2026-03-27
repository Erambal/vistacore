package com.vistacore.launcher.ui

import android.app.Activity
import android.app.SearchManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.speech.RecognizerIntent
import android.widget.Toast
import com.vistacore.launcher.R
import com.vistacore.launcher.iptv.Channel

/**
 * Voice search support for Android TV.
 * Uses the system speech recognizer to search channels and apps.
 */
class VoiceSearchHelper(private val activity: Activity) {

    companion object {
        const val REQUEST_CODE_VOICE = 1001
    }

    /**
     * Launch the system voice recognizer.
     */
    fun startVoiceSearch() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            putExtra(RecognizerIntent.EXTRA_PROMPT, "What would you like to watch?")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
        }

        try {
            activity.startActivityForResult(intent, REQUEST_CODE_VOICE)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(
                activity,
                "Voice search is not available on this device.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    /**
     * Process voice recognition results.
     * Returns the best matching query string, or null.
     */
    fun handleResult(requestCode: Int, resultCode: Int, data: Intent?): String? {
        if (requestCode != REQUEST_CODE_VOICE || resultCode != Activity.RESULT_OK) {
            return null
        }
        val results = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
        return results?.firstOrNull()
    }

    /**
     * Search channels by voice query using fuzzy matching.
     */
    fun searchChannels(query: String, channels: List<Channel>): List<Channel> {
        val queryLower = query.lowercase().trim()
        val queryWords = queryLower.split("\\s+".toRegex())

        // Score each channel by how well it matches
        return channels
            .map { channel -> channel to scoreMatch(queryWords, channel) }
            .filter { it.second > 0 }
            .sortedByDescending { it.second }
            .map { it.first }
    }

    private fun scoreMatch(queryWords: List<String>, channel: Channel): Int {
        val nameLower = channel.name.lowercase()
        val categoryLower = channel.category.lowercase()
        var score = 0

        for (word in queryWords) {
            // Exact name contains
            if (nameLower.contains(word)) {
                score += 10
            }
            // Category match
            if (categoryLower.contains(word)) {
                score += 5
            }
            // Starts with
            if (nameLower.startsWith(word)) {
                score += 15
            }
            // Common TV search aliases
            score += getAliasScore(word, nameLower)
        }

        // Exact match bonus
        if (nameLower == queryWords.joinToString(" ")) {
            score += 50
        }

        return score
    }

    private fun getAliasScore(queryWord: String, channelName: String): Int {
        // Map common spoken names to channel identifiers
        val aliases = mapOf(
            "espn" to listOf("espn", "e.s.p.n"),
            "disney" to listOf("disney", "disney+", "disneyplus"),
            "hbo" to listOf("hbo", "h.b.o"),
            "cnn" to listOf("cnn", "c.n.n"),
            "fox" to listOf("fox"),
            "nbc" to listOf("nbc", "n.b.c"),
            "abc" to listOf("abc", "a.b.c"),
            "cbs" to listOf("cbs", "c.b.s"),
            "sports" to listOf("sport", "sports", "espn", "fs1", "fox sports"),
            "news" to listOf("news", "cnn", "fox news", "msnbc"),
            "movies" to listOf("movie", "movies", "hbo", "showtime", "starz"),
            "kids" to listOf("kids", "children", "cartoon", "nick", "disney")
        )

        for ((_, aliasList) in aliases) {
            if (queryWord in aliasList && aliasList.any { channelName.contains(it) }) {
                return 8
            }
        }
        return 0
    }

    /**
     * Determine if voice query is asking to launch a specific app.
     */
    fun detectAppLaunch(query: String): AppLaunchIntent? {
        val q = query.lowercase().trim()

        return when {
            q.contains("disney") -> AppLaunchIntent.DISNEY
            q.contains("espn") || q.contains("e.s.p.n") -> AppLaunchIntent.ESPN
            q.contains("roku") -> AppLaunchIntent.ROKU
            q.contains("kids") || q.contains("children") || q.contains("cartoon") -> AppLaunchIntent.KIDS
            q.contains("movie") || q.contains("film") -> AppLaunchIntent.MOVIES
            q.contains("tv show") || q.contains("series") || q.contains("shows") -> AppLaunchIntent.TV_SHOWS
            q.contains("live tv") || q.contains("iptv") || q.contains("channels") -> AppLaunchIntent.IPTV
            q.contains("settings") || q.contains("setup") -> AppLaunchIntent.SETTINGS
            else -> null
        }
    }

    enum class AppLaunchIntent {
        DISNEY, ESPN, ROKU, IPTV, MOVIES, TV_SHOWS, KIDS, SETTINGS
    }
}
