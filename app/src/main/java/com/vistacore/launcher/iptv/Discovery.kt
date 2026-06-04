package com.vistacore.launcher.iptv

import android.content.Context
import com.vistacore.launcher.data.KeywordCache
import com.vistacore.launcher.data.WatchEntry
import com.vistacore.launcher.data.WatchHistoryManager

/**
 * Discovery: shelves and filters that turn a flat catalog of Channels into
 * a Netflix-style "what should I watch?" experience.
 *
 * Designed to be cheap — every helper here runs over in-memory lists with no
 * network or async work. Intended to be called from background coroutines in
 * the Browser activities and the result handed to the UI.
 */
object Discovery {

    // ─── Movie / Show moods (regex matched against name + category) ───
    data class Mood(val key: String, val label: String, val emoji: String, val pattern: Regex)

    val MOVIE_MOODS = listOf(
        Mood("feel-good",  "Feel-Good",      "☀",  Regex("comed|family|musical|romance|feel.?good", RegexOption.IGNORE_CASE)),
        Mood("funny",      "Funny",          "😄", Regex("comed|sitcom|stand.?up", RegexOption.IGNORE_CASE)),
        Mood("action",     "Action & Adventure", "💥", Regex("action|advent", RegexOption.IGNORE_CASE)),
        Mood("true-story", "True Story",     "📖", Regex("document|biograph|true.?story|history", RegexOption.IGNORE_CASE)),
        Mood("romance",    "Romance",        "❤", Regex("roman", RegexOption.IGNORE_CASE)),
        Mood("western",    "Westerns",       "🤠", Regex("western|cowboy", RegexOption.IGNORE_CASE)),
        Mood("thriller",   "Edge of Your Seat", "🔥", Regex("thrill|mystery|crime", RegexOption.IGNORE_CASE)),
        Mood("classic",    "Classics",       "🎬", Regex("classic|old|vintage|golden.?age", RegexOption.IGNORE_CASE)),
    )

    // Year suffix in title — "Movie Name (1999)"
    private val YEAR_RE = Regex("""\((19|20)\d{2}\)""")

    fun extractYear(name: String): Int? {
        val m = YEAR_RE.find(name) ?: return null
        return m.value.substring(1, 5).toIntOrNull()
    }

    fun yearOf(channel: Channel): Int? = channel.year.takeIf { it > 0 } ?: extractYear(channel.name)

    // ─── Filters ───

    /** IDs (streamUrls) the user has already watched/started. */
    fun seenStreamUrls(history: WatchHistoryManager): Set<String> =
        history.getContinueWatching().map { it.streamUrl }.toSet() +
            // also exclude finished items (getContinueWatching filters them out, so pull all)
            // We don't have a getAll() — so we just rely on the in-progress set for now.
            emptySet()

    fun isUnwatched(item: Channel, seenUrls: Set<String>) = item.streamUrl !in seenUrls

    /**
     * Heuristic match for titles / categories that are restricted or
     * adult-coded. Used when [com.vistacore.launcher.data.PrefsManager.hideRestrictedRatings]
     * is on to exclude obvious matches at browse time. This is catch-only —
     * IPTV providers rarely put MPAA codes in the catalog, so many R-rated
     * movies slip through; those get caught on the detail page instead.
     */
    private val RESTRICTED_NAME_RE = Regex(
        """\b(?:NC[- ]?17|TV[- ]?MA|XXX|18\+|R[- ]?rated|\[R\]|\(R\)|adult|erotic|porn)\b""",
        RegexOption.IGNORE_CASE
    )

    fun isRestrictedByName(item: Channel): Boolean =
        isRestrictedByName(item.name, item.category)

    /**
     * Name+category overload for callers that don't have a Channel object
     * (e.g. Home Continue Watching, where WatchEntry stores name only and
     * the underlying catalog item may not resolve when the entry is from
     * an Xtream/Jellyfin series episode that isn't itself in the cache).
     */
    fun isRestrictedByName(name: String, category: String = ""): Boolean =
        RESTRICTED_NAME_RE.containsMatchIn(name) ||
            (category.isNotEmpty() && RESTRICTED_NAME_RE.containsMatchIn(category))

    /** Filter a list to hide restricted/adult titles when [enabled] is true. */
    fun applyRestrictedFilter(items: List<Channel>, enabled: Boolean): List<Channel> =
        if (!enabled) items else items.filterNot { isRestrictedByName(it) }

