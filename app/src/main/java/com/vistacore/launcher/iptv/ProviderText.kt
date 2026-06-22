package com.vistacore.launcher.iptv

/**
 * Cleans provider "noise" out of channel names and category labels so the
 * launcher shows readable text. Shared by [M3UParser] (M3U sources) and
 * [XtreamClient] (Xtream/Dispatcharr sources) so both behave identically.
 *
 * The fixesto/Dispatcharr feed prefixes names and categories with tags
 * ("US| ", "PRIME| ", "CITY| ", "US (ESPN+ 001) | "), wraps headers in hashes
 * ("##### PRIME #####") and decorates everything with superscript unicode
 * ("ᴿᴬᵂ ⁶⁰ᶠᵖˢ", "ᶜᶦᵗʸ ᴴᴰ") and "VIP" tags. We strip all of that for display.
 */
object ProviderText {

    // Repeated leading "XXX| " tag on a channel NAME (US|, PRIME|, CITY|,
    // NFL TEAMS|, EN|, D+|, A+|, …). Token is a short run of tag-ish characters
    // (letters/digits and the punctuation providers use in tags: + & / . - _)
    // up to 15 chars, so we don't eat the real channel identity that follows.
    // The `+`/`&` etc. matter — "D+| ESPN" used to survive because the old
    // class excluded them.
    private val reNameTag = Regex("""^\s*[A-Za-z0-9+&/._\- ]{1,15}\|\s*""")

    // Leading region/provider tag on a CATEGORY ("US| ", "EN| ", "D+| "). 24/7
    // groups use a space, not a pipe, so this leaves "24/7 …" untouched.
    private val reRegionTag = Regex("""^\s*[A-Za-z0-9+&]{1,6}\|\s*""")

    // Provider/language tag separated by " - " instead of a pipe, the way this
    // feed actually labels VOD: "EN - Proud (PL)", "D+ - 22 vs. Earth (2021)",
    // "4K - Foo". A short (≤4) token of letters/digits/'+'; the caller requires
    // it to contain a LETTER so a real title like "24 - Live Another Day" and
    // a hyphenated name like "Spider-Man" (no surrounding spaces) are left alone.
    private val reDashTag = Regex("""^\s*([A-Z0-9+]{1,4})\s+-\s+""")

    // Region prefix on a NAME that may carry a parenthetical feed id before the
    // pipe, e.g. "US (ESPN+ 001) | Auckland vs …" -> "Auckland vs …". Anchored
    // to a known region code so we only swallow up to the FIRST pipe when the
    // name genuinely starts with a region tag (not real titles containing "|").
    private val reRegionPrefix = Regex(
        """^\s*(?:US|USA|UK|GB|CA|AU|NZ|IE|FR|DE|ES|IT|PT|NL|BE|SE|NO|DK|FI|PL|BR|MX|AR|IN|PK|TR|EU|LA)\b[^|]*\|\s*"""
    )

    // Decorative codepoints: modifier/superscript letters, super/subscript
    // digits, and '#' border characters.
    private val reDecorations = Regex("[#\\u02B0-\\u02FF\\u1D00-\\u1DBF\\u2070-\\u209F]")
    private val reVip = Regex("""\bVIP\b""", RegexOption.IGNORE_CASE)
    private val reDirecTv = Regex("""DIREC\s*TV""", RegexOption.IGNORE_CASE)
    private val reMultiSpace = Regex("""\s{2,}""")
    // Leftover separators stranded after stripping (e.g. "DirecTV /"). Keeps
    // meaningful trailing "+"/"&" ("AMC +", "BET+").
    private val reStrandedSep = Regex("""^[\s/\-|]+|[\s/\-|]+$""")

    // Tokens kept fully uppercase when title-casing ALL-CAPS provider text.
    private val acronyms = setOf(
        "US", "USA", "UK", "TV", "HD", "HQ", "4K", "PPV", "VOD",
        "ESPN", "NFL", "NHL", "NBA", "MLB", "MLS", "NCAA", "NCAAF", "NCAAB",
        "PGA", "UFC", "WWE", "AEW", "NASCAR", "F1",
        "HBO", "AMC", "BET", "TBS", "TNT", "FX", "FXX", "CNN", "CNBC", "MSNBC",
        "CBS", "NBC", "ABC", "FOX", "PBS", "MTV", "TLC", "IFC", "MGM", "CW",
        "ION", "EPIX", "SHO", "STARZ", "MAX", "PAC", "SEC", "BTN", "ACC",
        "USA", "WE", "OWN", "AXS", "POP", "NHK", "RT", "PBA",
        "BBC", "CMT", "AE", "VH", "HGTV", "TCM", "TMC", "ID", "SD", "UHD", "FHD"
    )

