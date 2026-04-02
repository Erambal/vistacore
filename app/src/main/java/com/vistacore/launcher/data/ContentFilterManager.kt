package com.vistacore.launcher.data

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File

/**
 * A single segment to mute or skip during playback.
 * @param startMs  start timestamp in milliseconds
 * @param endMs    end timestamp in milliseconds
 * @param action   MUTE (silence audio) or SKIP (seek past segment)
 * @param category content category (profanity, violence, nudity, etc.)
 * @param word     optional — the specific word/phrase detected (for profanity)
 */
data class FilterSegment(
    val startMs: Long,
    val endMs: Long,
    val action: FilterAction,
    val category: FilterCategory,
    val word: String? = null
)

enum class FilterAction { MUTE, SKIP }

enum class FilterCategory {
    PROFANITY,
    BLASPHEMY,
    SLURS,
    SEXUAL_DIALOGUE,
    VIOLENCE,
    NUDITY,
    DRUGS,
    FRIGHTENING;

    val displayName: String
        get() = name.replace('_', ' ').lowercase()
            .replaceFirstChar { it.uppercase() }
}

/**
 * A complete filter file for one piece of content.
 */
data class ContentFilterFile(
    val title: String,
    val year: String = "",
    val source: String = "manual",   // "whisper", "edl", "manual", "community"
    val createdAt: Long = System.currentTimeMillis(),
    val segments: List<FilterSegment>
)

/**
 * Manages content filter files stored as JSON in internal storage.
 * Filter files are keyed by a sanitized version of the content title.
 */
class ContentFilterManager(private val context: Context) {

    companion object {
        private const val FILTERS_DIR = "content_filters"

        val BLASPHEMY_WORDS = setOf(
            "goddamn", "goddammit", "goddam",
            "jesus", "christ",
        )

        val SLUR_WORDS = setOf<String>()  // Intentionally empty — populated from user config
    }

    private val gson = Gson()
    private val filtersDir: File
        get() = File(context.filesDir, FILTERS_DIR).also { it.mkdirs() }

    /** Load filter file for a given title. Returns null if none exists. */
    fun loadFilter(title: String, year: String = ""): ContentFilterFile? {
        val file = filterFile(title, year)
        if (!file.exists()) return null
        return try {
            val json = file.readText()
            gson.fromJson(json, ContentFilterFile::class.java)
        } catch (_: Exception) {
            null
        }
    }

    /** Save a filter file for content. */
    fun saveFilter(filter: ContentFilterFile) {
        val file = filterFile(filter.title, filter.year)
        file.writeText(gson.toJson(filter))
    }

    /** Delete a filter file. */
    fun deleteFilter(title: String, year: String = "") {
        filterFile(title, year).delete()
    }

    /** Check if a filter exists for this content. */
    fun hasFilter(title: String, year: String = ""): Boolean {
        return filterFile(title, year).exists()
    }

    /** List all available filter files. */
    fun listFilters(): List<ContentFilterFile> {
        return filtersDir.listFiles()?.mapNotNull { file ->
            try {
                gson.fromJson(file.readText(), ContentFilterFile::class.java)
            } catch (_: Exception) { null }
        } ?: emptyList()
    }

    /**
     * Import an EDL (Edit Decision List) file used by Kodi/MPlayer.
     * Format: startSeconds endSeconds actionCode
     *   0 = cut (skip), 1 = mute, 2 = scene marker (ignored), 3 = commercial break (skip)
     */
    fun importEdl(title: String, year: String, edlContent: String): ContentFilterFile {
        val segments = mutableListOf<FilterSegment>()
        for (line in edlContent.lines()) {
            val trimmed = line.trim()
            if (trimmed.isBlank() || trimmed.startsWith("#")) continue
            val parts = trimmed.split("\\s+".toRegex())
            if (parts.size < 3) continue
            val start = (parts[0].toDoubleOrNull() ?: continue) * 1000
            val end = (parts[1].toDoubleOrNull() ?: continue) * 1000
            val actionCode = parts[2].toIntOrNull() ?: continue
            val action = when (actionCode) {
                0, 3 -> FilterAction.SKIP
                1 -> FilterAction.MUTE
                else -> continue
            }
            segments.add(FilterSegment(
                startMs = start.toLong(),
                endMs = end.toLong(),
                action = action,
                category = FilterCategory.PROFANITY // EDL doesn't specify category
            ))
        }
        val filter = ContentFilterFile(
            title = title,
            year = year,
            source = "edl",
            segments = segments
        )
        saveFilter(filter)
        return filter
    }

    /**
     * Generate a filter file from a Whisper transcript with word-level timestamps.
     * @param transcript list of {word, start, end} entries from Whisper output
     * @param profanitySet set of lowercase words to flag
     * @param bufferMs padding around each word for cleaner muting (default 200ms)
     */
    fun generateFromTranscript(
        title: String,
        year: String,
        transcript: List<TranscriptWord>,
        profanitySet: Set<String>,
        bufferMs: Long = 200
    ): ContentFilterFile {
        val segments = mutableListOf<FilterSegment>()
        for (entry in transcript) {
            val cleanWord = entry.word.lowercase().replace(Regex("[^a-z']"), "")
            if (cleanWord in profanitySet) {
                segments.add(FilterSegment(
                    startMs = (entry.startMs - bufferMs).coerceAtLeast(0),
                    endMs = entry.endMs + bufferMs,
                    action = FilterAction.MUTE,
                    category = categorizeWord(cleanWord),
                    word = cleanWord
                ))
            }
        }
        // Merge overlapping mute segments
        val merged = mergeSegments(segments)
        val filter = ContentFilterFile(
            title = title,
            year = year,
            source = "whisper",
            segments = merged
        )
        saveFilter(filter)
        return filter
    }

    /** Merge overlapping or adjacent segments of the same action. */
    private fun mergeSegments(segments: List<FilterSegment>): List<FilterSegment> {
        if (segments.isEmpty()) return segments
        val sorted = segments.sortedBy { it.startMs }
        val merged = mutableListOf(sorted[0])
        for (seg in sorted.drop(1)) {
            val last = merged.last()
            if (seg.action == last.action && seg.startMs <= last.endMs + 100) {
                merged[merged.lastIndex] = last.copy(endMs = maxOf(last.endMs, seg.endMs))
            } else {
                merged.add(seg)
            }
        }
        return merged
    }

    private fun categorizeWord(word: String): FilterCategory {
        return when {
            word in BLASPHEMY_WORDS -> FilterCategory.BLASPHEMY
            word in SLUR_WORDS -> FilterCategory.SLURS
            else -> FilterCategory.PROFANITY
        }
    }

    private fun filterFile(title: String, year: String): File {
        val key = sanitizeKey(title) + if (year.isNotBlank()) "_$year" else ""
        return File(filtersDir, "$key.json")
    }

    private fun sanitizeKey(title: String): String {
        return title.lowercase()
            .replace(Regex("[^a-z0-9]+"), "_")
            .trim('_')
            .take(80)
    }

}

/**
 * A single word from a Whisper transcript with timing info.
 */
data class TranscriptWord(
    val word: String,
    val startMs: Long,
    val endMs: Long
)
