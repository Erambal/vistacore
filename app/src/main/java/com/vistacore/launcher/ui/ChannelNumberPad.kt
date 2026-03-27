package com.vistacore.launcher.ui

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.vistacore.launcher.R
import com.vistacore.launcher.iptv.Channel

/**
 * Channel Number Pad overlay for the IPTV player.
 * When user presses number keys on remote, shows an overlay with the typed number.
 * After a brief delay, tunes to that channel number.
 *
 * Usage: Attach to a parent FrameLayout and call handleKeyEvent() from onKeyDown.
 */
class ChannelNumberPad(
    private val context: Context,
    private val parentLayout: FrameLayout,
    private val channels: List<Channel>,
    private val onChannelSelected: (Channel) -> Unit
) {
    companion object {
        private const val AUTO_TUNE_DELAY_MS = 2500L
        private const val MAX_DIGITS = 4
    }

    private var currentInput = StringBuilder()
    private val handler = Handler(Looper.getMainLooper())
    private var overlayView: View? = null
    private var numberDisplay: TextView? = null
    private var channelPreview: TextView? = null
    private var autoTuneRunnable: Runnable? = null

    /**
     * Handle a key event. Returns true if consumed (was a number key).
     */
    fun handleKeyEvent(keyCode: Int): Boolean {
        val digit = keyCodeToDigit(keyCode) ?: return false

        // Cancel any pending auto-tune
        autoTuneRunnable?.let { handler.removeCallbacks(it) }

        if (currentInput.length >= MAX_DIGITS) {
            currentInput.clear()
        }

        currentInput.append(digit)
        showOverlay()
        updateDisplay()

        // Schedule auto-tune
        autoTuneRunnable = Runnable {
            tuneToChannel()
        }
        handler.postDelayed(autoTuneRunnable!!, AUTO_TUNE_DELAY_MS)

        return true
    }

    private fun showOverlay() {
        if (overlayView != null) return

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(0xDD000000.toInt())
            setPadding(48, 32, 48, 32)

            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.TOP or Gravity.END
                topMargin = 48
                rightMargin = 48
            }
        }

        val label = TextView(context).apply {
            text = "Channel"
            textSize = 16f
            setTextColor(context.getColor(R.color.text_secondary))
        }
        container.addView(label)

        numberDisplay = TextView(context).apply {
            textSize = 48f
            setTextColor(context.getColor(R.color.accent_gold))
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
        }
        container.addView(numberDisplay)

        channelPreview = TextView(context).apply {
            textSize = 18f
            setTextColor(context.getColor(R.color.text_primary))
            setPadding(0, 8, 0, 0)
        }
        container.addView(channelPreview)

        overlayView = container
        parentLayout.addView(container)
    }

    private fun updateDisplay() {
        numberDisplay?.text = currentInput.toString()

        // Preview which channel will be selected
        val num = currentInput.toString().toIntOrNull()
        val match = if (num != null) channels.find { it.number == num } else null
        channelPreview?.text = match?.name ?: "—"
    }

    private fun tuneToChannel() {
        val num = currentInput.toString().toIntOrNull()
        val match = if (num != null) channels.find { it.number == num } else null

        if (match != null) {
            onChannelSelected(match)
        }

        hideOverlay()
    }

    private fun hideOverlay() {
        currentInput.clear()
        overlayView?.let { parentLayout.removeView(it) }
        overlayView = null
        numberDisplay = null
        channelPreview = null
    }

    private fun keyCodeToDigit(keyCode: Int): Char? {
        return when (keyCode) {
            KeyEvent.KEYCODE_0, KeyEvent.KEYCODE_NUMPAD_0 -> '0'
            KeyEvent.KEYCODE_1, KeyEvent.KEYCODE_NUMPAD_1 -> '1'
            KeyEvent.KEYCODE_2, KeyEvent.KEYCODE_NUMPAD_2 -> '2'
            KeyEvent.KEYCODE_3, KeyEvent.KEYCODE_NUMPAD_3 -> '3'
            KeyEvent.KEYCODE_4, KeyEvent.KEYCODE_NUMPAD_4 -> '4'
            KeyEvent.KEYCODE_5, KeyEvent.KEYCODE_NUMPAD_5 -> '5'
            KeyEvent.KEYCODE_6, KeyEvent.KEYCODE_NUMPAD_6 -> '6'
            KeyEvent.KEYCODE_7, KeyEvent.KEYCODE_NUMPAD_7 -> '7'
            KeyEvent.KEYCODE_8, KeyEvent.KEYCODE_NUMPAD_8 -> '8'
            KeyEvent.KEYCODE_9, KeyEvent.KEYCODE_NUMPAD_9 -> '9'
            else -> null
        }
    }

    fun destroy() {
        autoTuneRunnable?.let { handler.removeCallbacks(it) }
        hideOverlay()
    }
}
