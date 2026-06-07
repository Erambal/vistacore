package com.vistacore.launcher.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import androidx.core.content.ContextCompat
import com.vistacore.launcher.R
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.sin

/**
 * A loading indicator of three dots that bounce in a staggered wave — used in
 * place of the stock circular [android.widget.ProgressBar] throughout the app.
 *
 * Calm and easy to read from across the room (senior-friendly TV UI). Tint via
 * the `app:dotColor` attribute; defaults to accent gold. The animation only
 * runs while the view is actually shown, so off-screen/GONE instances cost
 * nothing.
 */
class BouncingDotsView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    private val dotCount = 3
    // One full bounce cycle. Each dot lags the previous by a fraction of this.
    private val cycleMs = 1050L
    private val stagger = 0.55f // radians of phase offset between adjacent dots

    private var phase = 0f
    private var animator: ValueAnimator? = null

    init {
        var color = ContextCompat.getColor(context, R.color.accent_gold)
        if (attrs != null) {
            val a = context.obtainStyledAttributes(attrs, R.styleable.BouncingDotsView)
            color = a.getColor(R.styleable.BouncingDotsView_dotColor, color)
            a.recycle()
        }
        paint.color = color
    }

    private fun startAnimation() {
        if (animator != null) return
        animator = ValueAnimator.ofFloat(0f, (2 * PI).toFloat()).apply {
            duration = cycleMs
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                phase = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun stopAnimation() {
        animator?.cancel()
        animator = null
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (isShown) startAnimation()
    }

    override fun onDetachedFromWindow() {
        stopAnimation()
        super.onDetachedFromWindow()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility == VISIBLE && isShown) startAnimation() else stopAnimation()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == VISIBLE && isShown) startAnimation() else stopAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val radius = max(2f, h * 0.15f)
        val amplitude = h * 0.22f
        val gap = radius * 1.7f
        val spacing = 2 * radius + gap
        val totalWidth = dotCount * 2 * radius + (dotCount - 1) * gap
        val startX = (w - totalWidth) / 2f + radius
        val baseY = h / 2f

        for (i in 0 until dotCount) {
            val cx = startX + i * spacing
            // Each dot trails the previous; sin gives a smooth up/down bounce.
            val cy = baseY - amplitude * sin(phase - i * stagger)
            canvas.drawCircle(cx, cy, radius, paint)
        }
    }
}
