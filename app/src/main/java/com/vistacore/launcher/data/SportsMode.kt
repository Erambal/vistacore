package com.vistacore.launcher.data

import com.vistacore.launcher.iptv.Channel
import com.vistacore.launcher.iptv.ContentType
import com.vistacore.launcher.iptv.EpgData
import com.vistacore.launcher.iptv.UpcomingGame

/**
 * Sports Mode — narrows Live TV down to "things a game might be on right now".
 *
 * Three independent signals, unioned, because no single one is reliable across providers:
 *
 *  1. The provider's own category ("USA | SPORTS HD"). Cheapest and usually right, but
 *     plenty of lineups bury regional sports nets in a generic "USA" bucket.
 *  2. The channel name against a table of known sports networks. Catches the ones
 *     signal 1 misses.
 *  3. The EPG title currently airing ("MLB Baseball: Mariners vs. Astros"). Catches a
 *     game on a channel that isn't a sports network at all — a nationally broadcast
 *     game on FOX or ABC, which is exactly when someone can't find it.
 *
 * Results are ordered so channels with a game on *right now* float to the top, because
 * the person using this is looking for a game, not for a channel.
 */
object SportsMode {

    const val CATEGORY_SPORTS = "Sports"

    /** Provider-category tokens that mean "this whole group is sports". */
    private val CATEGORY_KEYWORDS = listOf(
        "sport", "sports", "espn", "nfl", "nba", "mlb", "nhl", "ncaa", "golf",
        "racing", "fight", "ufc", "boxing", "wrestling", "soccer", "futbol",
        "futebol", "cricket", "rugby", "tennis", "olympic"
    )

    /**
     * Known sports networks, in normalized form (see [normalize]). Matched as a
     * whole-token or prefix against the normalized channel name, never as a loose
     * substring — "cbs" must not claim every CBS affiliate, only "cbssportsnetwork".
     */
    private val NETWORK_NAMES = listOf(
        // National
        "espn", "espn2", "espn3", "espnu", "espnews", "espndeportes", "espnplus",
        "fs1", "fs2", "foxsports1", "foxsports2", "foxsports",
        "cbssportsnetwork", "cbssports", "cbssn",
        "nbcsports", "usanetwork", "golfchannel", "golf",
        "mlbnetwork", "nflnetwork", "nflredzone", "redzone", "nbatv", "nhlnetwork",
        "tennischannel", "motortrend", "beinsports", "beinsport",
        "accnetwork", "secnetwork", "bigtennetwork", "btn", "pac12",
        "willowtv", "willowcricket", "flosports", "stadium",
        // Regional (the ones that actually carry local teams)
        "rootsports", "rootsportsnw", "rootsportsnorthwest",
        "fanduelsportsnetwork", "ballysports", "masn", "yesnetwork",
        "nesn", "sny", "marquee", "altitude", "spectrumsportsnet", "msg",
        "nbcsportsbayarea", "nbcsportsboston", "nbcsportsphiladelphia",
        "nbcsportscalifornia", "nbcsportswashington", "spacecitynetwork"
    )

    /** Tokens in a program title that mean "a game is on". */
    private val PROGRAM_KEYWORDS = listOf(
        "mlb", "nba", "nfl", "nhl", "mls", "ncaa", "wnba",
        "baseball", "basketball", "football", "hockey", "soccer",
        "golf", "tennis", "nascar", "indycar", "formula 1", "motogp",
        "ufc", "boxing", "wrestling", "cricket", "rugby", "olympics",
        "postgame", "pregame", "highlights", "sportscenter"
    )

