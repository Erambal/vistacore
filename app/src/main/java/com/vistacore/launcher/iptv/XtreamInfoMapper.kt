package com.vistacore.launcher.iptv

/**
 * Normalizes Xtream's inconsistent info block shape into [VodDetail] /
 * [SeriesDetail]. Providers scatter the same data across half a dozen
 * differently-named fields (releaseDate vs. releasedate vs. release_date,
 * cover vs. movie_image, tmdb vs. tmdb_id) so mapping is mostly fallback
 * chains.
 */
object XtreamInfoMapper {

    fun toVodDetail(info: XtreamInfoBlock?): VodDetail? {
        if (info == null) return null
        val year = pickYear(info)
        val release = firstNonBlank(info.releaseDate, info.release_date, info.releasedate).orEmpty()
        return VodDetail(
            tmdbId = firstNonBlank(info.tmdb_id, info.tmdb).orEmpty(),
            plot = firstNonBlank(info.plot, info.description).orEmpty(),
            cast = info.cast.orEmpty(),
            director = info.director.orEmpty(),
            genre = info.genre.orEmpty(),
            country = info.country.orEmpty(),
            duration = info.duration.orEmpty(),
            durationSecs = info.duration_secs ?: 0L,
            year = year,
            releaseDate = release,
            rating = info.rating.orEmpty(),
            mpaa = firstNonBlank(info.mpaa_rating, info.age).orEmpty(),
            tagline = info.tagline.orEmpty(),
            trailer = info.youtube_trailer.orEmpty(),
            posterUrl = firstNonBlank(info.movie_image, info.cover_big, info.cover, info.stream_icon).orEmpty(),
            backdropUrl = info.backdrop_path?.firstOrNull().orEmpty()
        )
    }

    fun toSeriesDetail(info: XtreamInfoBlock?, episodes: List<Channel>): SeriesDetail? {
        if (info == null && episodes.isEmpty()) return null
        val safe = info ?: XtreamInfoBlock()
        val year = pickYear(safe)
        val release = firstNonBlank(safe.releaseDate, safe.release_date, safe.releasedate).orEmpty()
        return SeriesDetail(
            tmdbId = firstNonBlank(safe.tmdb_id, safe.tmdb).orEmpty(),
            plot = firstNonBlank(safe.plot, safe.description).orEmpty(),
            cast = safe.cast.orEmpty(),
            director = safe.director.orEmpty(),
            genre = safe.genre.orEmpty(),
            country = safe.country.orEmpty(),
            year = year,
            releaseDate = release,
            rating = safe.rating.orEmpty(),
            mpaa = firstNonBlank(safe.mpaa_rating, safe.age).orEmpty(),
            episodeRunTime = safe.episode_run_time.orEmpty(),
            tagline = safe.tagline.orEmpty(),
            trailer = safe.youtube_trailer.orEmpty(),
            posterUrl = firstNonBlank(safe.cover, safe.cover_big, safe.movie_image, safe.stream_icon).orEmpty(),
            backdropUrl = safe.backdrop_path?.firstOrNull().orEmpty(),
            episodes = episodes
        )
    }

    private fun pickYear(info: XtreamInfoBlock): String {
        val source = firstNonBlank(info.releaseDate, info.release_date, info.releasedate).orEmpty()
        val match = Regex("""(\d{4})""").find(source)
        return match?.value ?: ""
    }

    private fun firstNonBlank(vararg values: String?): String? =
        values.firstOrNull { !it.isNullOrBlank() }
}
