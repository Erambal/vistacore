package com.vistacore.launcher.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Holding RIGHT through the channel ribbon used to end with focus jumping up into the
 * SEARCH / MOVIES / TV SHOWS bar. Nothing was rebuilding the list — at the end of the
 * ribbon focusSearch finds nothing inside the RecyclerView, so the framework widens the
 * search to the whole window and lands on the top bar.
 *
 * The expected behaviour, in the user's words: "focus should just stop at the last
 * loaded show and wait". These pin that boundary arithmetic.
 */
class RibbonFocusTest {

    private fun right(position: Int, count: Int) =
        FocusPreserve.shouldPinFocus(position, movingRight = true, itemCount = count)

    private fun left(position: Int, count: Int) =
        FocusPreserve.shouldPinFocus(position, movingRight = false, itemCount = count)

    @Test
    fun `right at the last channel stays put instead of escaping to the top bar`() {
        assertTrue(right(position = 299, count = 300))
    }

    @Test
    fun `left at the first channel stays put`() {
        assertTrue(left(position = 0, count = 300))
    }

    @Test
    fun `the middle of the list is never pinned - normal scrolling must still work`() {
        // The whole point of holding RIGHT is to move. Pinning anywhere but the ends
        // would trap the user, which is a worse bug than the one being fixed.
        for (p in 1..298) {
            assertFalse("position $p should not pin moving right", right(p, 300))
            assertFalse("position $p should not pin moving left", left(p, 300))
        }
    }

    @Test
    fun `both directions are free at the ends that are not ends`() {
        assertFalse("right from the first of many should move", right(0, 300))
        assertFalse("left from the last of many should move", left(299, 300))
    }

    @Test
    fun `a single-channel ribbon pins in both directions`() {
        assertTrue(right(0, 1))
        assertTrue(left(0, 1))
    }

    @Test
    fun `an empty ribbon pins rather than letting focus wander`() {
        assertTrue(right(0, 0))
        assertTrue(left(0, 0))
        assertTrue(right(-1, 0))
    }

    @Test
    fun `a stale position outside the list defers to default handling`() {
        // NO_POSITION-ish input: the row was recycled mid-press. Pinning on a position
        // we cannot trust could strand focus, so let the framework decide instead.
        assertFalse(right(500, 300))
        assertFalse(left(-1, 300))
    }

    @Test
    fun `the sports category size from the real device behaves`() {
        // The box reported 300 sports channels prioritised in the EPG budget, and this
        // ribbon is exactly where the user hit the bug.
        assertFalse(right(0, 300))
        assertFalse(right(150, 300))
        assertTrue(right(299, 300))
    }
}