    /**
     * Channel display name: strip every leading "XXX| " provider tag (US|,
     * PRIME|, CITY|, NFL TEAMS|, …), drop decorative junk and tidy casing.
     * "PRIME| 5STARMAX EAST ᴿᴬᵂ" -> "5Starmax East".
     */
    fun cleanName(raw: String): String {
        val cleaned = tidy(stripLeadingTags(raw))
        // Bare-tag entries ("NHL LIVE|", "MLB 17 |") would clean to nothing —
        // keep the tag text instead of showing a blank name.
        return if (cleaned.isBlank()) tidy(raw) else cleaned
    }

    /** Peel a region prefix (possibly with a "(ESPN+ 001)" feed id) then any
     *  remaining "XXX| " provider tags ("PRIME| CITY| Foo" -> "Foo"). */
    private fun stripLeadingTags(raw: String): String {
        var s = reRegionPrefix.replaceFirst(raw, "")
        while (true) {
            val pipe = reNameTag.find(s)
            if (pipe != null) { s = s.substring(pipe.range.last + 1); continue }
            val dash = reDashTag.find(s)
            if (dash != null && dash.groupValues[1].any { it.isLetter() }) {
                s = s.substring(dash.range.last + 1); continue
            }
            break
        }
        return s
    }

    /**
     * Category label: drop the leading region tag ("US| ") but keep a "24/7"
     * marker, then strip decorations and tidy casing.
     * "US| ESPN PLUS" -> "ESPN Plus"; "24/7 MOVIES & SERIES" -> "24/7 Movies & Series".
     */
    fun cleanCategory(raw: String): String {
        val s = reRegionTag.replaceFirst(raw, "")
        val cleaned = tidy(s)
        return if (cleaned.isBlank()) "Uncategorized" else cleaned
    }

    /**
     * General display cleanup for any user-facing text that didn't go through
     * [cleanName]/[cleanCategory] at parse time — most importantly EPG program
     * titles, which arrive raw ("##### ENTERTAINMENT #####", "ᴿᴬᵂ ⁶⁰ᶠᵖˢ"). Strips
     * the hash borders / superscript decorations and tidies casing without the
     * aggressive leading-tag peeling that channel names need.
     */
    fun cleanDisplay(raw: String): String {
        val cleaned = tidy(stripLeadingTags(raw))
        return if (cleaned.isBlank()) tidy(raw).ifBlank { raw.trim() } else cleaned
    }

    /** Shared finishing pass: DirecTV fix, decorations/VIP removal, casing. */
    private fun tidy(input: String): String {
        var s = reDirecTv.replace(input, "DirecTV")
        s = reDecorations.replace(s, " ")
        s = reVip.replace(s, " ")
        s = reMultiSpace.replace(s, " ").trim()
        s = reStrandedSep.replace(s, "").trim()
        return titleCase(s)
    }

    /**
     * Title-case ALL-CAPS provider text while preserving known acronyms
     * (ESPN, NFL, HBO …) and the special-cased "DirecTV". Tokens that already
     * contain lowercase are left as-is so we never mangle real mixed-case names.
     */
    private fun titleCase(text: String): String {
        if (text.any { it.isLowerCase() }) return text
        return text.split(' ').joinToString(" ") { token ->
            when {
                token.isEmpty() -> token
                // Match on letters only so "BET+" / "ESPN," still resolve.
                acronyms.contains(token.filter { it.isLetter() }.uppercase()) -> token.uppercase()
                else -> {
                    // Capitalize the first letter of each alphabetic run, so
                    // "kids/family" -> "Kids/Family", "5starmax" -> "5Starmax".
                    val sb = StringBuilder(token.length)
                    var startOfRun = true
                    for (ch in token.lowercase()) {
                        if (ch.isLetter()) {
                            sb.append(if (startOfRun) ch.uppercaseChar() else ch)
                            startOfRun = false
                        } else {
                            sb.append(ch)
                            startOfRun = true
                        }
                    }
                    sb.toString()
                }
            }
        }
    }
}
