package com.vistacore.launcher.ui

import android.app.UiModeManager
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.Bundle
import android.view.KeyEvent
import androidx.appcompat.app.AppCompatActivity
import java.util.Locale

/**
 * Base activity that applies the user's font scale and locale preferences.
 * All activities should extend this instead of AppCompatActivity.
 */
open class BaseActivity : AppCompatActivity() {

    /**
     * Whether this activity should permit portrait rotation on tablets /
     * phones. Default: true everywhere except TVs (which have no sensor)
     * and the video player (locks landscape so 16:9 fills the screen).
     *
     * Subclasses override this to opt out. We override the manifest's
     * `screenOrientation="landscape"` at runtime by setting
     * `requestedOrientation = SCREEN_ORIENTATION_USER` in onCreate when
     * rotation is allowed. Manifest stays landscape-by-default so TVs —
     * which often boot with no orientation sensor — never end up stuck
     * in a portrait that they can't rotate out of.
     */
    open fun allowsRotation(): Boolean = !isTelevision()

    /**
     * Whether to inset content by a TV-safe overscan margin. Default: on for
     * TVs only. Many TVs (especially older/cheaper panels) overscan ~5% and
     * clip whatever sits at the physical edge — on the home screen that's the
     * clock and the search/settings buttons. Full-bleed screens (the video
     * player) override this to false so video fills the panel edge-to-edge.
     */
    open fun appliesOverscanInsets(): Boolean = isTelevision()

    private fun isTelevision(): Boolean {
        val uiMode = (getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager)
            ?.currentModeType ?: return false
        return uiMode == Configuration.UI_MODE_TYPE_TELEVISION
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (allowsRotation()) {
            // SCREEN_ORIENTATION_USER respects the user's auto-rotate
            // system toggle — locked-portrait phones won't whip into
            // landscape against their will.
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_USER
        }
    }

    override fun setContentView(layoutResID: Int) {
        super.setContentView(layoutResID)
        applyOverscanInsets()
    }

    override fun setContentView(view: android.view.View?) {
        super.setContentView(view)
        applyOverscanInsets()
    }

    override fun setContentView(view: android.view.View?, params: android.view.ViewGroup.LayoutParams?) {
        super.setContentView(view, params)
        applyOverscanInsets()
    }

    /**
     * Add a ~2.5%-per-edge safe-area padding to the content root so nothing
     * sits in the overscan region on TVs. Additive to any padding the layout
     * already declares, and applied once (guarded by a tag) so repeated
     * setContentView calls don't stack insets.
     */
    private var overscanApplied = false

    private fun applyOverscanInsets() {
        if (!appliesOverscanInsets() || overscanApplied) return
        val content = findViewById<android.view.View>(android.R.id.content) ?: return

        val dm = resources.displayMetrics
        val hInset = (dm.widthPixels * 0.025f).toInt()
        val vInset = (dm.heightPixels * 0.025f).toInt()
        content.setPadding(
            content.paddingLeft + hInset,
            content.paddingTop + vInset,
            content.paddingRight + hInset,
            content.paddingBottom + vInset
        )
        overscanApplied = true
    }

    override fun attachBaseContext(newBase: Context) {
        val prefs = newBase.getSharedPreferences("vistacore_prefs", Context.MODE_PRIVATE)

        val scaleIndex = prefs.getInt("ui_scale", 1)
        val fontScale = when (scaleIndex) {
            0 -> 0.85f  // Small
            2 -> 1.25f  // Large
            else -> 1.0f // Medium (default)
        }

        val langCode = prefs.getString("app_language", "en") ?: "en"
        val locale = Locale(langCode)

        val config = Configuration(newBase.resources.configuration)
        config.fontScale = fontScale
        config.setLocale(locale)

        super.attachBaseContext(newBase.createConfigurationContext(config))
    }

    /**
     * Hijack the remote's mic/search keys so they open VistaCore's own search
     * instead of Google Assistant. This only intercepts keys that are actually
     * delivered to the foreground app — on Google TV, the mic button is wired
     * to Assistant at the platform level and bypasses apps entirely. In that
     * case the user has to change the default Assistant in system settings.
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_SEARCH ||
            keyCode == KeyEvent.KEYCODE_VOICE_ASSIST ||
            keyCode == KeyEvent.KEYCODE_ASSIST
        ) {
            startActivity(
                Intent(this, VoiceSearchActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            )
            return true
        }
        return super.onKeyDown(keyCode, event)
    }
}
