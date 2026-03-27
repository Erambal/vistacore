package com.vistacore.launcher.ui

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.TextClock
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.vistacore.launcher.R
import java.text.SimpleDateFormat
import java.util.*

/**
 * Ambient screen saver mode.
 * Shows a floating clock + date that drifts around the screen to prevent burn-in.
 * Any key press exits back to the launcher.
 */
class ScreenSaverActivity : BaseActivity() {

    private val handler = Handler(Looper.getMainLooper())
    private var driftRunnable: Runnable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Build the UI programmatically for a clean fullscreen experience
        val root = FrameLayout(this).apply {
            setBackgroundColor(0xFF000000.toInt())
            keepScreenOn = true
        }

        val clockContainer = createClockContainer()
        root.addView(clockContainer)

        setContentView(root)

        // Start the floating drift animation
        startDriftAnimation(clockContainer, root)
    }

    private fun createClockContainer(): FrameLayout {
        val container = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val innerLayout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER
            setPadding(48, 32, 48, 32)
        }

        // Time
        val clock = TextClock(this).apply {
            format12Hour = "h:mm"
            format24Hour = "HH:mm"
            textSize = 96f
            setTextColor(getColor(R.color.accent_gold))
            typeface = android.graphics.Typeface.create("sans-serif-thin", android.graphics.Typeface.NORMAL)
            letterSpacing = 0.05f
        }
        innerLayout.addView(clock)

        // AM/PM
        val amPm = TextClock(this).apply {
            format12Hour = "a"
            format24Hour = ""
            textSize = 28f
            setTextColor(getColor(R.color.text_secondary))
            typeface = android.graphics.Typeface.create("sans-serif-light", android.graphics.Typeface.NORMAL)
        }
        innerLayout.addView(amPm)

        // Date
        val date = TextClock(this).apply {
            format12Hour = "EEEE, MMMM d"
            format24Hour = "EEEE, MMMM d"
            textSize = 28f
            setTextColor(getColor(R.color.text_secondary))
            typeface = android.graphics.Typeface.create("sans-serif-light", android.graphics.Typeface.NORMAL)
        }
        innerLayout.addView(date)

        // Subtle glow label
        val label = TextView(this).apply {
            text = "VistaCore"
            textSize = 16f
            setTextColor(0x44FFFFFF)
            typeface = android.graphics.Typeface.create("sans-serif-light", android.graphics.Typeface.NORMAL)
            gravity = android.view.Gravity.CENTER
            setPadding(0, 24, 0, 0)
        }
        innerLayout.addView(label)

        container.addView(innerLayout)
        return container
    }

    private fun startDriftAnimation(clockView: View, parent: FrameLayout) {
        val random = Random()

        driftRunnable = object : Runnable {
            override fun run() {
                val parentWidth = parent.width
                val parentHeight = parent.height
                val viewWidth = clockView.width
                val viewHeight = clockView.height

                if (parentWidth > 0 && parentHeight > 0 && viewWidth > 0 && viewHeight > 0) {
                    val maxX = (parentWidth - viewWidth).coerceAtLeast(0).toFloat()
                    val maxY = (parentHeight - viewHeight).coerceAtLeast(0).toFloat()

                    val targetX = random.nextFloat() * maxX
                    val targetY = random.nextFloat() * maxY

                    ObjectAnimator.ofFloat(clockView, "translationX", targetX).apply {
                        duration = 20000
                        interpolator = LinearInterpolator()
                        start()
                    }
                    ObjectAnimator.ofFloat(clockView, "translationY", targetY).apply {
                        duration = 20000
                        interpolator = LinearInterpolator()
                        start()
                    }
                }

                handler.postDelayed(this, 20000)
            }
        }

        // Initial position — center
        clockView.post {
            val centerX = ((parent.width - clockView.width) / 2).toFloat()
            val centerY = ((parent.height - clockView.height) / 2).toFloat()
            clockView.translationX = centerX
            clockView.translationY = centerY

            handler.postDelayed(driftRunnable!!, 5000)
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        // Any key press exits the screen saver
        finish()
        return true
    }

    override fun onDestroy() {
        super.onDestroy()
        driftRunnable?.let { handler.removeCallbacks(it) }
    }
}
