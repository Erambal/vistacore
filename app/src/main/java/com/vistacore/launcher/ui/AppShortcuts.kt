package com.vistacore.launcher.ui

import android.app.Activity
import android.content.Intent
import android.widget.Toast
import com.vistacore.launcher.R
import com.vistacore.launcher.apps.AppId
import com.vistacore.launcher.apps.AppItem
import com.vistacore.launcher.apps.AppLauncher
import com.vistacore.launcher.data.PrefsManager
import com.vistacore.launcher.data.UsageTracker

/**
 * The user's configured external/extra apps (ESPN, Roku, Disney+, Kids and any
 * custom packages) surfaced for the new home layouts so they keep the same app
 * access the Classic home has. Mirrors MainActivity's app definitions and
 * launch dispatch so behaviour is identical.
 */
object AppShortcuts {

    // Already represented by the three content lanes — excluded so we don't
    // list Live TV / Movies / TV Shows twice.
    private val LANE_IDS = setOf("IPTV", "MOVIES", "TV_SHOWS")

    /** Enabled apps minus the three content lanes, in the user's saved order. */
    fun extraApps(activity: Activity): List<AppItem> {
        val prefs = PrefsManager(activity)
        return prefs.enabledApps.mapNotNull { id ->
            when {
                id in LANE_IDS -> null
                id.startsWith("custom:") -> customApp(activity, id)
                else -> builtIn(activity, id)
            }
        }
    }

    private fun builtIn(activity: Activity, id: String): AppItem? = when (id) {
        "ESPN" -> AppItem(AppId.ESPN, activity.getString(R.string.app_espn), R.drawable.ic_espn, activity.getColor(R.color.espn_red))
        "ROKU" -> AppItem(AppId.ROKU, activity.getString(R.string.app_roku), R.drawable.ic_roku, activity.getColor(R.color.roku_purple))
        "DISNEY_PLUS" -> AppItem(AppId.DISNEY_PLUS, activity.getString(R.string.app_disney), R.drawable.ic_disney, activity.getColor(R.color.disney_blue))
        "KIDS" -> AppItem(AppId.KIDS, activity.getString(R.string.app_kids), R.drawable.ic_kids, activity.getColor(R.color.kids_yellow))
        else -> null
    }

    private fun customApp(activity: Activity, id: String): AppItem? {
        val pkg = id.removePrefix("custom:")
        return try {
            val ai = activity.packageManager.getApplicationInfo(pkg, 0)
            val label = activity.packageManager.getApplicationLabel(ai).toString()
            AppItem(AppId.SETTINGS, label, R.drawable.ic_settings, activity.getColor(R.color.settings_gray), pkg)
        } catch (_: Exception) { null }
    }

    /** Launch an app shortcut, mirroring MainActivity.onAppClicked. */
    fun launch(activity: Activity, app: AppItem) {
        try { UsageTracker(activity).trackAppUsage(app.id.name) } catch (_: Exception) {}

        if (app.customPackage != null) {
            if (!AppLauncher.launchPackage(activity, app.customPackage)) {
                Toast.makeText(activity, "App not found", Toast.LENGTH_SHORT).show()
            }
            return
        }
        when (app.id) {
            AppId.KIDS -> activity.startActivity(Intent(activity, KidsBrowserActivity::class.java))
            AppId.SETTINGS -> activity.startActivity(Intent(activity, SettingsActivity::class.java))
            else -> AppLauncher.launchApp(activity, app.id)
        }
    }
}
