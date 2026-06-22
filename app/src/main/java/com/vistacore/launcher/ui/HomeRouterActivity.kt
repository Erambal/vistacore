package com.vistacore.launcher.ui

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.vistacore.launcher.data.PrefsManager

/**
 * Invisible router that forwards to the home layout the user picked in
 * Settings. Mirrors [LiveTVActivity]: read the pref, start the chosen
 * variant, finish immediately so it never sits on the back stack.
 *
 * Default is the original app-card home ([MainActivity]) so existing
 * installs see no change until they opt into a new layout.
 */
class HomeRouterActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = PrefsManager(this)
        val target: Class<*> = when (prefs.homeLayout) {
            PrefsManager.HOME_TV_TURNS_ON -> HomeTvTurnsOnActivity::class.java
            PrefsManager.HOME_SIMPLE_ROWS -> HomeSimpleRowsActivity::class.java
            // Classic is the default and the fallback for any retired/unknown
            // value (e.g. an install that had three_lanes/spotlight saved).
            else -> MainActivity::class.java
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
