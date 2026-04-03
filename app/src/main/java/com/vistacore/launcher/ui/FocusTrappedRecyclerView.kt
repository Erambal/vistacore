package com.vistacore.launcher.ui

import android.content.Context
import android.util.AttributeSet
import android.view.KeyEvent
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/**
 * A horizontal RecyclerView that traps D-pad LEFT/RIGHT focus at boundaries.
 * Prevents focus from escaping to views outside the list.
 */
class FocusTrappedRecyclerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : RecyclerView(context, attrs, defStyle) {

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            val lm = layoutManager as? LinearLayoutManager ?: return super.dispatchKeyEvent(event)
            val focused = focusedChild ?: return super.dispatchKeyEvent(event)
            val pos = lm.getPosition(focused)
            val count = adapter?.itemCount ?: 0
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_RIGHT -> if (pos >= count - 1) return true
                KeyEvent.KEYCODE_DPAD_LEFT -> if (pos <= 0) return true
            }
        }
        return super.dispatchKeyEvent(event)
    }
}