    // ─── Movie shelves ───

    fun byMood(items: List<Channel>, mood: Mood, limit: Int = 30, exclude: Set<String> = emptySet()): List<Channel> =
        items.asSequence()
            .filter { it.streamUrl !in exclude }
            .filter { mood.pattern.containsMatchIn(it.name) || mood.pattern.containsMatchIn(it.category) }
            .take(limit)
            .toList()

    fun byDecade(items: List<Channel>, decade: Int, limit: Int = 30, exclude: Set<String> = emptySet()): List<Channel> {
        val lo = decade; val hi = decade + 9
        return items.asSequence()
            .filter { it.streamUrl !in exclude }
            .mapNotNull { ch -> yearOf(ch)?.let { y -> ch to y } }
            .filter { (_, y) -> y in lo..hi }
            .sortedByDescending { (_, y) -> y }
            .map { it.first }
            .take(limit)
            .toList()
    }

    fun justAdded(items: List<Channel>, limit: Int = 24, exclude: Set<String> = emptySet()): List<Channel> =
        items.asSequence()
            .filter { it.streamUrl !in exclude }
            .mapNotNull { ch -> yearOf(ch)?.let { y -> ch to y } }
            .sortedByDescending { (_, y) -> y }
            .map { it.first }
            .take(limit)
            .toList()

    /**
     * Pick a single random "Surprise Me" movie. Prefers titles from the last
     * decade (proxy for "we have a poster for it") and unwatched items.
     */
    fun surpriseMovie(items: List<Channel>, exclude: Set<String> = emptySet()): Channel? {
        val candidates = items.filter { it.streamUrl !in exclude && it.logoUrl.isNotBlank() }
        if (candidates.isEmpty()) return null
        val recent = candidates.filter { (yearOf(it) ?: 0) >= 2010 }
        val pool = if (recent.size >= 30) recent else candidates
        return pool.random()
    }

    // ─── Keyword similarity ───

    /**
     * Build a keyword-frequency profile from the user's watch history.
     * Each keyword that appears in a watched title's TMDB tag list gets a
     * count of 1 per watch — repeated tags across multiple watched titles
     * get higher weights, which is what we want: "spy" appearing in three
     * watched titles should outweigh a one-off "robot" tag.
     *
     * Returns an empty map when the user has no history or none of their
     * watched items have been enriched yet (cold start), in which case
     * [sortByKeywordSimilarity] short-circuits to alphabetical.
     */
    fun buildKeywordProfile(
        history: WatchHistoryManager,
        cache: Map<String, KeywordCache.Entry>
    ): Map<String, Int> {
        val watched = history.getRecent(30)
        if (watched.isEmpty() || cache.isEmpty()) return emptyMap()
        val profile = HashMap<String, Int>()
        for (entry in watched) {
            val kw = cache[entry.streamUrl]?.keywords ?: continue
            for (k in kw) profile[k] = (profile[k] ?: 0) + 1
        }
        return profile
    }

    /**
     * Re-order a category's items so the user sees personalized picks first.
     * Sort key, descending: (similarity score by overlapping keywords) →
     * (year, newer first) → (title, alphabetical). Already-watched items go
     * to the bottom so the row leads with fresh suggestions.
     *
     * Items without cached keywords get score 0 — they fall back to the
     * year/title tie-breakers, so the cold path stays predictable while
     * the catalog enriches.
     */
    fun sortByKeywordSimilarity(
        items: List<Channel>,
        profile: Map<String, Int>,
        cache: Map<String, KeywordCache.Entry>,
        seenUrls: Set<String>
    ): List<Channel> {
        if (profile.isEmpty()) return items
        return items.sortedWith(
            compareBy<Channel> { it.streamUrl in seenUrls } // unwatched first (false < true)
                .thenByDescending { ch ->
                    val kws = cache[ch.streamUrl]?.keywords ?: return@thenByDescending 0
                    var score = 0
                    for (k in kws) score += profile[k] ?: 0
                    score
                }
                .thenByDescending { yearOf(it) ?: 0 }
                .thenBy { it.name.lowercase() }
        )
    }

    // ─── Continue Watching mapping ───

