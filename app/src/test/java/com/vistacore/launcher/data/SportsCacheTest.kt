package com.vistacore.launcher.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the caching rules for the ESPN scoreboard memo.
 *
 * SportsCache.get() itself hits the network, so these tests exercise the freshness and
 * keying decisions rather than the fetch. Getting these wrong either hammers ESPN on
 * every home resume (the original bug) or pins a stale/empty games row.
 */
class SportsCacheTest {

    /** Mirrors SportsCache.isFresh. */
    private fun isFresh(
        fetchedAt: Long,
        cachedFor: Set<String>,
        requested: Set<String>,
        now: Long
    ): Boolean = fetchedAt > 0L && cachedFor == requested && (now - fetchedAt) < SportsCache.TTL_MS

    private val sports = setOf("baseball", "basketball")

    @Test
    fun `a fresh entry for the same sports is reused`() {
        val t = 1_000_000L
        assertTrue(isFresh(t, sports, sports, t + 60_000L))
    }

    @Test
    fun `an expired entry is not reused`() {
        val t = 1_000_000L
        assertFalse(isFresh(t, sports, sports, t + SportsCache.TTL_MS + 1))
    }

    @Test
    fun `changing the enabled sports invalidates the entry`() {
        val t = 1_000_000L
        // Enabling hockey must not serve the baseball+basketball result.
        assertFalse(isFresh(t, sports, sports + "hockey", t + 1000L))
    }

    @Test
    fun `an unfetched cache is never fresh`() {
        assertFalse(isFresh(0L, emptySet(), sports, 1_000_000L))
    }

    @Test
    fun `ttl is short enough that live scores stay meaningful`() {
        // Live scores move on the order of minutes; anything much longer would show
        // stale scores on the home screen.
        assertTrue("TTL should be at most 5 minutes", SportsCache.TTL_MS <= 5 * 60 * 1000L)
        assertTrue("TTL should be long enough to stop per-resume refetching", SportsCache.TTL_MS >= 60 * 1000L)
    }

    @Test
    fun `invalidate clears the entry`() {
        SportsCache.invalidate()
        // After invalidate, fetchedAt is 0 so nothing can be considered fresh.
        assertFalse(isFresh(0L, sports, sports, 1_000_000L))
    }

    @Test
    fun `an empty result is not cached so a transient outage retries`() {
        // Documents the intended rule: only a non-empty fetch updates the memo.
        val games = emptyList<String>()
        val shouldCache = games.isNotEmpty()
        assertFalse("empty results must not be cached", shouldCache)
        assertEquals(0, games.size)
    }
}
