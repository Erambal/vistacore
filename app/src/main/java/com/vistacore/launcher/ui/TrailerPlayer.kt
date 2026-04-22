package com.vistacore.launcher.ui

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.view.View
import android.webkit.JavascriptInterface
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

    // Fire TV and many Google TV devices report a User-Agent that YouTube's
    // embed gate rejects (Error 153 "Video player configuration error").
    // Use a stock Chrome desktop UA to be treated as a normal browser.
    private const val DESKTOP_UA =
        "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/121.0.0.0 Safari/537.36"

    // youtube-nocookie.com is YouTube's privacy-enhanced embed domain.
    // It skips the personalized-ads pipeline, which in WebView contexts
    // (Fire TV / Android TV) otherwise fails with "Error 152-4" because
    // the ad request can't resolve from the WebView's network stack.
    // Still a valid origin as far as the player is concerned.
    private const val YT_EMBED_ORIGIN = "https://www.youtube-nocookie.com"

    @SuppressLint("SetJavaScriptEnabled")
    private fun baseSetup(web: WebView) {
        web.settings.javaScriptEnabled = true
        web.settings.mediaPlaybackRequiresUserGesture = false
        web.settings.domStorageEnabled = true
        web.settings.loadWithOverviewMode = true
        web.settings.useWideViewPort = true
        web.settings.userAgentString = DESKTOP_UA
        web.webChromeClient = WebChromeClient()
        web.setBackgroundColor(0xFF000000.toInt())
        web.isVerticalScrollBarEnabled = false
        web.isHorizontalScrollBarEnabled = false
    }

    /**
     * Muted, looping, controls off — ambient preview.
     *
     * [onReady] fires on the UI thread the first time the YouTube player
     * reports the PLAYING state — i.e. once the actual video frames are on
     * screen and the "Watch on YouTube" title / loading spinner are gone.
     * Callers should keep the WebView at alpha 0 and fade it in from the
     * callback so none of the YouTube chrome ever leaks through.
     *
     * A fallback timer fires the callback after [readyTimeoutMs] in case
     * the IFrame API never dispatches a state change (network stall, ad
     * pre-roll, etc.) — better a slightly-early fade than no trailer.
     */
    fun configureBackdropPreview(
        web: WebView,
        youtubeId: String,
        readyTimeoutMs: Long = 6000L,
        onReady: (() -> Unit)? = null,
    ) {
        baseSetup(web)
        attachReadyBridge(web, onReady, readyTimeoutMs)
        loadEmbed(web, youtubeId, muted = true, controls = false, loop = true)
    }

    /** Full-screen, unmuted, with controls — on-demand playback. */
    fun configureFullscreen(
        web: WebView,
        youtubeId: String,
        readyTimeoutMs: Long = 8000L,
        onReady: (() -> Unit)? = null,
    ) {
        baseSetup(web)
        // Focusable in touch mode so the WebView can take D-pad focus and
        // forward media keys (play/pause) into the YouTube player.
        web.isFocusable = true
        web.isFocusableInTouchMode = true
        attachReadyBridge(web, onReady, readyTimeoutMs)
        loadEmbed(web, youtubeId, muted = false, controls = true, loop = false)
    }

    private const val BRIDGE_NAME = "VistaCoreTrailer"

    private fun attachReadyBridge(web: WebView, onReady: (() -> Unit)?, timeoutMs: Long) {
        if (onReady == null) return
        val main = Handler(Looper.getMainLooper())
        val fired = java.util.concurrent.atomic.AtomicBoolean(false)
        val deliver = Runnable {
            if (fired.compareAndSet(false, true)) onReady()
        }
        web.addJavascriptInterface(object {
            @JavascriptInterface
            fun onPlaying() { main.post(deliver) }
        }, BRIDGE_NAME)
        main.postDelayed(deliver, timeoutMs)
    }

    /** Clear the player and drop any resources it holds. */
    fun stop(web: WebView) {
        web.stopLoading()
        web.loadUrl("about:blank")
        web.clearHistory()
    }

    /**
     * Load the YouTube embed via an HTML wrapper with
     * [WebView.loadDataWithBaseURL] so the player sees
     * `https://www.youtube.com/` as the embedding origin. Loading the embed
     * URL directly results in either a null origin (Error 153) or a
     * black frame because the player's JS can't verify where it's hosted.
     */
    private fun loadEmbed(web: WebView, ytId: String, muted: Boolean, controls: Boolean, loop: Boolean) {
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
            add("enablejsapi=1")
            add("origin=$YT_EMBED_ORIGIN")
        }.joinToString("&")
        val embedUrl = "$YT_EMBED_ORIGIN/embed/$ytId?$params"
        // IFrame API lets us listen for the PLAYING state so the host can
        // delay fading the WebView in until real video frames are on screen
        // — no YouTube title text or spinner ever becomes visible.
        val html = """
            <!DOCTYPE html><html><head><meta name="viewport" content="width=device-width,initial-scale=1">
            <style>html,body{margin:0;padding:0;height:100%;width:100%;background:#000;overflow:hidden;}
            #player{width:100%;height:100%;border:0;display:block;}</style>
            </head><body>
            <iframe id="player" src="$embedUrl"
              allow="autoplay; encrypted-media; picture-in-picture"
              allowfullscreen></iframe>
            <script>
              var notified = false;
              function notifyPlaying(){
                if (notified) return;
                notified = true;
                try { if (window.$BRIDGE_NAME && window.$BRIDGE_NAME.onPlaying) window.$BRIDGE_NAME.onPlaying(); } catch(e){}
              }
              function onYouTubeIframeAPIReady(){
                try {
                  new YT.Player('player', {
                    events: {
                      onStateChange: function(e){
                        if (e.data === YT.PlayerState.PLAYING) notifyPlaying();
                      }
                    }
                  });
                } catch(e){}
              }
            </script>
            <script src="https://www.youtube.com/iframe_api"></script>
            </body></html>
        """.trimIndent()
        web.loadDataWithBaseURL(YT_EMBED_ORIGIN, html, "text/html", "utf-8", null)
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
