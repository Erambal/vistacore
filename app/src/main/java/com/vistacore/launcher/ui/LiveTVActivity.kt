package com.vistacore.launcher.ui

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.vistacore.launcher.data.PrefsManager

/**
 * Router activity that launches the user's chosen Live TV style variant.
 * Kept as an entry point so existing callers (MainActivity, widgets, etc.)
 * don't need to know which variant is active.
 */
class LiveTVActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_SEARCH_QUERY = BaseLiveTVActivity.EXTRA_SEARCH_QUERY
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = PrefsManager(this)
        val target: Class<*> = when (prefs.liveTvStyle) {
            PrefsManager.LIVE_TV_GRID -> LiveTVGridActivity::class.java
            PrefsManager.LIVE_TV_EPG -> LiveTVEpgActivity::class.java
            PrefsManager.LIVE_TV_IMMERSIVE -> LiveTVImmersiveActivity::class.java
            PrefsManager.LIVE_TV_CAROUSEL -> LiveTVCarouselActivity::class.java
            PrefsManager.LIVE_TV_SPLIT_HERO -> LiveTVSplitHeroActivity::class.java
            else -> LiveTVClassicActivity::class.java
        }

        val forward = Intent(this, target).apply {
            intent.extras?.let { putExtras(it) }
            addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
        }
        startActivity(forward)
        finish()
        overridePendingTransition(0, 0)
    }
}
