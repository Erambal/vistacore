package com.vistacore.launcher.ui

import android.content.Context
import android.content.res.Configuration
import androidx.appcompat.app.AppCompatActivity

/**
 * Base activity that applies the user's font scale preference.
 * All activities should extend this instead of AppCompatActivity.
 */
open class BaseActivity : AppCompatActivity() {

    override fun attachBaseContext(newBase: Context) {
        val prefs = newBase.getSharedPreferences("vistacore_prefs", Context.MODE_PRIVATE)
        val scaleIndex = prefs.getInt("ui_scale", 1)
        val fontScale = when (scaleIndex) {
            0 -> 0.85f  // Small
            2 -> 1.25f  // Large
            else -> 1.0f // Medium (default)
        }
        val config = Configuration(newBase.resources.configuration)
        config.fontScale = fontScale
        super.attachBaseContext(newBase.createConfigurationContext(config))
    }
}
