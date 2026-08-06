package com.vistacore.launcher.ui

import android.content.Context
import android.util.AttributeSet
import android.widget.FrameLayout

/**
 * A FrameLayout that is always as tall as it is wide. Used for the favourites-grid
 * tiles, where the request was literally "squares": a RecyclerView grid cell is sized to
 * (parentWidth / columns), so the tile fills that width and this forces a matching height
 * without hard-coding a dp that would break across resolutions.
 */
class SquareFrameLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0,
) : FrameLayout(context, attrs, defStyle) {

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // Drive height from the resolved width so the tile is square regardless of the
        // height spec the grid hands us.
        super.onMeasure(widthMeasureSpec, widthMeasureSpec)
    }
}