    /**
     * ESPN's broadcast label → the names a provider is likely to use for the same
     * network. ESPN says "ROOT SPORTS NW"; a lineup might carry it as
     * "ROOT Sports Northwest HD". Keys and values are normalized on lookup.
     */
    private val BROADCAST_ALIASES: Map<String, List<String>> = mapOf(
        "rootsportsnw" to listOf("rootsports", "rootsportsnorthwest", "rootsportsnw"),
        "rootsportsnorthwest" to listOf("rootsports", "rootsportsnw"),
        "fs1" to listOf("foxsports1", "fs1"),
        "fs2" to listOf("foxsports2", "fs2"),
        "abc" to listOf("abc"),
        "nbc" to listOf("nbc"),
        "cbs" to listOf("cbs"),
        "fox" to listOf("fox"),
        "tbs" to listOf("tbs"),
        "tnt" to listOf("tnt"),
        "espn" to listOf("espn"),
        "espn2" to listOf("espn2"),
        "mlbn" to listOf("mlbnetwork", "mlbn"),
        "mlbnetwork" to listOf("mlbnetwork", "mlbn"),
        "nflnetwork" to listOf("nflnetwork", "nfln"),
        "nbatv" to listOf("nbatv"),
        "nhln" to listOf("nhlnetwork", "nhln"),
        "cbssn" to listOf("cbssportsnetwork", "cbssn"),
        "usa" to listOf("usanetwork", "usa"),
        "peacock" to listOf("peacock"),
        "appletv" to listOf("appletv", "appletvplus"),
        "primevideo" to listOf("primevideo", "amazonprime")
    )

    /**
     * Broadcast labels that are short, generic English words and therefore dangerous to
     * match loosely: "FOX" would otherwise claim "FOX News", "ABC" would claim
     * "ABC Family". For these we require an exact normalized name or a confirmed live
     * game — never a bare prefix match.
     */
    private val GENERIC_BROADCASTS = setOf("abc", "nbc", "cbs", "fox", "usa", "tbs", "tnt")

    /** Quality/'+' suffixes that appear in channel names but carry no identity. */
    private val QUALITY_TOKENS = listOf(
        "hd", "fhd", "uhd", "sd", "4k", "hevc", "h265", "raw", "alt", "backup"
    )

    /**
     * Reduce a channel or network name to a comparable core: lowercase, drop any
     * country/group prefix ("US: ", "USA | "), drop quality suffixes, strip everything
     * that isn't alphanumeric. "USA | ROOT Sports NW HD" → "rootsportsnw".
     */
    fun normalize(raw: String): String {
        var s = raw.lowercase().trim()
        // Strip a leading country/group prefix delimited by ':' or '|'.
        val delim = s.indexOfLast { it == ':' || it == '|' }
        if (delim in 0 until s.length - 1) s = s.substring(delim + 1)
        // Drop trailing quality tokens before stripping punctuation, while they're
        // still separable words.
        var words = s.split(' ', '-', '_', '.', '(', ')', '[', ']')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        while (words.isNotEmpty() && words.last() in QUALITY_TOKENS) {
            words = words.dropLast(1)
        }
        return words.joinToString("").filter { it.isLetterOrDigit() }
    }

    private fun nowPlayingTitle(channel: Channel, epg: EpgData?): String? {
        val e = epg ?: return null
        val key = channel.epgId.ifBlank { channel.id }
        return (e.getNowPlaying(key) ?: e.getNowPlaying(channel.name))?.title
    }

    private fun matchesNetworkName(normalizedName: String): Boolean {
        if (normalizedName.isEmpty()) return false
        return NETWORK_NAMES.any { net ->
            normalizedName == net || normalizedName.startsWith(net) || normalizedName.endsWith(net)
        }
    }

    /** True when this channel is a sports network, or has a game on right now. */
    fun isSportsChannel(channel: Channel, epg: EpgData? = null): Boolean {
        if (channel.contentType != ContentType.LIVE) return false

        val category = channel.category.lowercase()
        if (CATEGORY_KEYWORDS.any { category.contains(it) }) return true

        if (matchesNetworkName(normalize(channel.name))) return true

        val title = nowPlayingTitle(channel, epg)?.lowercase()
        if (title != null && PROGRAM_KEYWORDS.any { title.contains(it) }) return true

        return false
    }

    /** True when a game (not a talk show) appears to be airing on this channel now. */
    fun hasLiveGame(channel: Channel, epg: EpgData?): Boolean {
        val title = nowPlayingTitle(channel, epg)?.lowercase() ?: return false
        val looksLikeMatchup = title.contains(" vs") || title.contains(" @ ") ||
            title.contains(" at ")
        val leagueNamed = PROGRAM_KEYWORDS.any { title.contains(it) }
        // A studio show mentions the league too, so require a matchup shape as well
        // unless the title is unambiguously a game.
        return leagueNamed && looksLikeMatchup
    }

