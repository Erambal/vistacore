package com.vistacore.launcher.iptv

import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

data class UpcomingGame(
    val sport: String,
    val league: String,
    val homeTeam: String,
    val awayTeam: String,
    val homeLogo: String,
    val awayLogo: String,
    val startTime: Date,
    val status: String,
    val homeScore: String = "",
    val awayScore: String = "",
    val venue: String = "",
    val broadcast: String = ""
) {
    val isLive: Boolean get() = status == "in" || status == "halftime"
    val isUpcoming: Boolean get() = status == "pre"
    val isFinished: Boolean get() = status == "post"

    val displayTime: String
        get() {
            val fmt = SimpleDateFormat("h:mm a", Locale.getDefault())
            return fmt.format(startTime)
        }

    val displayDate: String
        get() {
            val fmt = SimpleDateFormat("EEE, MMM d", Locale.getDefault())
            return fmt.format(startTime)
        }
}

class SportsDataManager {

    private val client = TlsCompat.applyTrustAll(OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS))
        .build()

    private val gson = Gson()

    private val sportEndpoints = mapOf(
        "basketball" to "basketball/nba",
        "football" to "football/nfl",
        "baseball" to "baseball/mlb",
        "hockey" to "hockey/nhl",
        "soccer" to "soccer/usa.1"
    )

    private val sportLeagues = mapOf(
        "basketball" to "NBA",
        "football" to "NFL",
        "baseball" to "MLB",
        "hockey" to "NHL",
        "soccer" to "MLS"
    )

    suspend fun getUpcomingGames(enabledSports: Set<String>): List<UpcomingGame> = coroutineScope {
        val games = enabledSports
            .filter { it in sportEndpoints }
            .map { sport ->
                async(Dispatchers.IO) {
                    try {
                        fetchGamesForSport(sport)
                    } catch (_: Exception) {
                        emptyList()
                    }
                }
            }
            .awaitAll()
            .flatten()
            .sortedBy { it.startTime }

        // Prioritize live games first, then upcoming, then finished
        val live = games.filter { it.isLive }
        val upcoming = games.filter { it.isUpcoming }
        val finished = games.filter { it.isFinished }
        live + upcoming + finished
    }

    private fun fetchGamesForSport(sport: String): List<UpcomingGame> {
        val endpoint = sportEndpoints[sport] ?: return emptyList()
        val league = sportLeagues[sport] ?: sport.uppercase()
        val url = "https://site.api.espn.com/apis/site/v2/sports/$endpoint/scoreboard"

        val request = Request.Builder().url(url).build()
        val body = client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return emptyList()
            response.body?.string() ?: return emptyList()
        }
        return parseScoreboard(body, sport, league)
    }

    private fun parseScoreboard(json: String, sport: String, league: String): List<UpcomingGame> {
        val games = mutableListOf<UpcomingGame>()

        try {
            val root = gson.fromJson(json, JsonObject::class.java)
            val events = root.getAsJsonArray("events") ?: return emptyList()

            for (eventEl in events) {
                val event = eventEl.asJsonObject
                val competitions = event.getAsJsonArray("competitions") ?: continue

                for (compEl in competitions) {
                    val comp = compEl.asJsonObject
                    val competitors = comp.getAsJsonArray("competitors") ?: continue
                    if (competitors.size() < 2) continue

                    var homeTeam = ""
                    var awayTeam = ""
                    var homeLogo = ""
                    var awayLogo = ""
                    var homeScore = ""
                    var awayScore = ""

                    for (teamEl in competitors) {
                        val team = teamEl.asJsonObject
                        val isHome = team.get("homeAway")?.asString == "home"
                        val teamInfo = team.getAsJsonObject("team")
                        val name = teamInfo?.get("displayName")?.asString ?: ""
                        val logo = teamInfo?.get("logo")?.asString ?: ""
                        val score = team.get("score")?.asString ?: ""

                        if (isHome) {
                            homeTeam = name
                            homeLogo = logo
                            homeScore = score
                        } else {
                            awayTeam = name
                            awayLogo = logo
                            awayScore = score
                        }
                    }

                    val statusObj = comp.getAsJsonObject("status")
                    val statusType = statusObj?.getAsJsonObject("type")
                    val statusState = statusType?.get("state")?.asString ?: "pre"

                    val dateStr = comp.get("date")?.asString ?: event.get("date")?.asString ?: ""
                    val startTime = parseEspnDate(dateStr)

                    val venue = comp.getAsJsonObject("venue")?.get("fullName")?.asString ?: ""

                    var broadcast = ""
                    val broadcasts = comp.getAsJsonArray("broadcasts")
                    if (broadcasts != null && broadcasts.size() > 0) {
                        val names = broadcasts[0]?.asJsonObject?.getAsJsonArray("names")
                        if (names != null && names.size() > 0) {
                            broadcast = names[0].asString
                        }
                    }

                    games.add(
                        UpcomingGame(
                            sport = sport,
                            league = league,
                            homeTeam = homeTeam,
                            awayTeam = awayTeam,
                            homeLogo = homeLogo,
                            awayLogo = awayLogo,
                            startTime = startTime,
                            status = statusState,
                            homeScore = homeScore,
                            awayScore = awayScore,
                            venue = venue,
                            broadcast = broadcast
                        )
                    )
                }
            }
        } catch (_: Exception) { }

        return games
    }

    private fun parseEspnDate(dateStr: String): Date {
        return try {
            val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm'Z'", Locale.US)
            fmt.timeZone = TimeZone.getTimeZone("UTC")
            fmt.parse(dateStr) ?: Date()
        } catch (_: Exception) {
            Date()
        }
    }
}
