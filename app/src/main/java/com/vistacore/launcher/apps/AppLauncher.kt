package com.vistacore.launcher.apps

import android.content.Context
import android.content.Intent
import android.widget.Toast
import com.vistacore.launcher.R

/**
 * Handles launching external streaming apps and internal IPTV.
 */
object AppLauncher {

    // Known package names for supported streaming apps
    private val PACKAGE_MAP = mapOf(
        AppId.DISNEY_PLUS to listOf(
            "com.disney.disneyplus",           // Standard Disney+
            "com.disney.disneyplus.tv",        // TV variant
            "com.disney.disneyplus_goo"        // Google TV variant
        ),
        AppId.ESPN to listOf(
            "com.espn.score_center",           // Standard ESPN
            "com.espn.score_center.tv",        // TV variant
            "com.espn.espn"                    // Newer ESPN app
        ),
        AppId.ROKU to listOf(
            "com.roku.remote",                 // Roku remote
            "com.roku.remote.tv",              // TV variant
            "com.roku.channelstore",           // Roku Channel Store
            "com.roku.web.trc"                 // Roku streaming app
        )
    )

    fun launchApp(context: Context, appId: AppId) {
        val packages = PACKAGE_MAP[appId] ?: return

        for (packageName in packages) {
            if (launchPackage(context, packageName)) return
        }

        // App not installed — show friendly message
        Toast.makeText(
            context,
            context.getString(R.string.error_app_not_installed),
            Toast.LENGTH_LONG
        ).show()
    }

    fun launchPackage(context: Context, packageName: String): Boolean {
        val pm = context.packageManager

        // 1. Try standard launcher
        var intent = pm.getLaunchIntentForPackage(packageName)
        if (intent != null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            return true
        }

        // 2. Try Leanback launcher (Android TV)
        intent = pm.getLeanbackLaunchIntentForPackage(packageName)
        if (intent != null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            return true
        }

        // 3. Try querying for any activity in the package
        try {
            val mainIntent = Intent(Intent.ACTION_MAIN).apply {
                setPackage(packageName)
            }
            // Try LEANBACK_LAUNCHER category
            mainIntent.addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
            val leanbackActivities = pm.queryIntentActivities(mainIntent, 0)
            if (leanbackActivities.isNotEmpty()) {
                val activity = leanbackActivities[0].activityInfo
                intent = Intent().apply {
                    setClassName(activity.packageName, activity.name)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                return true
            }

            // Try LAUNCHER category
            mainIntent.removeCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
            mainIntent.addCategory(Intent.CATEGORY_LAUNCHER)
            val launcherActivities = pm.queryIntentActivities(mainIntent, 0)
            if (launcherActivities.isNotEmpty()) {
                val activity = launcherActivities[0].activityInfo
                intent = Intent().apply {
                    setClassName(activity.packageName, activity.name)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                return true
            }
        } catch (_: Exception) { }

        return false
    }

    fun isAppInstalled(context: Context, appId: AppId): Boolean {
        val packages = PACKAGE_MAP[appId] ?: return false
        return packages.any { pkg ->
            try {
                context.packageManager.getPackageInfo(pkg, 0)
                true
            } catch (_: Exception) {
                false
            }
        }
    }
}

enum class AppId {
    DISNEY_PLUS,
    ESPN,
    ROKU,
    IPTV,
    MOVIES,
    TV_SHOWS,
    KIDS,
    SETTINGS
}

data class AppItem(
    val id: AppId,
    val label: String,
    val iconRes: Int,
    val brandColor: Int,
    val customPackage: String? = null
)