    /**
     * Map watch history entries back to current Channel objects so the
     * Continue Watching row can show fresh metadata (poster, category)
     * even if the catalog has changed since playback.
     */
    fun continueWatching(history: WatchHistoryManager, allItems: List<Channel>, limit: Int = 12): List<Channel> {
        val byUrl = allItems.associateBy { it.streamUrl }
        return history.getContinueWatching()
            .mapNotNull { byUrl[it.streamUrl] }
            .take(limit)
    }

    /** Channel ids built from a watch-history entry carry this prefix so the
     *  browser click handlers know to resume the saved URL directly instead of
     *  routing back through a detail screen (which would re-resolve the URL). */
    const val CW_RESUME_PREFIX = "cw_resume:"

    /**
     * Build Continue Watching tiles straight from watch-history entries rather
     * than joining against the current catalog. [continueWatching] above does a
     * `streamUrl` lookup that silently drops anything not in the in-memory list
     * — which is *every* series episode (only series wrappers are cached) plus
     * any title the catalog has since dropped. That's why the Continue Watching
     * row never appeared inside the Movies/Shows/Kids browsers. A WatchEntry
     * already holds everything a poster needs (name, logo, the real playable
     * streamUrl), so we render from it directly and guarantee the row shows up.
     *
     * [keep] narrows entries to the calling section (movies vs shows vs kids).
     * Dead and restricted entries are dropped up front.
     */
    fun continueWatchingTiles(
        history: WatchHistoryManager,
        deadUrls: Set<String> = emptySet(),
        hideRestricted: Boolean = false,
        limit: Int = 12,
        keep: (WatchEntry) -> Boolean = { true },
    ): List<Channel> =
        history.getContinueWatching()
            .asSequence()
            .filter { it.streamUrl !in deadUrls }
            .filter { !hideRestricted || !isRestrictedByName(it.name) }
            .filter(keep)
            .map { entry ->
                Channel(
                    id = CW_RESUME_PREFIX + entry.streamUrl,
                    name = entry.name,
                    streamUrl = entry.streamUrl,
                    logoUrl = entry.logoUrl,
                    category = "Continue Watching",
                    contentType = if (entry.streamUrl.contains("/series/"))
                        ContentType.SERIES else ContentType.MOVIE,
                )
            }
            .take(limit)
            .toList()

    /** True when a saved entry looks like a series episode (Xtream `/series/`
     *  path) vs a movie. Used to route entries to the right browser section. */
    fun isSeriesEntry(entry: WatchEntry): Boolean = entry.streamUrl.contains("/series/")
}

/** Persists which mood shelves the user clicks into so we can promote favorites. */
class MoodPrefsManager(context: Context) {
    private val prefs = context.getSharedPreferences("vc_mood_prefs", Context.MODE_PRIVATE)

    fun bump(moodKey: String) {
        if (moodKey.isBlank()) return
        prefs.edit().putInt(moodKey, prefs.getInt(moodKey, 0) + 1).apply()
    }

    fun scoreOf(moodKey: String): Int = prefs.getInt(moodKey, 0)

    fun sortMoods(moods: List<Discovery.Mood>): List<Discovery.Mood> =
        moods.sortedByDescending { scoreOf(it.key) }
}

// ═══════════════════════════════════════════════════════════════════
// Kids: franchises, age bands, kid-tuned moods, tighter content gate.
// ═══════════════════════════════════════════════════════════════════

object KidsDiscovery {

    enum class AgeBand(val key: String, val label: String, val emoji: String, val sub: String) {
        TODDLER("toddler", "Little Kids", "🧸", "Under 5"),
        YOUNGER("younger", "Younger Kids", "🚒", "5-7"),
        OLDER("older",     "Older Kids",   "🦸", "8-12"),
        ALL("all",         "All Ages",     "⭐", "Show everything");

        companion object {
            fun fromKey(key: String?): AgeBand =
                values().firstOrNull { it.key == key } ?: ALL
            val ORDER: List<AgeBand> = listOf(TODDLER, YOUNGER, OLDER)
        }
    }

    data class Franchise(
        val key: String,
        val label: String,
        val emoji: String,
        val band: AgeBand,
        val tintHex: String,
        val pattern: Regex
    )

    data class KidsMood(
        val key: String,
        val label: String,
        val emoji: String,
        val tintHex: String,
        val pattern: Regex
    )

