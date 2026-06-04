package com.vistacore.launcher.data

import android.content.Context

/**
 * Remembers streams that failed to play with a *permanent* error (HTTP 404 —
 * "this stream is no longer available"). The catalog has no "is this playable"
 * field; a dead stream is only discovered when ExoPlayer fails on it. We record
 * those URLs here so the browse rows can hide them on the next load — the app
 * learns and stops showing the same broken titles.
 *
 * We deliberately do NOT record 403 / "subscription expired": that failure is
 * account-wide and transient, so blocklisting on it would wipe the whole
 * catalog the moment a provider hiccups.
 */
class DeadStreamManager(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun markDead(streamUrl: String) {
        if (streamUrl.isBlank()) return
        val set = current().toMutableSet()
        if (!set.add(streamUrl)) return
        // Cap growth on a flaky provider. StringSet order isn't guaranteed, so
        // this trims an arbitrary subset once over the cap — acceptable, since
        // the cap is only a memory backstop, not a correctness requirement.
        val trimmed = if (set.size > MAX) set.toList().takeLast(MAX).toSet() else set
        prefs.edit().putStringSet(KEY, trimmed).apply()
    }

    fun isDead(streamUrl: String): Boolean =
        streamUrl.isNotBlank() && current().contains(streamUrl)

    /** Snapshot of all known-dead URLs, for filtering browse rows. */
    fun deadUrls(): Set<String> = current()

    fun clear() = prefs.edit().remove(KEY).apply()

    private fun current(): Set<String> = prefs.getStringSet(KEY, emptySet()) ?: emptySet()

    companion object {
        private const val PREFS_NAME = "vistacore_dead_streams"
        private const val KEY = "dead_urls"
        private const val MAX = 2000
    }
}
