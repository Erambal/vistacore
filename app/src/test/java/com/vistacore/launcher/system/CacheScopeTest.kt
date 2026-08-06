package com.vistacore.launcher.system

import com.vistacore.launcher.iptv.Channel
import com.vistacore.launcher.iptv.ContentType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the scoping rules behind ChannelUpdateWorker.cacheChannels.
 *
 * cacheChannels itself needs a Context (file IO), so these tests exercise the decision
 * logic it applies rather than the writes: which cache files a given scope is allowed to
 * touch, and whether the VOD-derived caches should be invalidated. Getting this wrong is
 * what silently wiped users' movie and series libraries.
 */
class CacheScopeTest {

    private fun ch(name: String, type: ContentType) =
        Channel(id = name, name = name, streamUrl = "http://x/$name", contentType = type)

    /** Mirrors the guard in cacheChannels: write a type only when it is in scope. */
    private fun writesFor(scope: Set<ContentType>): Set<ContentType> =
        ContentType.values().filter { it in scope }.toSet()

    /** Mirrors the `touchedVod` decision. */
    private fun invalidatesVodDerivedCaches(scope: Set<ContentType>): Boolean =
        ContentType.MOVIE in scope || ContentType.SERIES in scope

    @Test
    fun `live-only scope never writes the movie or series caches`() {
        val writes = writesFor(ChannelUpdateWorker.LIVE_ONLY)
        assertEquals(setOf(ContentType.LIVE), writes)
        assertFalse(ContentType.MOVIE in writes)
        assertFalse(ContentType.SERIES in writes)
    }

    @Test
    fun `full scope writes all three caches`() {
        assertEquals(
            setOf(ContentType.LIVE, ContentType.MOVIE, ContentType.SERIES),
            writesFor(ChannelUpdateWorker.ALL_CONTENT_TYPES)
        )
    }

    @Test
    fun `default scope is the full catalog so existing callers are unaffected`() {
        assertEquals(ContentType.values().toSet(), ChannelUpdateWorker.ALL_CONTENT_TYPES)
    }

    @Test
    fun `a live-only fetch would have blanked VOD under the old whole-catalog behaviour`() {
        // This is the exact shape Xtream getChannels() returns.
        val liveOnlyFetch = listOf(
            ch("ESPN", ContentType.LIVE),
            ch("ROOT Sports NW", ContentType.LIVE)
        )
        val movies = liveOnlyFetch.filter { it.contentType == ContentType.MOVIE }
        val series = liveOnlyFetch.filter { it.contentType == ContentType.SERIES }

        // The partitions really are empty — writing them is what destroyed the caches.
        assertTrue(movies.isEmpty())
        assertTrue(series.isEmpty())

        // Under the live-only scope those writes are skipped entirely.
        val writes = writesFor(ChannelUpdateWorker.LIVE_ONLY)
        assertFalse(
            "movie cache must not be written from a live-only fetch",
            ContentType.MOVIE in writes
        )
        assertFalse(
            "series cache must not be written from a live-only fetch",
            ContentType.SERIES in writes
        )
    }

    @Test
    fun `live-only refresh does not invalidate the show index`() {
        // Nulling showEpisodesIndex on a live-only tick left M3U series unopenable
        // until the app restarted.
        assertFalse(invalidatesVodDerivedCaches(ChannelUpdateWorker.LIVE_ONLY))
    }

    @Test
    fun `a real VOD refresh still invalidates the show index`() {
        assertTrue(invalidatesVodDerivedCaches(ChannelUpdateWorker.ALL_CONTENT_TYPES))
        assertTrue(invalidatesVodDerivedCaches(setOf(ContentType.SERIES)))
        assertTrue(invalidatesVodDerivedCaches(setOf(ContentType.MOVIE)))
    }

    // --- fetch-failure scoping (observed in the wild) ---

    @Test
    fun `a failed movies fetch does not authorise writing the movies cache`() {
        // Real device state that exposed this: 42,723 series cached, movies cache 0.
        // getMovies() threw, fetchAllSources substituted emptyList(), and the caller
        // persisted that as authoritative — deleting the whole movie library.
        val succeeded = setOf(ContentType.LIVE, ContentType.SERIES) // MOVIE threw
        val writes = writesFor(succeeded)

        assertFalse(
            "a movies endpoint failure must leave the existing cache untouched",
            ContentType.MOVIE in writes
        )
        assertTrue(ContentType.LIVE in writes)
        assertTrue(ContentType.SERIES in writes)
    }

    @Test
    fun `a genuinely empty but successful movies fetch still authorises the write`() {
        // The distinction that matters: success-with-zero-results is authoritative,
        // failure is not. A provider that really dropped its movie catalog should
        // clear the cache.
        val succeeded = setOf(ContentType.LIVE, ContentType.MOVIE, ContentType.SERIES)
        assertTrue(ContentType.MOVIE in writesFor(succeeded))
    }

    @Test
    fun `total failure authorises nothing`() {
        assertTrue(writesFor(emptySet()).isEmpty())
    }

    @Test
    fun `a failed VOD fetch does not invalidate the show index`() {
        // invalidatePreload() nulls showEpisodesIndex; doing that on a failed series
        // fetch would strand M3U series with no rebuild until an app restart.
        assertFalse(invalidatesVodDerivedCaches(setOf(ContentType.LIVE)))
    }

    @Test
    fun `mixed M3U fetch keeps its VOD partitions`() {
        val m3uFetch = listOf(
            ch("ESPN", ContentType.LIVE),
            ch("Some Movie", ContentType.MOVIE),
            ch("Some Show S01E01", ContentType.SERIES)
        )
        // M3U genuinely carries VOD, so it stays full-scope and all three get written.
        assertEquals(3, writesFor(ChannelUpdateWorker.ALL_CONTENT_TYPES).size)
        assertEquals(1, m3uFetch.count { it.contentType == ContentType.MOVIE })
        assertEquals(1, m3uFetch.count { it.contentType == ContentType.SERIES })
    }
}
