package com.vistacore.launcher.ui

import android.content.Context
import android.view.View
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/**
 * D-pad-safe adapter swapping.
 *
 * Every list in this app refreshes by constructing a brand-new adapter and assigning it
 * (`list.adapter = SomeAdapter(...)`). That detaches every view, including the one holding
 * focus — so on a TV the cursor silently vanishes and the scroll position resets. It
 * happens on the most ordinary actions: tuning a channel, toggling a favourite, the EPG
 * arriving, typing a character into a search box.
 *
 * There is no visible pointer on a TV, so a user who loses focus has no idea where they
 * are. The senior this launcher was built for reads it as "the remote stopped working".
 *
 * Use [setAdapterPreservingFocus] instead of assigning `.adapter` directly whenever the
 * list can be rebuilt while the user is browsing it.
 */
object FocusPreserve {
    /** Layout passes to wait for the target row before giving up. */
    const val MAX_RESTORE_ATTEMPTS = 8

    /**
     * Should a horizontal D-pad press at [position] be pinned rather than allowed to
     * search outside the list?
     *
     * True only at the two ends. Pulled out of [RibbonLayoutManager] so the boundary
     * arithmetic can be tested without a Context or a live RecyclerView — an
     * off-by-one here either re-opens the focus-escape bug or traps the user on the
     * last channel, and neither is obvious from reading it.
     *
     * [movingRight] false means LEFT. [itemCount] of 0 pins, since there is nowhere
     * to go at all.
     */
    fun shouldPinFocus(position: Int, movingRight: Boolean, itemCount: Int): Boolean {
        if (itemCount <= 0) return true
        if (position < 0 || position >= itemCount) return false
        val target = if (movingRight) position + 1 else position - 1
        return target < 0 || target >= itemCount
    }
}

/**
 * Horizontal layout manager for the channel ribbons that refuses to let D-pad focus
 * escape sideways.
 *
 * Holding RIGHT to run through a few hundred channels used to end with the cursor
 * jumping up into the SEARCH / MOVIES / TV SHOWS bar. Nothing was rebuilding the list
 * — the cause is the end of the ribbon. Once there is no next item, focusSearch finds
 * nothing inside the RecyclerView, so the framework widens the search to the whole
 * window and the top bar is what it lands on.
 *
 * A sighted user with a mouse would see the cursor move. On a TV the ribbon simply
 * stops responding to RIGHT and the highlight is somewhere else entirely, which reads
 * as the remote misbehaving. Pinning focus at the ends is what a channel list should
 * do anyway: run out of channels and stay put.
 *
 * Only LEFT/RIGHT are pinned. UP and DOWN still leave the ribbon normally, so the top
 * bar and the category button stay reachable on purpose rather than by accident.
 */
class RibbonLayoutManager(context: Context) :
    LinearLayoutManager(context, HORIZONTAL, false) {

    override fun onInterceptFocusSearch(focused: View, direction: Int): View? {
        if (direction != View.FOCUS_LEFT && direction != View.FOCUS_RIGHT) {
            return super.onInterceptFocusSearch(focused, direction)
        }

        // `focused` can be a descendant of the row (the favourite heart, say), so
        // resolve up to the row itself before asking for its adapter position.
        val itemView = findContainingItemView(focused)
            ?: return super.onInterceptFocusSearch(focused, direction)
        val position = getPosition(itemView)
        if (position == RecyclerView.NO_POSITION) {
            return super.onInterceptFocusSearch(focused, direction)
        }

        // Returning `focused` means "focus stays exactly here" — the framework never
        // gets to widen the search. Anywhere in the middle of the list we defer to the
        // default handling, which scrolls the next row into view.
        val pin = FocusPreserve.shouldPinFocus(
            position = position,
            movingRight = direction == View.FOCUS_RIGHT,
            itemCount = itemCount
        )
        return if (pin) focused else super.onInterceptFocusSearch(focused, direction)
    }
}

/**
 * Replace this RecyclerView's adapter, restoring scroll position and D-pad focus.
 *
 * If focus was inside the list, it is returned to the same adapter position after the new
 * adapter lays out — clamped to the new item count, so a refresh that shortened the list
 * still lands somewhere sensible instead of nowhere. If focus was elsewhere on screen it
 * is left alone: stealing it back would be its own bug.
 */
fun RecyclerView.setAdapterPreservingFocus(next: RecyclerView.Adapter<*>?) {
    val hadFocus = hasFocus()
    val focusedPosition = if (hadFocus) {
        findFocus()
            ?.let { focused -> findContainingViewHolder(focused)?.bindingAdapterPosition }
            ?.takeIf { it != RecyclerView.NO_POSITION }
    } else null

    // Scroll offset lives in the LayoutManager, which survives the adapter swap.
    val layoutState = layoutManager?.onSaveInstanceState()

    adapter = next

    layoutState?.let { layoutManager?.onRestoreInstanceState(it) }

    if (hadFocus) requestFocusAtPosition(focusedPosition ?: 0)
}

/**
 * Ask for focus on [position] once the row exists.
 *
 * The ViewHolder is usually not attached yet on the frame the adapter is swapped, and for
 * an off-screen position it will not exist until we scroll to it — hence the bounded
 * retry rather than a single `post`.
 */
fun RecyclerView.requestFocusAtPosition(position: Int, attempt: Int = 0) {
    post {
        val count = adapter?.itemCount ?: 0
        if (count == 0) return@post // nothing to focus; caller decides where to go

        val target = position.coerceIn(0, count - 1)
        val holder = findViewHolderForAdapterPosition(target)
        if (holder != null) {
            holder.itemView.requestFocus()
        } else if (attempt < FocusPreserve.MAX_RESTORE_ATTEMPTS) {
            scrollToPosition(target)
            requestFocusAtPosition(position, attempt + 1)
        }
    }
}
