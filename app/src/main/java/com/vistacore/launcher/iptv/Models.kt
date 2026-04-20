package com.vistacore.launcher.iptv

enum class ContentType {
    LIVE,
    MOVIE,
    SERIES
}

enum class ContentSource {
    IPTV,
    JELLYFIN
}

data class Channel(
    val id: String,
    val name: String,
    val streamUrl: String,
    val logoUrl: String = "",
    val category: String = "Uncategorized",
    val number: Int = 0,
    val contentType: ContentType = ContentType.LIVE,
    val epgId: String = "",
    val source: ContentSource = ContentSource.IPTV,
    val year: Int = 0
)

data class Category(
    val name: String,
    val channelCount: Int = 0
)

data class XtreamAuth(
    val server: String,
    val username: String,
    val password: String
) {
    val baseUrl: String
        get() {
            val s = server.trimEnd('/')
            return "$s/player_api.php"
        }

    val liveStreamUrl: String
        get() {
            val s = server.trimEnd('/')
            return "$s/live/$username/$password"
        }

    val movieStreamUrl: String
        get() {
            val s = server.trimEnd('/')
            return "$s/movie/$username/$password"
        }

    val seriesStreamUrl: String
        get() {
            val s = server.trimEnd('/')
            return "$s/series/$username/$password"
        }
}

// Xtream API response models
data class XtreamUserInfo(
    val username: String = "",
    val password: String = "",
    val status: String = "",
    val exp_date: String = "",
    val max_connections: String = ""
)

data class XtreamAuthResponse(
    val user_info: XtreamUserInfo? = null,
    val server_info: Map<String, Any>? = null
)

data class XtreamCategory(
    val category_id: String = "",
    val category_name: String = "",
    val parent_id: Int = 0
)

data class XtreamStream(
    val num: Int = 0,
    val name: String = "",
    val stream_type: String = "",
    val stream_id: Int = 0,
    val stream_icon: String? = null,
    val epg_channel_id: String? = null,
    val category_id: String = "",
    val container_extension: String = "ts"
)

data class XtreamVodStream(
    val num: Int = 0,
    val name: String = "",
    val stream_id: Int = 0,
    val stream_icon: String? = null,
    val category_id: String = "",
    val container_extension: String = "mp4",
    val rating: String? = null,
    val year: String? = null
)

data class XtreamSeries(
    val num: Int = 0,
    val name: String = "",
    val series_id: Int = 0,
    val cover: String? = null,
    val category_id: String = "",
    val container_extension: String = "mp4",
    val rating: String? = null,
    val year: String? = null
)

data class XtreamSeriesEpisode(
    val id: String = "",
    val episode_num: Int = 0,
    val title: String = "",
    val container_extension: String = "mp4",
    val season: Int = 1
)

data class XtreamSeriesInfo(
    val info: XtreamInfoBlock? = null,
    val episodes: Map<String, List<XtreamSeriesEpisode>>? = null
)

data class XtreamVodInfoResponse(
    val info: XtreamInfoBlock? = null,
    val movie_data: Map<String, Any>? = null
)

/**
 * Rich metadata block returned by Xtream's get_vod_info / get_series_info.
 * Fields are optional because providers return inconsistent shapes; we
 * merge them into [VodDetail] / [SeriesDetail] for the UI.
 */
data class XtreamInfoBlock(
    val tmdb_id: String? = null,
    val tmdb: String? = null,
    val name: String? = null,
    val o_name: String? = null,
    val cover: String? = null,
    val cover_big: String? = null,
    val movie_image: String? = null,
    val stream_icon: String? = null,
    val backdrop_path: List<String>? = null,
    val youtube_trailer: String? = null,
    val genre: String? = null,
    val plot: String? = null,
    val description: String? = null,
    val cast: String? = null,
    val director: String? = null,
    val country: String? = null,
    val releaseDate: String? = null,
    val release_date: String? = null,
    val releasedate: String? = null,
    val rating: String? = null,
    val rating_5based: Double? = null,
    val duration: String? = null,
    val duration_secs: Long? = null,
    val episode_run_time: String? = null,
    val age: String? = null,
    val mpaa_rating: String? = null,
    val tagline: String? = null
)

/**
 * Rich VOD (movie) metadata normalized for UI consumption. Built from the
 * Xtream info block by [XtreamInfoMapper]; empty strings mean "unknown".
 */
data class VodDetail(
    val tmdbId: String,
    val plot: String,
    val cast: String,
    val director: String,
    val genre: String,
    val country: String,
    val duration: String,
    val durationSecs: Long,
    val year: String,
    val releaseDate: String,
    val rating: String,
    val mpaa: String,
    val tagline: String,
    val trailer: String,
    val posterUrl: String,
    val backdropUrl: String
)

/**
 * Rich series metadata (plot, cast, rating, etc.) paired with the episode
 * list. Constructed by [XtreamClient.getSeriesDetail].
 */
data class SeriesDetail(
    val tmdbId: String,
    val plot: String,
    val cast: String,
    val director: String,
    val genre: String,
    val country: String,
    val year: String,
    val releaseDate: String,
    val rating: String,
    val mpaa: String,
    val episodeRunTime: String,
    val tagline: String,
    val trailer: String,
    val posterUrl: String,
    val backdropUrl: String,
    val episodes: List<Channel>
)

/** Single actor returned by TMDB credits. */
data class CastMember(
    val name: String,
    val character: String,
    val profileUrl: String
)
