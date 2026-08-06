package com.vistacore.launcher.data

import com.vistacore.launcher.iptv.SportsDataManager
import com.vistacore.launcher.iptv.UpcomingGame
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Process-level memo for the ESPN scoreboard.
 *
 * The home screen reloads upcoming games on every `onResume`, and each load previously
 * constructed a fresh [SportsDataManager] — a new OkHttpClient with its own connection
 * pool and SSL context — then fired up to five parallel HTTPS requests. Bouncing in and
 * out of a show a few times meant dozens of scoreboard fetches an hour, all returning
 * near-identical data.
 *
 * Scores change on the order of minutes, so a short TTL costs nothing in freshness. A
 * failed fetch is not cached, so a transient outage retries on the next resume rather than
 * pinning an empty row for the whole TTL.
 */
object SportsCache {

    /** Live scores move fast enough that this is about the longest defensible window. */
    const val TTL_MS = 3 * 60 * 1000L

    private val manager by lazy { SportsDataManager() }
    private val mutex = Mutex()

    private var cachedGames: List<UpcomingGame> = emptyList()
    private var cachedFor: Set<String> = emptySet()
    private var fetchedAt: Long = 0L

    private fun isFresh(sports: Set<String>): Boolean =
        fetchedAt > 0L &&
            cachedFor == sports &&
            (System.currentTimeMillis() - fetchedAt) < TTL_MS

    /**
     * Games for [enabledSports], from cache when fresh.
     *
     * The mutex collapses concurrent callers onto a single fetch — two home surfaces
     * resuming together should not both hit ESPN.
     */
    suspend fun get(enabledSports: Set<String>): List<UpcomingGame> {
        if (isFresh(enabledSports)) return cachedGames

        return mutex.withLock {
            // Re-check: another caller may have filled the cache while we waited.
            if (isFresh(enabledSports)) return@withLock cachedGames

            val games = manager.getUpcomingGames(enabledSports)
            // Only cache a real answer. Caching an empty failure would blank the games
            // row for the whole TTL even after connectivity came back.
            if (games.isNotEmpty()) {
                cachedGames = games
                cachedFor = enabledSports
                fetchedAt = System.currentTimeMillis()
            }
            games
        }
    }

    /** Drop the memo — used when the user changes which sports are enabled. */
    fun invalidate() {
        fetchedAt = 0L
        cachedGames = emptyList()
        cachedFor = emptySet()
    }
}
