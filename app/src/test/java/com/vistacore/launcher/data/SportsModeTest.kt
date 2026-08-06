package com.vistacore.launcher.data

import com.vistacore.launcher.iptv.Channel
import com.vistacore.launcher.iptv.ContentType
import com.vistacore.launcher.iptv.EpgChannel
import com.vistacore.launcher.iptv.EpgData
import com.vistacore.launcher.iptv.EpgProgram
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Date

/**
 * Covers the channel-identification logic behind Sports Mode. The names here are real
 * shapes seen in IPTV lineups (country prefixes, quality suffixes, regional net naming),
 * because that formatting variance is exactly what the matching has to survive.
 */
class SportsModeTest {

    private fun ch(
        name: String,
        category: String = "Uncategorized",
        id: String = name,
        epgId: String = name
    ) = Channel(
        id = id,
        name = name,
        streamUrl = "http://example/$id",
        category = category,
        contentType = ContentType.LIVE,
        epgId = epgId
    )

    /** An EPG where [epgId] is airing [title] right now. */
    private fun epgNowPlaying(vararg pairs: Pair<String, String>): EpgData {
        val now = System.currentTimeMillis()
        return EpgData(
            channels = pairs.associate { it.first to EpgChannel(it.first, it.first) },
            programs = pairs.map { (id, title) ->
                EpgProgram(
                    channelId = id,
                    title = title,
                    startTime = Date(now - 30 * 60 * 1000L),
                    endTime = Date(now + 90 * 60 * 1000L)
                )
            }
        )
    }

    // --- normalize ---

    @Test
    fun `normalize strips group prefixes and quality suffixes`() {
        assertEquals("rootsportsnw", SportsMode.normalize("ROOT SPORTS NW"))
        assertEquals("rootsportsnw", SportsMode.normalize("USA | ROOT Sports NW HD"))
        assertEquals("rootsportsnw", SportsMode.normalize("US: ROOT Sports NW FHD"))
        assertEquals("espn2", SportsMode.normalize("ESPN2 4K"))
        assertEquals("mlbnetwork", SportsMode.normalize("MLB Network"))
    }

    // --- sports channel detection ---

    @Test
    fun `detects sports channel by provider category`() {
        assertTrue(SportsMode.isSportsChannel(ch("Some Regional Net", category = "USA | SPORTS HD")))
    }

    @Test
    fun `detects sports channel by network name when category is generic`() {
        // The case that matters: a regional sports net filed under a generic bucket.
        assertTrue(SportsMode.isSportsChannel(ch("ROOT Sports NW HD", category = "USA")))
    }

    @Test
    fun `detects a game on a non-sports channel via the guide`() {
        val fox = ch("FOX 13 Seattle", category = "USA", epgId = "fox13")
        val epg = epgNowPlaying("fox13" to "MLB Baseball: Mariners vs. Astros")
        assertTrue(SportsMode.isSportsChannel(fox, epg))
    }

    @Test
    fun `does not classify an ordinary channel as sports`() {
        assertFalse(SportsMode.isSportsChannel(ch("HGTV HD", category = "USA | LIFESTYLE")))
        assertFalse(SportsMode.isSportsChannel(ch("FOX News HD", category = "USA | NEWS")))
    }

    // --- live game detection ---

    @Test
    fun `hasLiveGame requires a matchup not just a league mention`() {
        val studio = ch("MLB Network", epgId = "mlbn")
        assertFalse(
            SportsMode.hasLiveGame(studio, epgNowPlaying("mlbn" to "MLB Tonight"))
        )
        assertTrue(
            SportsMode.hasLiveGame(studio, epgNowPlaying("mlbn" to "MLB Baseball: Mariners vs. Astros"))
        )
    }

    // --- broadcast → channel ---

    @Test
    fun `resolves ESPN broadcast label to the regional channel`() {
        val channels = listOf(
            ch("HGTV HD", category = "USA | LIFESTYLE"),
            ch("USA | ROOT Sports NW HD", category = "USA | SPORTS"),
            ch("ESPN HD", category = "USA | SPORTS")
        )
        val match = SportsMode.findChannelForBroadcast("ROOT SPORTS NW", channels)
        assertEquals("USA | ROOT Sports NW HD", match?.name)
    }

    @Test
    fun `generic network does not match a same-prefix non-sports channel`() {
        val channels = listOf(
            ch("FOX News HD", category = "USA | NEWS", epgId = "foxnews"),
            ch("FOX Business", category = "USA | NEWS", epgId = "foxbiz")
        )
        // "FOX" must not grab FOX News just because the name starts the same way.
        assertNull(SportsMode.findChannelForBroadcast("FOX", channels))
    }

    @Test
    fun `generic network matches when the guide confirms a game`() {
        val channels = listOf(
            ch("FOX News HD", category = "USA | NEWS", epgId = "foxnews"),
            ch("FOX 13 Seattle", category = "USA", epgId = "fox13")
        )
        val epg = epgNowPlaying(
            "foxnews" to "The Five",
            "fox13" to "MLB Baseball: Mariners vs. Astros"
        )
        assertEquals("FOX 13 Seattle", SportsMode.findChannelForBroadcast("FOX", channels, epg)?.name)
    }

    @Test
    fun `prefers the channel actually airing a game among same-network duplicates`() {
        val channels = listOf(
            ch("ESPN HD", category = "USA | SPORTS", id = "espn-a", epgId = "espn-a"),
            ch("ESPN", category = "USA | SPORTS", id = "espn-b", epgId = "espn-b")
        )
        val epg = epgNowPlaying(
            "espn-a" to "SportsCenter",
            "espn-b" to "NBA Basketball: Lakers vs. Celtics"
        )
        assertEquals("espn-b", SportsMode.findChannelForBroadcast("ESPN", channels, epg)?.id)
    }

    // --- ordering ---

    @Test
    fun `sports list puts channels with a live game first`() {
        val channels = listOf(
            ch("ESPN HD", category = "USA | SPORTS", epgId = "espn"),
            ch("ROOT Sports NW", category = "USA", epgId = "root"),
            ch("HGTV", category = "USA | LIFESTYLE", epgId = "hgtv")
        )
        val epg = epgNowPlaying(
            "espn" to "SportsCenter",
            "root" to "MLB Baseball: Mariners vs. Astros"
        )
        val ordered = SportsMode.sportsChannels(channels, epg)
        assertEquals("ROOT Sports NW", ordered.first().name)
        assertFalse(ordered.any { it.name == "HGTV" })
    }

    // --- search fallback ---

    @Test
    fun `search terms are individual tokens not a concatenated matchup`() {
        val game = com.vistacore.launcher.iptv.UpcomingGame(
            sport = "baseball",
            league = "MLB",
            homeTeam = "Houston Astros",
            awayTeam = "Seattle Mariners",
            homeLogo = "",
            awayLogo = "",
            startTime = Date(),
            status = "pre",
            broadcast = "ROOT SPORTS NW"
        )
        val terms = SportsMode.searchTermsForGame(game)
        assertTrue(terms.contains("ROOT SPORTS NW"))
        assertTrue(terms.contains("Mariners"))
        assertTrue(terms.contains("Astros"))
        // The old behaviour built one string that no channel or title ever contains.
        assertFalse(terms.any { it == "Mariners Astros" })
    }
}
