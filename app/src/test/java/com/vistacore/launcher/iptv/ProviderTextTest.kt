package com.vistacore.launcher.iptv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Names observed on a real 3,155-channel lineup. This provider generates a channel per
 * sporting event and puts the raw schedule in the channel NAME, which made the whole
 * ribbon unreadable and pushed the matchup — the only useful part — off the screen.
 */
class ProviderTextTest {

    @Test
    fun `strips the start-stop schedule block from an event channel name`() {
        assertEquals(
            "Mets x Padres",
            ProviderText.cleanName("Mets x Padres start:2026-06-06 02:40:00 stop:2026-06-06 09:53:20")
        )
    }

    @Test
    fun `strips a start block with no stop`() {
        assertEquals(
            "Rays x Marlins",
            ProviderText.cleanName("Rays x Marlins start:2026-06-06 02:40:00")
        )
    }

    @Test
    fun `handles the provider tag and the schedule block together`() {
        assertEquals(
            "Athletics x Astros",
            ProviderText.cleanName("US| Athletics x Astros start:2026-06-06 23:05:00 stop:2026-06-07 02:00:00")
        )
    }

    @Test
    fun `the team names survive so search can still match them`() {
        // The whole point: "mariners" has to match this channel by name.
        val cleaned = ProviderText.cleanName(
            "Mariners x Astros start:2026-07-19 02:10:00 stop:2026-07-19 05:00:00"
        )
        assertTrue(cleaned.contains("Mariners", ignoreCase = true))
        assertTrue(cleaned.contains("Astros", ignoreCase = true))
        assertFalse(cleaned.contains("start:"))
        assertFalse(cleaned.contains("stop:"))
        assertFalse(cleaned.contains("2026"))
    }

    @Test
    fun `a normal channel name is untouched`() {
        assertEquals("ESPN", ProviderText.cleanName("ESPN"))
        assertEquals("ROOT Sports NW", ProviderText.cleanName("ROOT Sports NW"))
    }

    @Test
    fun `does not eat a legitimate name that merely contains the word start`() {
        // "start:" with a colon is the marker; a plain word must survive.
        assertEquals("Head Start", ProviderText.cleanName("Head Start"))
        assertEquals("Jump Start TV", ProviderText.cleanName("Jump Start TV"))
    }

    @Test
    fun `blank-after-strip falls back rather than showing nothing`() {
        // A name that is ONLY a schedule block should not clean to an empty string.
        val cleaned = ProviderText.cleanName("start:2026-06-06 02:40:00 stop:2026-06-06 09:53:20")
        assertTrue("must not render as blank", cleaned.isNotBlank())
    }
}
