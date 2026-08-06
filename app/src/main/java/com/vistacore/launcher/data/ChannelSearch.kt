package com.vistacore.launcher.data

import com.vistacore.launcher.iptv.Channel
import com.vistacore.launcher.iptv.EpgData

/**
 * The one place channel searching is defined.
 *
 * There were four independent implementations of "find a channel", each broken in a
 * different way, and between them they made a query like "mariners" impossible to satisfy:
 *
 *  - Live TV's filter searched only the *current category*, and the default landing
 *    category is Recent (10 channels), so a channel the user owns returned "no matches".
 *  - It also treated any all-digit query as a channel-number prefix and *excluded* names
 *    entirely, so "60" could never find "60 Minutes".
 *  - Its EPG clause consulted only `getNowPlaying`, so a programme could be found only
 *    while already airing — "the next Mariners game" was unfindable by construction.
 *  - Global search matched `name` only: not category, not the guide.
 *
 * This matcher is additive rather than exclusive: every signal that can match, does, and
 * results come back ordered by how direct the match was.
 */
object ChannelSearch {

    /** How far ahead to look in the guide for an upcoming programme match. */
    const val UPCOMING_WINDOW_HOURS = 12

    /** Why a channel matched — drives result ordering and can be shown to the user. */
    enum class MatchKind {
        CHANNEL_NUMBER,
        CHANNEL_NAME,
        NOW_PLAYING,
        UPCOMING,
        CATEGORY,
    }

    data class Result(
        val channel: Channel,
        val kind: MatchKind,
        /** Programme title when the match came from the guide, else null. */
        val programTitle: String? = null,
    )

    private fun epgKeys(channel: Channel): List<String> =
        listOf(channel.epgId.ifBlank { channel.id }, channel.name).distinct()

    private fun nowPlaying(channel: Channel, epg: EpgData?): String? {
        val e = epg ?: return null
        return epgKeys(channel).firstNotNullOfOrNull { e.getNowPlaying(it) }?.title
    }

    private fun upcomingTitles(channel: Channel, epg: EpgData?): List<String> {
        val e = epg ?: return emptyList()
        return epgKeys(channel)
            .flatMap { e.getUpcoming(it, UPCOMING_WINDOW_HOURS) }
            .map { it.title }
    }

    /**
     * Rank a single channel against [query]. Returns null when nothing matched.
     *
     * Order of preference is deliberate: an exact-ish channel identity beats a programme
     * mention, which beats a category. Someone typing "espn" wants the ESPN channel, not
     * every channel whose guide happens to mention ESPN.
     */
    fun match(channel: Channel, query: String, epg: EpgData?): Result? {
        val q = query.trim()
        if (q.isBlank()) return null

        // Numeric queries stay useful as channel-number prefixes, but no longer suppress
        // the text clauses below — that exclusivity is what hid "60 Minutes" and "ESPN2".
        if (q.toIntOrNull() != null && channel.number.toString().startsWith(q)) {
            return Result(channel, MatchKind.CHANNEL_NUMBER)
        }
        if (channel.name.contains(q, ignoreCase = true)) {
            return Result(channel, MatchKind.CHANNEL_NAME)
        }
        nowPlaying(channel, epg)?.let { title ->
            if (title.contains(q, ignoreCase = true)) {
                return Result(channel, MatchKind.NOW_PLAYING, title)
            }
        }
        upcomingTitles(channel, epg).firstOrNull { it.contains(q, ignoreCase = true) }?.let { title ->
            return Result(channel, MatchKind.UPCOMING, title)
        }
        if (channel.category.contains(q, ignoreCase = true)) {
            return Result(channel, MatchKind.CATEGORY)
        }
        return null
    }

    /** Search [channels], best matches first. */
    fun search(channels: List<Channel>, query: String, epg: EpgData?): List<Result> {
        if (query.isBlank()) return emptyList()
        return channels
            .mapNotNull { match(it, query, epg) }
            .sortedWith(compareBy({ it.kind.ordinal }, { it.channel.number }))
    }

    /** Convenience for callers that only need the channels. */
    fun searchChannels(channels: List<Channel>, query: String, epg: EpgData?): List<Channel> =
        search(channels, query, epg).map { it.channel }

    /**
     * A short, plain-language note about why a result matched, for surfacing under the
     * channel name. Null when the match needs no explanation (the name itself matched).
     */
    fun explain(result: Result): String? = when (result.kind) {
        MatchKind.NOW_PLAYING -> result.programTitle?.let { "On now: $it" }
        MatchKind.UPCOMING -> result.programTitle?.let { "Coming up: $it" }
        MatchKind.CATEGORY -> "In ${result.channel.category}"
        MatchKind.CHANNEL_NUMBER, MatchKind.CHANNEL_NAME -> null
    }
}