    // Block tokens that should never appear in Kids regardless of keyword match.
    val BLOCK_RE = Regex(
        """\b(adult|adults|18\+|nsfw|hentai|ecchi|yaoi|yuri|harem|seinen|josei|horror|gore|murder|crime|mafia|drug|narcos|erotic|xxx|porn)\b""",
        RegexOption.IGNORE_CASE
    )

    // Friendly-only allowlist. Tighter than the original "anime/family/junior/jr" mass-match.
    val ALLOW_RE = Regex(
        """\b(kids?|children|toddler|preschool|nursery|cartoon|toon|disney\s*junior|nick\s*jr|pbs\s*kids|sprout|cbeebies|playhouse|saturday\s*morning|family\s*film)\b""",
        RegexOption.IGNORE_CASE
    )

    val FRANCHISES: List<Franchise> = listOf(
        // Toddler (under 5)
        Franchise("bluey",        "Bluey",          "🐕", AgeBand.TODDLER, "#F7D33A", Regex("\\bbluey\\b", RegexOption.IGNORE_CASE)),
        Franchise("cocomelon",    "Cocomelon",      "🍉", AgeBand.TODDLER, "#34C759", Regex("\\bcocomelon\\b", RegexOption.IGNORE_CASE)),
        Franchise("peppa",        "Peppa Pig",      "🐷", AgeBand.TODDLER, "#FF85B3", Regex("\\bpeppa\\s*pig\\b", RegexOption.IGNORE_CASE)),
        Franchise("sesame",       "Sesame Street",  "🅰", AgeBand.TODDLER, "#E4002B", Regex("\\b(sesame\\s*street|elmo|big\\s*bird)\\b", RegexOption.IGNORE_CASE)),
        Franchise("daniel-tiger", "Daniel Tiger",   "🐯", AgeBand.TODDLER, "#FF7A3D", Regex("\\bdaniel\\s*tiger\\b", RegexOption.IGNORE_CASE)),
        Franchise("mickey",       "Mickey Mouse",   "🐭", AgeBand.TODDLER, "#222222", Regex("\\b(mickey\\s*mouse|minnie\\s*mouse|mickey\\s*and\\s*the\\s*roadster)\\b", RegexOption.IGNORE_CASE)),

        // Younger kids (5-7)
        Franchise("paw-patrol",   "Paw Patrol",     "🚒", AgeBand.YOUNGER, "#0AAEEF", Regex("\\bpaw\\s*patrol\\b", RegexOption.IGNORE_CASE)),
        Franchise("pj-masks",     "PJ Masks",       "🦉", AgeBand.YOUNGER, "#7D3CF2", Regex("\\bpj\\s*masks\\b", RegexOption.IGNORE_CASE)),
        Franchise("octonauts",    "Octonauts",      "🐙", AgeBand.YOUNGER, "#00B9D6", Regex("\\boctonauts\\b", RegexOption.IGNORE_CASE)),
        Franchise("doc-mcs",      "Doc McStuffins", "🩺", AgeBand.YOUNGER, "#FF5FA2", Regex("\\bdoc\\s*mcstuffins\\b", RegexOption.IGNORE_CASE)),
        Franchise("sofia",        "Sofia the First","👑", AgeBand.YOUNGER, "#9C27B0", Regex("\\bsofia\\s*the\\s*first\\b", RegexOption.IGNORE_CASE)),
        Franchise("frozen",       "Frozen",         "❄", AgeBand.YOUNGER, "#74C0FC", Regex("\\bfrozen(\\s*ii?| 2)?\\b", RegexOption.IGNORE_CASE)),
        Franchise("toy-story",    "Toy Story",      "🤠", AgeBand.YOUNGER, "#3E9EFF", Regex("\\btoy\\s*story\\b", RegexOption.IGNORE_CASE)),
        Franchise("cars",         "Cars",           "🏎", AgeBand.YOUNGER, "#FF3B30", Regex("\\bcars\\b(?!\\s*\\d{4})", RegexOption.IGNORE_CASE)),
        Franchise("nemo-dory",    "Finding Nemo & Dory", "🐠", AgeBand.YOUNGER, "#FF7A00", Regex("\\bfinding\\s*(nemo|dory)\\b", RegexOption.IGNORE_CASE)),
        Franchise("shrek",        "Shrek",          "🟢", AgeBand.YOUNGER, "#5CB85C", Regex("\\bshrek\\b", RegexOption.IGNORE_CASE)),
        Franchise("madagascar",   "Madagascar",     "🦒", AgeBand.YOUNGER, "#FBB040", Regex("\\bmadagascar\\b", RegexOption.IGNORE_CASE)),
        Franchise("ice-age",      "Ice Age",        "🦣", AgeBand.YOUNGER, "#74C0FC", Regex("\\bice\\s*age\\b", RegexOption.IGNORE_CASE)),
        Franchise("tom-jerry",    "Tom and Jerry",  "😼", AgeBand.YOUNGER, "#FFB300", Regex("\\btom\\s*(and|&|\\+)\\s*jerry\\b", RegexOption.IGNORE_CASE)),
        Franchise("looney",       "Looney Tunes",   "🐰", AgeBand.YOUNGER, "#FF5722", Regex("\\b(looney\\s*tunes|bugs\\s*bunny)\\b", RegexOption.IGNORE_CASE)),
        Franchise("scooby",       "Scooby-Doo",     "🐾", AgeBand.YOUNGER, "#8BC34A", Regex("\\bscooby[-\\s]*doo\\b", RegexOption.IGNORE_CASE)),

        // Older kids (8-12)
        Franchise("incredibles",  "The Incredibles","🦸", AgeBand.OLDER, "#E63B3B", Regex("\\bincredibles\\b", RegexOption.IGNORE_CASE)),
        Franchise("kung-fu",      "Kung Fu Panda",  "🐼", AgeBand.OLDER, "#FBB040", Regex("\\bkung\\s*fu\\s*panda\\b", RegexOption.IGNORE_CASE)),
        Franchise("dragon",       "How to Train Your Dragon", "🐲", AgeBand.OLDER, "#5D6D7E", Regex("\\bhow\\s*to\\s*train\\s*your\\s*dragon\\b", RegexOption.IGNORE_CASE)),
        Franchise("spider",       "Spider-Man",     "🕷", AgeBand.OLDER, "#E63B3B", Regex("\\bspider[-\\s]*man\\b", RegexOption.IGNORE_CASE)),
        Franchise("star-wars",    "Star Wars",      "⭐", AgeBand.OLDER, "#FFD60A", Regex("\\bstar\\s*wars\\b", RegexOption.IGNORE_CASE)),
        Franchise("marvel",       "Marvel",         "🦸", AgeBand.OLDER, "#ED1D24", Regex("\\b(avengers|thor|iron\\s*man|captain\\s*america|hulk|black\\s*panther|guardians\\s*of\\s*the\\s*galaxy|x[-\\s]*men)\\b", RegexOption.IGNORE_CASE)),
        Franchise("batman",       "Batman",         "🦇", AgeBand.OLDER, "#3A3A3A", Regex("\\bbatman\\b", RegexOption.IGNORE_CASE)),
        Franchise("pokemon",      "Pokémon",        "⚡", AgeBand.OLDER, "#F7D33A", Regex("\\bpok[eé]mon\\b", RegexOption.IGNORE_CASE)),
        Franchise("lego",         "LEGO",           "🧱", AgeBand.OLDER, "#F7D33A", Regex("\\blego\\b", RegexOption.IGNORE_CASE)),
        Franchise("power-rangers","Power Rangers",  "🤖", AgeBand.OLDER, "#9C27B0", Regex("\\bpower\\s*rangers\\b", RegexOption.IGNORE_CASE)),
    )

