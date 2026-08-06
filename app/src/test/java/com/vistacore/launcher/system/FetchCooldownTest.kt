package com.vistacore.launcher.system

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Observed on the device: the full 42,737-entry series catalog was downloaded three
 * times in under four minutes.
 *
 * The loop — WorkManager's KEEP policy only dedupes work that is still in flight, so a
 * completed fetch does not stop the next one. A content type whose endpoint is failing
 * never gets a cache written, VODBrowserActivity sees the empty cache and calls
 * refreshNow, and the fetch pulls live + movies + series with no type scoping. So a
 * Movies browser re-downloads the entire series catalog, movies 504s again, and the
 * next open repeats it.
 *
 * These pin the gate that breaks it. The failure mode to guard hardest against is not
 * "fetches too often" — it is the opposite: a cooldown that never expires and silently
 * blocks refreshes forever.
 */
class FetchCooldownTest {

    private val id = "xtream|stream.fixesto.com|Fixesto"
    private val interval = ChannelUpdateWorker.minFetchIntervalMs

    private fun due(
        lastIdentity: String? = id,
        currentIdentity: String = id,
        lastAttemptMs: Long,
        nowMs: Long,
    ) = ChannelUpdateWorker.isFetchDue(lastIdentity, currentIdentity, lastAttemptMs, nowMs)

    @Test
    fun `a second fetch moments after the first is refused`() {
        // The actual bug: browser reopened 46s after a fetch, triggering another.
        assertFalse(due(lastAttemptMs = 1_000_000L, nowMs = 1_000_000L + 46_000L))
    }

    @Test
    fun `the three observed refetches collapse to one`() {
        // Real timings: 08:11:00, 08:11:46, 08:14:29 — a 209s span, all well inside
        // the 10 minute window, so only the first should have gone to the network.
        val first = 0L
        assertTrue("first fetch must run", due(lastIdentity = null, lastAttemptMs = 0L, nowMs = first))
        assertFalse("08:11:46 must be refused", due(lastAttemptMs = first, nowMs = 46_000L))
        assertFalse("08:14:29 must be refused", due(lastAttemptMs = first, nowMs = 209_000L))
    }

    @Test
    fun `a fetch is allowed again once the window passes`() {
        assertTrue(due(lastAttemptMs = 0L, nowMs = interval))
        assertTrue(due(lastAttemptMs = 0L, nowMs = interval + 1))
    }

    @Test
    fun `the boundary is exclusive - one millisecond early is still refused`() {
        assertFalse(due(lastAttemptMs = 0L, nowMs = interval - 1))
    }

    @Test
    fun `changing provider voids the cooldown immediately`() {
        // A catalog pulled seconds ago belongs to the previous account. Making the
        // user wait ten minutes after entering new credentials would look broken.
        assertTrue(
            due(
                lastIdentity = "xtream|old.example.com|olduser",
                currentIdentity = id,
                lastAttemptMs = 1_000_000L,
                nowMs = 1_000_001L,
            )
        )
    }

    @Test
    fun `a first ever fetch with no stored identity is due`() {
        assertTrue(due(lastIdentity = null, lastAttemptMs = 0L, nowMs = 0L))
    }

    @Test
    fun `a clock moving backwards does not lock refreshes out`() {
        // This box boots with a bogus RTC and NTP corrects it later. If the stored
        // timestamp ends up in the future, a naive `since < interval` check would
        // block every refresh until wall-clock caught up — potentially days.
        assertTrue(
            "future timestamp must not block refresh",
            due(lastAttemptMs = 9_000_000L, nowMs = 1_000_000L)
        )
    }

    @Test
    fun `a wildly future timestamp still resolves to due rather than blocking`() {
        assertTrue(due(lastAttemptMs = Long.MAX_VALUE / 2, nowMs = 1_000L))
    }
}
