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
    val episodes: Map<String, List<XtreamSeriesEpisode>>? = null
)
