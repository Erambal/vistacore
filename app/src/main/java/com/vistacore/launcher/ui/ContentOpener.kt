package com.vistacore.launcher.ui

import android.app.Activity
import android.content.Intent
import com.vistacore.launcher.data.ContentCache
import com.vistacore.launcher.data.PrefsManager
import com.vistacore.launcher.iptv.Channel
import com.vistacore.launcher.iptv.XtreamAuth
import com.vistacore.launcher.iptv.XtreamClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Shared "open this item" routing used by the new home layouts so they don't
 * each reimplement the movie / show / live-channel launch logic. Mirrors the
 * proven paths in [VODBrowserActivity] (movie detail vs direct play, Xtream /
 * Jellyfin / M3U series episode fetch) and [MainActivity] (live channel).
 *
 * Kept as a standalone helper rather than refactoring VODBrowserActivity so
 * the battle-tested browser path is left untouched.
 */
object ContentOpener {

    /** Resolve a display/show name for a series item, preferring the
     *  precomputed map Splash builds, falling back to a regex strip. */
    fun showNameFor(item: Channel): String =
        ContentCache.showNameMap?.get(item.id) ?: stripShowName(item.name)

    private val showNameStripper = Regex(
        """[\s.,-]*(?:[Ss]\d{1,2}[\s.,-]*[Ee]\d{1,3}|\d{1,2}[xX]\d{1,3}|[Ss]eason\s*\d+|\bEp\.?\s*\d+|\bEpisode\s*\d+).*""",
        RegexOption.IGNORE_CASE
    )
    private val showNameCleanup = Regex(
        """[\s-]+\d{1,3}\s*$|\s*[\(\[]\d{4}[\)\]]|\s*\b(?:HD|FHD|SD|4K|UHD)\b.*$""",
        RegexOption.IGNORE_CASE
    )

    private fun stripShowName(name: String): String {
        var cleaned = showNameStripper.replace(name, "")
        cleaned = showNameCleanup.replace(cleaned, "")
        return cleaned.trim().ifBlank { name.trim() }
    }

    /**
     * Open a live channel straight in the fullscreen player. Back returns to
     * whatever home launched it (no LiveTV guide stacked underneath — the home
     * IS the channel chooser here).
     */
    fun openLiveChannel(activity: Activity, channel: Channel) {
        PrefsManager(activity).lastChannel = channel.id
        activity.startActivity(Intent(activity, IPTVPlayerActivity::class.java).apply {
            putExtra(IPTVPlayerActivity.EXTRA_STREAM_URL, channel.streamUrl)
            putExtra(IPTVPlayerActivity.EXTRA_CHANNEL_NAME, channel.name)
            putExtra(IPTVPlayerActivity.EXTRA_CHANNEL_LOGO, channel.logoUrl)
            putExtra(IPTVPlayerActivity.EXTRA_CHANNEL_ID, channel.id)
        })
    }

    /** Resume a Continue Watching entry straight in the player at its saved
     *  position. The entry's streamUrl is the already-resolved playable URL. */
    fun resumeWatch(activity: Activity, entry: com.vistacore.launcher.data.WatchEntry) {
        activity.startActivity(Intent(activity, IPTVPlayerActivity::class.java).apply {
            putExtra(IPTVPlayerActivity.EXTRA_STREAM_URL, entry.streamUrl)
            putExtra(IPTVPlayerActivity.EXTRA_CHANNEL_NAME, entry.name)
            putExtra(IPTVPlayerActivity.EXTRA_CHANNEL_LOGO, entry.logoUrl)
            putExtra(IPTVPlayerActivity.EXTRA_IS_VOD, true)
            if (entry.positionMs > 0) putExtra(IPTVPlayerActivity.EXTRA_RESUME_POSITION, entry.positionMs)
        })
    }