    val MOODS: List<KidsMood> = listOf(
        KidsMood("animals",   "Animals",       "🐶", "#8BC34A", Regex("\\b(cat|kitten|dog|puppy|bear|panda|fish|whale|shark|lion|tiger|elephant|monkey|horse|farm|safari|jungle|zoo|wild|nature|wildlife|pet|animal)\\b", RegexOption.IGNORE_CASE)),
        KidsMood("vehicles",  "Cars & Trucks", "🚒", "#0AAEEF", Regex("\\b(car|truck|train|plane|race|wheels|motor|tractor|fire\\s*engine|excavator|digger|monster\\s*truck|airplane|boat|ship|rocket)\\b", RegexOption.IGNORE_CASE)),
        KidsMood("sing",      "Sing-Along",    "🎵", "#FF85B3", Regex("\\b(sing|song|musical|melody|music|nursery\\s*rhyme|lullaby|dance)\\b", RegexOption.IGNORE_CASE)),
        KidsMood("funny",     "Funny",         "🎈", "#FFD60A", Regex("\\b(funny|silly|laugh|comedy|prank|wacky)\\b", RegexOption.IGNORE_CASE)),
        KidsMood("bedtime",   "Bedtime",       "🌙", "#7D3CF2", Regex("\\b(bedtime|sleep|lullaby|goodnight|good\\s*night|calm|sleepy|moon|star|dream)\\b", RegexOption.IGNORE_CASE)),
        KidsMood("learning",  "Learning",      "📚", "#34C759", Regex("\\b(educational|learn|abc|alphabet|count|counting|123|number|school|word|read|reading|science|math)\\b", RegexOption.IGNORE_CASE)),
        KidsMood("adventure", "Adventure",     "🏰", "#FF7A3D", Regex("\\b(adventure|quest|explorer|discover|journey|treasure|pirate|knight|castle|jungle)\\b", RegexOption.IGNORE_CASE)),
    )

