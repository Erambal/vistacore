package com.vistacore.launcher

import android.app.Application
import android.content.ComponentCallbacks2
import android.util.Log
import com.bumptech.glide.Glide
import org.conscrypt.Conscrypt
import java.security.Security

class VistaCoreApp : Application() {
    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.LEGACY_TLS) {
            try {
                Security.insertProviderAt(Conscrypt.newProvider(), 1)
                Log.d(TAG, "Conscrypt TLS provider installed")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to install Conscrypt", e)
            }
        }
    }

    /**
     * Memory-pressure policy for the 2 GB onn box.
     *
     * Sized from what the device actually reports rather than intuition: a
     * dumpsys on the target showed the Java heap holding 7-25 MB while ~158 MB
     * of this process sat swapped to zram, with 685 MB of zram in use
     * device-wide. So the pressure is not a runaway data structure inside the
     * app — it is a 1.4 GB box that is oversubscribed overall, and the useful
     * move is to shrink our resident working set so we fault less, not to drop
     * the catalog and pay to rebuild it.
     *
     * Deliberately narrow: images only. ContentCache is NOT released here, at
     * any level. It is worth single-digit MB against a 128 MB heap limit, and
     * dropping it while the process lives arms a far worse failure — Splash
     * gates its rebuild on ContentCache.isReady, so releasing rows without
     * clearing that flag permanently strands showEpisodesIndex (built only in
     * SplashActivity.preloadContent), while clearing the flag makes every
     * subsequent HOME press re-parse the whole 42k-entry series cache from
     * disk. Both make the thrashing worse, not better.
     */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // Glide registers its own ComponentCallbacks2 and already clears at
        // BACKGROUND and above, but only trims to HALF at RUNNING_CRITICAL.
        // RUNNING_CRITICAL is the case that matters here — foreground, player
        // active, box out of memory — so the explicit clear is a real delta.
        // Nothing at RUNNING_LOW or below: that fires routinely during playback
        // and re-decoding the ribbon logos seconds later is net-negative churn.
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL) {
            Log.d(TAG, "trim($level): clearing image memory")
            try {
                Glide.get(this).clearMemory()
            } catch (e: Exception) {
                Log.w(TAG, "Glide clearMemory failed", e)
            }
        }
    }

    companion object {
        private const val TAG = "VistaCoreApp"
    }
}