    /**
     * The sports lineup, ordered for someone hunting a game: channels with a game on
     * now first, then other sports channels, each group alphabetical so the list
     * doesn't reshuffle unpredictably between visits.
     */
    fun sportsChannels(all: List<Channel>, epg: EpgData?): List<Channel> {
        val sports = all.filter { isSportsChannel(it, epg) }
        val (live, rest) = sports.partition { hasLiveGame(it, epg) }
        return live.sortedBy { it.name.lowercase() } + rest.sortedBy { it.name.lowercase() }
    }

    /**
     * Find the channel carrying a given ESPN broadcast label.
     *
     * This is the link that turns "the Mariners play at 7:10 on ROOT SPORTS NW" into a
     * channel we can actually tune. Tries the alias table first, then a direct
     * normalized comparison, and prefers a channel whose EPG confirms a game is on.
     */
    fun findChannelForBroadcast(
        broadcast: String,
        channels: List<Channel>,
        epg: EpgData? = null
    ): Channel? {
        if (broadcast.isBlank()) return null
        val key = normalize(broadcast)
        if (key.isEmpty()) return null

        val candidates = BROADCAST_ALIASES[key] ?: listOf(key)
        val live = channels.filter { it.contentType == ContentType.LIVE }

        // Exact normalized match is the most trustworthy.
        for (candidate in candidates) {
            val exact = live.filter { normalize(it.name) == candidate }
            if (exact.isNotEmpty()) {
                return exact.firstOrNull { hasLiveGame(it, epg) } ?: exact.first()
            }
        }

        // Then prefix/suffix, which tolerates "ROOT Sports NW Seattle". Generic
        // single-word networks are excluded here — for those, an unconfirmed prefix
        // match is more likely to be wrong ("FOX" → "FOX News") than right.
        for (candidate in candidates) {
            if (candidate in GENERIC_BROADCASTS) continue
            val partial = live.filter {
                val n = normalize(it.name)
                n.startsWith(candidate) || n.endsWith(candidate)
            }
            if (partial.isNotEmpty()) {
                return partial.firstOrNull { hasLiveGame(it, epg) } ?: partial.first()
            }
        }

        // Last resort for the generic ones: accept a loose match only when the guide
        // confirms a game is actually on that channel right now.
        for (candidate in candidates) {
            if (candidate !in GENERIC_BROADCASTS) continue
            val confirmed = live.firstOrNull {
                val n = normalize(it.name)
                (n.startsWith(candidate) || n.endsWith(candidate)) && hasLiveGame(it, epg)
            }
            if (confirmed != null) return confirmed
        }

        return null
    }

    /**
     * Fallback when no broadcast match exists: find a channel whose current program
     * title mentions either team. Handles "Mariners vs. Astros" appearing as
     * "MLB Baseball: Seattle at Houston" by matching on each team's distinctive
     * last word (the nickname) independently, rather than the whole matchup string.
     */
    fun findChannelForGame(
        game: UpcomingGame,
        channels: List<Channel>,
        epg: EpgData?
    ): Channel? {
        findChannelForBroadcast(game.broadcast, channels, epg)?.let { return it }
        val epgData = epg ?: return null

        val nicknames = listOfNotNull(
            game.homeTeam.split(" ").lastOrNull()?.lowercase(),
            game.awayTeam.split(" ").lastOrNull()?.lowercase()
        ).filter { it.length >= 3 }
        if (nicknames.isEmpty()) return null

        return channels
            .filter { it.contentType == ContentType.LIVE }
            .firstOrNull { channel ->
                val title = nowPlayingTitle(channel, epgData)?.lowercase() ?: return@firstOrNull false
                nicknames.any { title.contains(it) }
            }
    }

    /**
     * Search terms for a game, used when we can't resolve a channel and have to fall
     * back to the Live TV filter. Individual tokens, not the concatenated
     * "Mariners Astros" string, which matches nothing in a real lineup.
     */
    fun searchTermsForGame(game: UpcomingGame): List<String> = listOfNotNull(
        game.broadcast.takeIf { it.isNotBlank() },
        game.awayTeam.split(" ").lastOrNull(),
        game.homeTeam.split(" ").lastOrNull()
    ).filter { it.isNotBlank() }
}
