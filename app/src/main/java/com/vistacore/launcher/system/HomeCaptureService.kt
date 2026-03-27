package com.vistacore.launcher.system

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import com.vistacore.launcher.data.PrefsManager
import com.vistacore.launcher.ui.SplashActivity

/**
 * Accessibility service that intercepts Home button presses on Google TV devices.
 * When the stock launcher comes to the foreground, this service immediately
 * launches VistaCore on top — making it behave as the default launcher.
 */
class HomeCaptureService : AccessibilityService() {

    companion object {
        private const val TAG = "HomeCaptureService"

        // Known Google TV / Android TV stock launcher packages
        private val LAUNCHER_PACKAGES = setOf(
            "com.google.android.apps.tv.launcherx",   // Google TV (Onn, Chromecast)
            "com.google.android.tvlauncher",           // Older Android TV
            "com.amazon.tv.launcher",                  // Fire TV
            "com.hisense.hitv.launcher",               // Hisense
            "com.tcl.tv.launcher"                      // TCL
        )

        var isRunning = false
            private set
    }

    private var lastRedirectTime = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        isRunning = true
        Log.d(TAG, "Home capture service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return

        val packageName = event.packageName?.toString() ?: return

        // Only intercept if the stock launcher is coming to the foreground
        if (packageName !in LAUNCHER_PACKAGES) return

        // Check if user has home capture enabled
        val prefs = PrefsManager(this)
        if (!prefs.autoLaunchOnBoot) return

        // Debounce — don't redirect more than once per second
        val now = System.currentTimeMillis()
        if (now - lastRedirectTime < 1000) return
        lastRedirectTime = now

        Log.d(TAG, "Stock launcher detected ($packageName), redirecting to VistaCore")

        val intent = Intent(this, SplashActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        startActivity(intent)
    }

    override fun onInterrupt() {
        Log.d(TAG, "Home capture service interrupted")
    }

    override fun onDestroy() {
        isRunning = false
        super.onDestroy()
    }
}
