package com.vistacore.launcher.iptv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Date

/**
 * Covers the per-channel index behind EpgData and the boundary calculation the
 * Live TV screens use to know when "now playing" goes stale.
 */
class EpgDataTest {

    private val now = System.currentTimeMillis()

    private fun mins(m: Long) = Date(now + m * 60_000L)

    private fun prog(channel: String, title: String, startMin: Long, endMin: Long) =
        EpgProgram(
            channelId = channel,
            title = title,
            startTime = mins(startMin),
            endTime = mins(endMin)
        )

    private fun epg(vararg programs: EpgProgram) =
        EpgData(channels = emptyMap(), programs = programs.toList())

    @Test
    fun `getNowPlaying returns the airing program for the right channel`() {
        val data = epg(
            prog("espn", "SportsCenter", -30, 30),
            prog("root", "Mariners vs. Astros", -60, 120),
            prog("root", "Postgame", 120, 180)
        )
        assertEquals("SportsCenter", data.getNowPlaying("espn")?.title)
        assertEquals("Mariners vs. Astros", data.getNowPlaying("root")?.title)
    }

    @Test
    fun `getNowPlaying does not leak programs across channels`() {
        val data = epg(prog("espn", "SportsCenter", -30, 30))
        assertNull(data.getNowPlaying("root"))
        assertNull(data.getNowPlaying("unknown-channel"))
    }

    @Test
    fun `getNowPlaying excludes a program that already ended`() {
        val data = epg(prog("espn", "Old Game", -180, -60))
        assertNull(data.getNowPlaying("espn"))
    }

    @Test
    fun `getUpcoming is start-ordered and window-bounded`() {
        val data = epg(
            prog("espn", "Third", 200, 260),
            prog("espn", "First", 20, 80),
            prog("espn", "Second", 80, 140),
            prog("espn", "Way Out", 60 * 20, 60 * 21)
        )
        val upcoming = data.getUpcoming("espn", hours = 6).map { it.title }
        assertEquals(listOf("First", "Second", "Third"), upcoming)
    }

    @Test
    fun `getUpcoming excludes the currently airing program`() {
        val data = epg(
            prog("espn", "Airing Now", -30, 30),
            prog("espn", "Next", 30, 90)
        )
        assertEquals(listOf("Next"), data.getUpcoming("espn").map { it.title })
    }

    // --- boundary scheduling ---

    @Test
    fun `nextProgramBoundary returns the soonest end among the given channels`() {
        val data = epg(
            prog("espn", "Ends Later", -30, 90),
            prog("root", "Ends Sooner", -30, 25),
            prog("mlbn", "Ends Latest", -30, 200)
        )
        val boundary = data.nextProgramBoundary(listOf("espn", "root", "mlbn"))
        assertEquals(mins(25).time, boundary?.time)
    }

    @Test
    fun `nextProgramBoundary ignores channels that are not in the list`() {
        val data = epg(
            prog("espn", "Ends Later", -30, 90),
            prog("root", "Ends Sooner", -30, 25)
        )
        // Only asking about espn — root's earlier boundary must not be returned.
        assertEquals(mins(90).time, data.nextProgramBoundary(listOf("espn"))?.time)
    }

    @Test
    fun `nextProgramBoundary is null when nothing is airing`() {
        val data = epg(
            prog("espn", "Finished", -180, -60),
            prog("espn", "Not Started", 60, 120)
        )
        assertNull(data.nextProgramBoundary(listOf("espn")))
        assertNull(data.nextProgramBoundary(emptyList()))
    }

    @Test
    fun `boundary is always in the future so a tick cannot schedule a busy loop`() {
        val data = epg(
            prog("espn", "Airing", -30, 5),
            prog("root", "Airing", -120, 45)
        )
        val boundary = data.nextProgramBoundary(listOf("espn", "root"))
        assertTrue("boundary should be ahead of now", boundary!!.time > now)
    }

    @Test
    fun `index does not affect data class equality`() {
        val programs = listOf(prog("espn", "SportsCenter", -30, 30))
        val a = EpgData(emptyMap(), programs)
        val b = EpgData(emptyMap(), programs)
        // Touch the lazy index on one instance only.
        a.getNowPlaying("espn")
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }
}
