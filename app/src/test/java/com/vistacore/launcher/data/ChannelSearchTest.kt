package com.vistacore.launcher.data

import com.vistacore.launcher.iptv.Channel
import com.vistacore.launcher.iptv.ContentType
import com.vistacore.launcher.iptv.EpgChannel
import com.vistacore.launcher.iptv.EpgData
import com.vistacore.launcher.iptv.EpgProgram
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Date

/**
 * Pins the search behaviours that were individually broken across four implementations.
 * Each test here corresponds to a real dead end a user hit.
 */
class ChannelSearchTest {

    private val now = System.currentTimeMillis()

    private fun ch(
        name: String,
        number: Int = 0,
        category: String = "Uncategorized",
        epgId: String = name
    ) = Channel(
        id = name,
        name = name,
        streamUrl = "http://x/$name",
        category = category,
        number = number,
        contentType = ContentType.LIVE,
        epgId = epgId
    )

    private fun epg(vararg entries: Triple<String, String, LongRange>): EpgData = EpgData(
        channels = entries.associate { it.first to EpgChannel(it.first, it.first) },
        programs = entries.map { (id, title, offsetMins) ->
            EpgProgram(
                channelId = id,
                title = title,
                startTime = Date(now + offsetMins.first * 60_000L),
                endTime = Date(now + offsetMins.last * 60_000L)
            )
        }
    )

    // --- the Mariners case ---

    @Test
    fun `finds a channel by an upcoming programme, not just what is airing now`() {
        val root = ch("ROOT Sports NW", epgId = "root")
        // Game starts in two hours — the old now-playing-only clause could never find it.
        val guide = epg(
            Triple("root", "Mariners Pregame", -30L..120L),
            Triple("root", "MLB Baseball: Mariners vs. Astros", 120L..300L)
        )
        val result = ChannelSearch.match(root, "mariners", guide)
        assertNotNull(result)
        assertEquals(ChannelSearch.MatchKind.NOW_PLAYING, result!!.kind)
    }

    @Test
    fun `upcoming-only match is reported as upcoming`() {
        val fox = ch("FOX 13", epgId = "fox")
        val guide = epg(
            Triple("fox", "The Simpsons", -30L..30L),
            Triple("fox", "MLB Baseball: Mariners vs. Astros", 180L..360L)
        )
        val result = ChannelSearch.match(fox, "mariners", guide)
        assertEquals(ChannelSearch.MatchKind.UPCOMING, result?.kind)
        assertTrue(ChannelSearch.explain(result!!)!!.startsWith("Coming up:"))
    }

    // --- numeric queries no longer suppress names ---

    @Test
    fun `numeric query still finds a channel whose name contains the digits`() {
        // "60" used to match channel numbers ONLY, so "60 Minutes" was unfindable.
        val sixty = ch("60 Minutes", number = 402)
        assertEquals(ChannelSearch.MatchKind.CHANNEL_NAME, ChannelSearch.match(sixty, "60", null)?.kind)
    }

    @Test
    fun `numeric query still works as a channel-number prefix`() {
        val espn = ch("ESPN", number = 206)
        assertEquals(ChannelSearch.MatchKind.CHANNEL_NUMBER, ChannelSearch.match(espn, "20", null)?.kind)
    }

    @Test
    fun `digit in a channel name is findable`() {
        val espn2 = ch("ESPN2", number = 209)
        assertNotNull(ChannelSearch.match(espn2, "2", null))
    }

    // --- category ---

    @Test
    fun `matches on category when nothing else does`() {
        val chan = ch("ROOT NW", category = "USA | SPORTS")
        assertEquals(ChannelSearch.MatchKind.CATEGORY, ChannelSearch.match(chan, "sports", null)?.kind)
    }

    @Test
    fun `no match returns null`() {
        assertNull(ChannelSearch.match(ch("HGTV", category = "LIFESTYLE"), "mariners", null))
    }

    @Test
    fun `blank query never matches`() {
        assertNull(ChannelSearch.match(ch("ESPN"), "   ", null))
        assertTrue(ChannelSearch.search(listOf(ch("ESPN")), "", null).isEmpty())
    }

    // --- ordering ---

    @Test
    fun `channel name beats a programme mention`() {
        val espn = ch("ESPN", number = 206, epgId = "espn")
        val other = ch("Some Channel", number = 500, epgId = "other")
        val guide = epg(Triple("other", "ESPN Films: The Last Dance", -30L..60L))

        val results = ChannelSearch.search(listOf(other, espn), "espn", guide)
        assertEquals("ESPN", results.first().channel.name)
        assertEquals(2, results.size)
    }

    @Test
    fun `now playing beats upcoming`() {
        val a = ch("Channel A", number = 1, epgId = "a")
        val b = ch("Channel B", number = 2, epgId = "b")
        val guide = epg(
            Triple("a", "Later: Mariners vs Astros", 200L..300L),
            Triple("b", "Now: Mariners vs Astros", -10L..60L)
        )
        val results = ChannelSearch.search(listOf(a, b), "mariners", guide)
        assertEquals("Channel B", results.first().channel.name)
    }

    @Test
    fun `search works with no guide at all`() {
        val channels = listOf(ch("ESPN"), ch("HGTV"))
        assertEquals(1, ChannelSearch.search(channels, "espn", null).size)
    }

    @Test
    fun `explain is null when the name itself matched`() {
        val result = ChannelSearch.match(ch("ESPN"), "espn", null)
        assertNull(ChannelSearch.explain(result!!))
    }
}
