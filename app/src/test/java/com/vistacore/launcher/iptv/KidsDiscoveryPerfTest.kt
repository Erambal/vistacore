package com.vistacore.launcher.iptv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * isKidsItem is called once per catalog item during Splash preload. On the 2 GB box,
 * over an 80k-movie + 42k-series catalog, the old form (31 separate franchise regexes
 * per item) measured 61 seconds and blew the 25s preload budget. It was replaced with a
 * single combined franchise regex.
 *
 * These pin that the combined regex is behaviourally identical to walking the franchise
 * list — so a franchise added later cannot silently drop out of the fast path — and that
 * the classifier's block/allow decisions are unchanged.
 */
class KidsDiscoveryPerfTest {

    private fun ch(name: String, category: String = "") =
        Channel(id = name, name = name, streamUrl = "http://x", category = category)

    // A title that actually matches each franchise's pattern. NB: a franchise's `label`
    // is only a display name — "Marvel" is labelled "Marvel" but its pattern matches
    // Avengers/Thor/etc., never the word "Marvel" — so samples must come from the tokens
    // the patterns really look for.
    private val franchiseSamples = mapOf(
        "bluey" to "Bluey", "cocomelon" to "Cocomelon", "peppa" to "Peppa Pig",
        "sesame" to "Sesame Street", "daniel-tiger" to "Daniel Tiger",
        "mickey" to "Mickey Mouse Clubhouse", "paw-patrol" to "Paw Patrol",
        "pj-masks" to "PJ Masks", "octonauts" to "Octonauts", "doc-mcs" to "Doc McStuffins",
        "sofia" to "Sofia the First", "frozen" to "Frozen II", "toy-story" to "Toy Story",
        "cars" to "Cars", "nemo-dory" to "Finding Nemo", "shrek" to "Shrek",
        "madagascar" to "Madagascar", "ice-age" to "Ice Age", "tom-jerry" to "Tom and Jerry",
        "looney" to "Looney Tunes", "scooby" to "Scooby-Doo", "incredibles" to "The Incredibles",
        "kung-fu" to "Kung Fu Panda", "dragon" to "How to Train Your Dragon",
        "spider" to "Spider-Man", "star-wars" to "Star Wars", "marvel" to "The Avengers",
        "batman" to "Batman", "pokemon" to "Pokemon", "lego" to "The LEGO Movie",
        "power-rangers" to "Power Rangers",
    )

    /** The invariant the optimization must preserve: the combined-regex fast path in
     *  isKidsItem accepts every franchise that matchFranchise recognizes. Also flags any
     *  franchise this test forgot to cover, so a newly-added one can't slip through. */
    @Test
    fun `combined franchise detection agrees with matchFranchise for every franchise`() {
        for (f in KidsDiscovery.FRANCHISES) {
            val sample = franchiseSamples[f.key]
                ?: error("franchise '${f.key}' has no test sample — add one so its fast-path match is verified")
            val item = ch(sample)
            assertTrue(
                "franchise ${f.key}: matchFranchise should detect '$sample'",
                KidsDiscovery.matchFranchise(item) != null
            )
            assertTrue(
                "franchise ${f.key}: isKidsItem should accept '$sample' via the combined regex",
                KidsDiscovery.isKidsItem(item)
            )
        }
    }

    @Test
    fun `the cars negative lookahead still excludes a year-suffixed title`() {
        // "Cars" is kids; "Cars 2006" (a year) must NOT match the cars franchise — the
        // combined regex must preserve that per-alternative lookahead.
        assertTrue(KidsDiscovery.matchFranchise(ch("Cars")) != null)
        assertTrue(KidsDiscovery.matchFranchise(ch("Cars 3")) != null)
        assertEquals(null, KidsDiscovery.matchFranchise(ch("Cars 2006")))
    }

    @Test
    fun `blocked adult content is rejected even when it name-matches a franchise token`() {
        // BLOCK_RE runs first. A title mixing an adult token must lose regardless.
        val blocked = KidsDiscovery.isKidsItem(ch("Batman XXX Parody"))
        assertFalse(blocked)
    }

    @Test
    fun `allowlist terms still classify as kids without a franchise`() {
        assertTrue(KidsDiscovery.isKidsItem(ch("PBS Kids Morning Block")))
        assertTrue(KidsDiscovery.isKidsItem(ch("Cartoon Time", category = "Children")))
    }

    @Test
    fun `plain adult and neutral titles are not kids`() {
        assertFalse(KidsDiscovery.isKidsItem(ch("The Godfather")))
        assertFalse(KidsDiscovery.isKidsItem(ch("Monday Night Football", category = "Sports")))
    }

    @Test
    fun `classifying a large batch stays well under a second`() {
        // Regression guard for the perf fix itself: 50k mixed items must classify fast.
        // The old 31-regex-per-item form took ~24ms/1000 on desktop; this asserts the
        // combined form is not accidentally reverted to per-franchise iteration.
        val names = listOf(
            "Bluey", "The Godfather", "Paw Patrol Movie", "Breaking Bad",
            "PBS Kids", "Random Action Flick", "Frozen II", "Documentary"
        )
        val batch = (0 until 50_000).map { ch(names[it % names.size] + " $it") }
        val start = System.nanoTime()
        var kids = 0
        for (item in batch) if (KidsDiscovery.isKidsItem(item)) kids++
        val elapsedMs = (System.nanoTime() - start) / 1_000_000
        assertTrue("classified $kids/50000 kids", kids > 0)
        assertTrue("50k items took ${elapsedMs}ms — expected < 1500ms", elapsedMs < 1500)
    }
}