    /** Open a movie: rich detail screen for Xtream VOD, direct play otherwise. */
    fun openMovie(activity: Activity, item: Channel) {
        val year = Regex("""\((\d{4})\)""").find(item.name)?.groupValues?.get(1) ?: ""
        val vodId = if (item.id.startsWith("xt_vod_")) {
            item.id.removePrefix("xt_vod_").toIntOrNull() ?: 0
        } else 0

        if (vodId > 0) {
            MovieDetailActivity.launch(
                activity = activity,
                title = item.name,
                category = item.category,
                posterUrl = item.logoUrl,
                streamUrl = item.streamUrl,
                vodId = vodId,
                year = year
            )
        } else {
            activity.startActivity(Intent(activity, IPTVPlayerActivity::class.java).apply {
                putExtra(IPTVPlayerActivity.EXTRA_STREAM_URL, item.streamUrl)
                putExtra(IPTVPlayerActivity.EXTRA_CHANNEL_NAME, item.name)
                putExtra(IPTVPlayerActivity.EXTRA_CHANNEL_LOGO, item.logoUrl)
                putExtra(IPTVPlayerActivity.EXTRA_IS_VOD, true)
                if (year.isNotBlank()) putExtra(IPTVPlayerActivity.EXTRA_CONTENT_YEAR, year)
            })
        }
    }

    /**
     * Open a series: fetch episodes (Xtream / Jellyfin via API, M3U from the
     * preloaded index) then hand off to [ShowDetailActivity]. [onLoading] lets
     * the caller show/hide a spinner around the network fetch; [onError] fires
     * when no episodes could be resolved.
     */
    fun openShow(
        activity: Activity,
        scope: CoroutineScope,
        item: Channel,
        onLoading: (Boolean) -> Unit = {},
        onError: () -> Unit = {},
    ) {
        val showName = showNameFor(item)
        val xtreamSeriesId =
            if (item.id.startsWith("xt_series_")) item.id.removePrefix("xt_series_").toIntOrNull() else null
        val jellyfinSeriesId =
            if (item.id.startsWith("jf_series_")) item.id.removePrefix("jf_series_") else null

        when {
            jellyfinSeriesId != null -> {
                onLoading(true)
                scope.launch {
                    val episodes = withContext(Dispatchers.IO) {
                        try {
                            val prefs = PrefsManager(activity)
                            val jf = com.vistacore.launcher.iptv.JellyfinClient(
                                com.vistacore.launcher.iptv.JellyfinAuth(
                                    prefs.jellyfinServer, prefs.jellyfinUsername, prefs.jellyfinPassword
                                )
                            )
                            jf.authenticate()
                            jf.getEpisodes(jellyfinSeriesId)
                        } catch (_: Exception) { emptyList() }
                    }
                    onLoading(false)
                    if (episodes.isNotEmpty()) {
                        ShowDetailActivity.launch(activity, showName, item.category, item.logoUrl, episodes)
                    } else onError()
                }
            }
            xtreamSeriesId != null -> {
                onLoading(true)
                scope.launch {
                    val episodes = withContext(Dispatchers.IO) {
                        try {
                            val prefs = PrefsManager(activity)
                            val auth = XtreamAuth(prefs.xtreamServer, prefs.xtreamUsername, prefs.xtreamPassword)
                            XtreamClient(auth).getSeriesInfo(xtreamSeriesId)
                        } catch (_: Exception) { emptyList() }
                    }
                    onLoading(false)
                    if (episodes.isNotEmpty()) {
                        ShowDetailActivity.launch(
                            activity, showName, item.category, item.logoUrl, episodes,
                            xtreamSeriesId = xtreamSeriesId
                        )
                    } else onError()
                }
            }
            else -> {
                // M3U series: episodes already grouped in the preloaded index.
                val episodes = ContentCache.showEpisodesIndex?.get(showName) ?: emptyList()
                if (episodes.isNotEmpty()) {
                    ShowDetailActivity.launch(activity, showName, item.category, item.logoUrl, episodes)
                } else onError()
            }
        }
    }
}
