package com.vistacore.launcher.ui

import android.annotation.SuppressLint
import android.view.View
import android.webkit.WebChromeClient
import android.webkit.WebView

/**
 * Helpers for loading a YouTube trailer into a WebView.
 *
 * Two use-cases supported by both detail screens:
 *
 *  - [configureBackdropPreview] — muted, looping, no controls. Fades in
 *    over the backdrop ImageView a short while after the detail page
 *    loads. Intended as ambient motion, like Netflix.
 *
 *  - [configureFullscreen] — unmuted, with controls, covers the whole
 *    screen. Launched by the user tapping the Trailer button. An in-app
 *    WebView instead of firing an ACTION_VIEW intent (which fails on
 *    Fire TV / Google TV devices with no YouTube app installed).
 *
 * Both variants call [WebView.loadUrl] directly to the YouTube embed URL
 * rather than wrapping an iframe in a data:-scheme HTML page. The
 * data-URL approach creates a null-origin context that YouTube often
 * refuses to play in, leaving a black surface.
 */
object TrailerPlayer {

    @SuppressLint("SetJavaScriptEnabled")
    private fun baseSetup(web: WebView) {
        web.settings.javaScriptEnabled = true
        web.settings.mediaPlaybackRequiresUserGesture = false
        web.settings.domStorageEnabled = true
        web.settings.loadWithOverviewMode = true
        web.settings.useWideViewPort = true
        web.webChromeClient = WebChromeClient()
        web.setBackgroundColor(0xFF000000.toInt())
        web.isVerticalScrollBarEnabled = false
        web.isHorizontalScrollBarEnabled = false
    }

    /** Muted, looping, controls off — ambient preview. */
    fun configureBackdropPreview(web: WebView, youtubeId: String) {
        baseSetup(web)
        val url = embedUrl(youtubeId, muted = true, controls = false, loop = true)
        web.loadUrl(url)
    }

    /** Full-screen, unmuted, with controls — on-demand playback. */
    fun configureFullscreen(web: WebView, youtubeId: String) {
        baseSetup(web)
        // Focusable in touch mode so the WebView can take D-pad focus and
        // forward media keys (play/pause) into the YouTube player.
        web.isFocusable = true
        web.isFocusableInTouchMode = true
        val url = embedUrl(youtubeId, muted = false, controls = true, loop = false)
        web.loadUrl(url)
    }

    /** Clear the player and drop any resources it holds. */
    fun stop(web: WebView) {
        web.stopLoading()
        web.loadUrl("about:blank")
        web.clearHistory()
    }

    private fun embedUrl(ytId: String, muted: Boolean, controls: Boolean, loop: Boolean): String {
        val params = buildList {
            add("autoplay=1")
            add(if (muted) "mute=1" else "mute=0")
            add(if (controls) "controls=1" else "controls=0")
            if (loop) {
                add("loop=1")
                add("playlist=$ytId")
            }
            add("modestbranding=1")
            add("rel=0")
            add("iv_load_policy=3")
            add("playsinline=1")
        }.joinToString("&")
        return "https://www.youtube.com/embed/$ytId?$params"
    }

    /** Accept a YouTube id, a youtu.be link, a watch URL, or an embed URL. */
    fun extractId(raw: String): String? {
        if (raw.isBlank()) return null
        val m = Regex("""(?:v=|youtu\.be/|embed/)([A-Za-z0-9_-]{11})""").find(raw)
        if (m != null) return m.groupValues[1]
        if (Regex("""^[A-Za-z0-9_-]{11}$""").matches(raw.trim())) return raw.trim()
        return null
    }
}