    /** Returns the franchise this item belongs to, or null. */
    fun matchFranchise(item: Channel): Franchise? =
        FRANCHISES.firstOrNull { it.pattern.containsMatchIn(item.name) }

    /** Tighter kids classifier. Blocks adult tokens, requires franchise OR allowlist match. */
    fun isKidsItem(item: Channel): Boolean {
        val text = "${item.name} ${item.category}"
        if (BLOCK_RE.containsMatchIn(text)) return false
        if (matchFranchise(item) != null) return true
        return ALLOW_RE.containsMatchIn(text)
    }

    fun bandOf(item: Channel): AgeBand {
        matchFranchise(item)?.let { return it.band }
        val text = "${item.name} ${item.category}".lowercase()
        if (Regex("\\b(toddler|preschool|nursery|baby|infant)\\b").containsMatchIn(text)) return AgeBand.TODDLER
        if (Regex("\\b(disney\\s*junior|nick\\s*jr|pbs\\s*kids|sprout|cbeebies|playhouse)\\b").containsMatchIn(text)) return AgeBand.YOUNGER
        return AgeBand.OLDER
    }

    /** Returns true if `item` is appropriate for the chosen band. */
    fun passesBand(item: Channel, band: AgeBand): Boolean {
        if (band == AgeBand.ALL) return true
        val itemBand = bandOf(item)
        return AgeBand.ORDER.indexOf(itemBand) <= AgeBand.ORDER.indexOf(band)
    }

    fun byFranchise(items: List<Channel>, franchise: Franchise, band: AgeBand, limit: Int = 18): List<Channel> =
        items.asSequence()
            .filter { passesBand(it, band) }
            .filter { franchise.pattern.containsMatchIn(it.name) }
            .take(limit)
            .toList()

    fun byMood(items: List<Channel>, mood: KidsMood, band: AgeBand, limit: Int = 18, exclude: Set<String> = emptySet()): List<Channel> =
        items.asSequence()
            .filter { it.streamUrl !in exclude }
            .filter { passesBand(it, band) }
            .filter { mood.pattern.containsMatchIn(it.name) || mood.pattern.containsMatchIn(it.category) }
            .take(limit)
            .toList()

    /**
     * All items within the chosen band. Assumes [items] was already
     * filtered through [isKidsItem] when loaded — e.g. KidsBrowserActivity
     * caches the kids-filtered catalog once per launch via filterToKids().
     * Running isKidsItem again per shelf was the main driver of the
     * visible lag when switching bands on large catalogs.
     */
    fun all(items: List<Channel>, band: AgeBand): List<Channel> =
        if (band == AgeBand.ALL) items else items.filter { passesBand(it, band) }
}

/** Persists the user's chosen Kids age band across launches. */
class KidsBandPrefs(context: Context) {
    private val prefs = context.getSharedPreferences("vc_kids_band", Context.MODE_PRIVATE)
    fun get(): KidsDiscovery.AgeBand =
        KidsDiscovery.AgeBand.fromKey(prefs.getString("band", null))
    fun set(band: KidsDiscovery.AgeBand) {
        prefs.edit().putString("band", band.key).apply()
    }
}
