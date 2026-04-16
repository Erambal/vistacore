package com.vistacore.launcher.ui

import android.content.Context
import android.util.AttributeSet
import android.view.KeyEvent
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/**
 * Horizontal RecyclerView that manually drives D-pad LEFT/RIGHT navigation
 * between children. Android's default focusSearch escapes the list when the
 * next child isn't yet laid out, which lets focus leak to unrelated siblings
 * or snap back to position 0. We bypass it entirely: compute target position,
 * scroll if needed, then request focus once the child is attached.
 */
class FocusTrappedRecyclerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : RecyclerView(context, attrs, defStyle) {

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(event)
        if (event.keyCode != KeyEvent.KEYCODE_DPAD_LEFT &&
            event.keyCode != KeyEvent.KEYCODE_DPAD_RIGHT) {
            return super.dispatchKeyEvent(event)
        }

        val lm = layoutManager as? LinearLayoutManager ?: return super.dispatchKeyEvent(event)
        if (lm.orientation != LinearLayoutManager.HORIZONTAL) return super.dispatchKeyEvent(event)

        val focused = focusedChild ?: return super.dispatchKeyEvent(event)
        val count = adapter?.itemCount ?: 0
        if (count == 0) return true

        val currentPos = getChildAdapterPosition(focused)
        if (currentPos == NO_POSITION) return super.dispatchKeyEvent(event)

        val target = if (event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT) currentPos - 1
                     else currentPos + 1

        // Trap at edges — do not let focus escape.
        if (target < 0 || target >= count) return true

        val vh = findViewHolderForAdapterPosition(target)
        if (vh != null) {
            // VH exists (possibly clipped at the edge) — focus it and
            // scroll so it's fully visible, not just barely peeking in.
            vh.itemView.requestFocus()
            smoothScrollToPosition(target)
        } else {
            // VH not yet laid out — scroll it in, then grab focus as
            // soon as it appears via onScrolled (fires every frame).
            smoothScrollToPosition(target)
            addOnScrollListener(object : OnScrollListener() {
                private var focused = false
                override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                    if (!focused) {
                        findViewHolderForAdapterPosition(target)?.itemView?.let {
                            it.requestFocus()
                            focused = true
                        }
                    }
                }
                override fun onScrollStateChanged(rv: RecyclerView, newState: Int) {
                    if (newState == SCROLL_STATE_IDLE) {
                        rv.removeOnScrollListener(this)
                        if (!focused) {
                            findViewHolderForAdapterPosition(target)?.itemView?.requestFocus()
                        }
                    }
                }
            })
        }
        return true
    }
}
