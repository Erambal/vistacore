package com.vistacore.launcher.iptv

import com.google.gson.JsonElement
import com.google.gson.JsonObject

/**
 * Xtream providers vary wildly in the shape of get_vod_info /
 * get_series_info responses. Fields that spec out as arrays can come back
 * as strings, numbers sometimes arrive quoted, and the whole info block
 * occasionally comes back as an empty array instead of an object when the
 * provider has no metadata.
 *
 * Reflection-based Gson on a strict class crashes the whole response on
 * any one of these. This parser extracts each field defensively so a single
 * malformed value only nulls that field, not the entire detail.
 */
object XtreamSafeParser {

    /** Parse the "info" block into [XtreamInfoBlock]. Null if the element
     *  isn't an object (e.g. provider returned an empty array). */
    fun parseInfoBlock(elem: JsonElement?): XtreamInfoBlock? {
        if (elem == null || elem.isJsonNull || !elem.isJsonObject) return null
        val o = elem.asJsonObject
        return XtreamInfoBlock(
            tmdb_id = stringOrEmpty(o.get("tmdb_id")).ifBlank { null },
            tmdb = stringOrEmpty(o.get("tmdb")).ifBlank { null },
            name = stringOrEmpty(o.get("name")).ifBlank { null },
            o_name = stringOrEmpty(o.get("o_name")).ifBlank { null },
            cover = stringOrEmpty(o.get("cover")).ifBlank { null },
            cover_big = stringOrEmpty(o.get("cover_big")).ifBlank { null },
            movie_image = stringOrEmpty(o.get("movie_image")).ifBlank { null },
            stream_icon = stringOrEmpty(o.get("stream_icon")).ifBlank { null },
            backdrop_path = stringList(o.get("backdrop_path")),
            youtube_trailer = stringOrEmpty(o.get("youtube_trailer")).ifBlank { null },
            genre = stringOrEmpty(o.get("genre")).ifBlank { null },
            plot = stringOrEmpty(o.get("plot")).ifBlank { null },
            description = stringOrEmpty(o.get("description")).ifBlank { null },
            cast = stringOrEmpty(o.get("cast")).ifBlank { null },
            director = stringOrEmpty(o.get("director")).ifBlank { null },
            country = stringOrEmpty(o.get("country")).ifBlank { null },
            releaseDate = stringOrEmpty(o.get("releaseDate")).ifBlank { null },
            release_date = stringOrEmpty(o.get("release_date")).ifBlank { null },
            releasedate = stringOrEmpty(o.get("releasedate")).ifBlank { null },
            rating = stringOrEmpty(o.get("rating")).ifBlank { null },
            rating_5based = doubleOrNull(o.get("rating_5based")),
            duration = stringOrEmpty(o.get("duration")).ifBlank { null },
            duration_secs = longOrNull(o.get("duration_secs")),
            episode_run_time = stringOrEmpty(o.get("episode_run_time")).ifBlank { null },
            age = stringOrEmpty(o.get("age")).ifBlank { null },
            mpaa_rating = stringOrEmpty(o.get("mpaa_rating")).ifBlank { null },
            tagline = stringOrEmpty(o.get("tagline")).ifBlank { null }
        )
    }

    fun stringOrEmpty(elem: JsonElement?): String {
        if (elem == null || elem.isJsonNull) return ""
        return try {
            if (elem.isJsonPrimitive) elem.asString else ""
        } catch (_: Exception) { "" }
    }

    fun longOrNull(elem: JsonElement?): Long? {
        if (elem == null || elem.isJsonNull) return null
        return try {
            if (elem.isJsonPrimitive) {
                val p = elem.asJsonPrimitive
                if (p.isNumber) p.asLong
                else p.asString.trim().toLongOrNull()
            } else null
        } catch (_: Exception) { null }
    }

    fun doubleOrNull(elem: JsonElement?): Double? {
        if (elem == null || elem.isJsonNull) return null
        return try {
            if (elem.isJsonPrimitive) {
                val p = elem.asJsonPrimitive
                if (p.isNumber) p.asDouble
                else p.asString.trim().toDoubleOrNull()
            } else null
        } catch (_: Exception) { null }
    }

    /** Accepts either a string ("foo.jpg"), an array (["a.jpg","b.jpg"]),
     *  or null. Always returns a non-null list (empty when absent). */
    fun stringList(elem: JsonElement?): List<String>? {
        if (elem == null || elem.isJsonNull) return null
        return try {
            when {
                elem.isJsonArray -> elem.asJsonArray
                    .filterNot { it.isJsonNull }
                    .mapNotNull {
                        if (it.isJsonPrimitive) it.asString else null
                    }
                    .filter { it.isNotBlank() }
                elem.isJsonPrimitive -> {
                    val s = elem.asString
                    if (s.isBlank()) emptyList() else listOf(s)
                }
                else -> emptyList()
            }
        } catch (_: Exception) { null }
    }
}
