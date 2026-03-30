package com.vistacore.launcher.data

import android.content.Context
import android.content.SharedPreferences

class PrefsManager(context: Context) {

    companion object {
        private const val PREFS_NAME = "vistacore_prefs"
        private const val KEY_SOURCE_TYPE = "source_type"
        private const val KEY_M3U_URL = "m3u_url"
        private const val KEY_XTREAM_SERVER = "xtream_server"
        private const val KEY_XTREAM_USERNAME = "xtream_username"
        private const val KEY_XTREAM_PASSWORD = "xtream_password"
        private const val KEY_LAST_CHANNEL = "last_channel"
        private const val KEY_FAVORITE_CHANNELS = "favorite_channels"
        private const val KEY_EPG_URL = "epg_url"
        private const val KEY_RELAY_SERVER = "relay_server_url"
        private const val KEY_SCREENSAVER_TIMEOUT = "screensaver_timeout"
        private const val KEY_AUTO_UPDATE = "auto_update_enabled"
        private const val KEY_CATCHUP_TYPE = "catchup_type"
        private const val KEY_SPORTS_TYPES = "sports_types"
        private const val KEY_ENABLED_APPS = "enabled_apps"
        private const val KEY_AUTO_LAUNCH = "auto_launch_on_boot"
        private const val KEY_KIDS_ENABLED = "kids_section_enabled"
        private const val KEY_SHOW_EPG_IN_LIST = "show_epg_in_channel_list"
        private const val KEY_SETUP_COMPLETE = "setup_complete"
        private const val KEY_LOAD_MOVIES = "load_movies_enabled"
        private const val KEY_LOAD_SHOWS = "load_shows_enabled"
        private const val KEY_LOAD_KIDS = "load_kids_enabled"
        private const val KEY_DISPATCHARR_API_KEY = "dispatcharr_api_key"
        private const val KEY_UI_SCALE = "ui_scale"
        private const val KEY_SETTINGS_PIN = "settings_pin"
        private const val KEY_PIN_ENABLED = "settings_pin_enabled"
        private const val KEY_APP_UPDATE_REPO = "app_update_github_repo"
        private const val KEY_APP_AUTO_UPDATE = "app_auto_update_enabled"
        private const val KEY_APP_LANGUAGE = "app_language"
        private const val KEY_PREFERRED_AUDIO_LANG = "preferred_audio_language"
        private const val KEY_PREFERRED_SUBTITLE_LANG = "preferred_subtitle_language"
        private const val KEY_OPENSUBTITLES_API_KEY = "opensubtitles_api_key"

        const val SOURCE_M3U = 0
        const val SOURCE_XTREAM = 1

        val ALL_SPORTS = setOf("basketball", "football", "baseball", "hockey", "soccer")

        /** Default ordered list of app IDs shown on the home screen */
        val DEFAULT_APPS = listOf("IPTV", "MOVIES", "TV_SHOWS", "KIDS", "ESPN", "ROKU", "DISNEY_PLUS")
    }

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var sourceType: Int
        get() = prefs.getInt(KEY_SOURCE_TYPE, SOURCE_M3U)
        set(value) = prefs.edit().putInt(KEY_SOURCE_TYPE, value).apply()

    var m3uUrl: String
        get() = prefs.getString(KEY_M3U_URL, "") ?: ""
        set(value) = prefs.edit().putString(KEY_M3U_URL, value).apply()

    var xtreamServer: String
        get() = prefs.getString(KEY_XTREAM_SERVER, "") ?: ""
        set(value) = prefs.edit().putString(KEY_XTREAM_SERVER, value).apply()

    var xtreamUsername: String
        get() = prefs.getString(KEY_XTREAM_USERNAME, "") ?: ""
        set(value) = prefs.edit().putString(KEY_XTREAM_USERNAME, value).apply()

    var xtreamPassword: String
        get() = prefs.getString(KEY_XTREAM_PASSWORD, "") ?: ""
        set(value) = prefs.edit().putString(KEY_XTREAM_PASSWORD, value).apply()

    var dispatcharrApiKey: String
        get() = prefs.getString(KEY_DISPATCHARR_API_KEY, "25h_7mBeuBnxOdGYlLIKxLaoU9UMsMbQ0AhT0524XbgFMG0nXjUeGg") ?: "25h_7mBeuBnxOdGYlLIKxLaoU9UMsMbQ0AhT0524XbgFMG0nXjUeGg"
        set(value) = prefs.edit().putString(KEY_DISPATCHARR_API_KEY, value).apply()

    var lastChannel: String
        get() = prefs.getString(KEY_LAST_CHANNEL, "") ?: ""
        set(value) = prefs.edit().putString(KEY_LAST_CHANNEL, value).apply()

    var epgUrl: String
        get() = prefs.getString(KEY_EPG_URL, "") ?: ""
        set(value) = prefs.edit().putString(KEY_EPG_URL, value).apply()

    var relayServerUrl: String
        get() = prefs.getString(KEY_RELAY_SERVER, "https://pair.fixesto.com") ?: "https://pair.fixesto.com"
        set(value) = prefs.edit().putString(KEY_RELAY_SERVER, value).apply()

    /** Screen saver timeout in minutes. 0 = disabled. Default 10 minutes. */
    var screenSaverTimeout: Int
        get() = prefs.getInt(KEY_SCREENSAVER_TIMEOUT, 10)
        set(value) = prefs.edit().putInt(KEY_SCREENSAVER_TIMEOUT, value).apply()

    var autoUpdateEnabled: Boolean
        get() = prefs.getBoolean(KEY_AUTO_UPDATE, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_UPDATE, value).apply()

    /** Whether to preload movies during splash. Default true. */
    var loadMoviesEnabled: Boolean
        get() = prefs.getBoolean(KEY_LOAD_MOVIES, true)
        set(value) = prefs.edit().putBoolean(KEY_LOAD_MOVIES, value).apply()

    /** Whether to preload shows during splash. Default true. */
    var loadShowsEnabled: Boolean
        get() = prefs.getBoolean(KEY_LOAD_SHOWS, true)
        set(value) = prefs.edit().putBoolean(KEY_LOAD_SHOWS, value).apply()

    /** Whether to preload kids during splash. Default true. */
    var loadKidsEnabled: Boolean
        get() = prefs.getBoolean(KEY_LOAD_KIDS, true)
        set(value) = prefs.edit().putBoolean(KEY_LOAD_KIDS, value).apply()

    /** Show EPG program info in the Live TV channel list. Default true. */
    var showEpgInChannelList: Boolean
        get() = prefs.getBoolean(KEY_SHOW_EPG_IN_LIST, true)
        set(value) = prefs.edit().putBoolean(KEY_SHOW_EPG_IN_LIST, value).apply()

    /** Kids section enabled. Default true. */
    var kidsEnabled: Boolean
        get() = prefs.getBoolean(KEY_KIDS_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_KIDS_ENABLED, value).apply()

    /** Auto-launch VistaCore on device boot. Default true. */
    var autoLaunchOnBoot: Boolean
        get() = prefs.getBoolean(KEY_AUTO_LAUNCH, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_LAUNCH, value).apply()

    /** Catch-up URL pattern type: xtream, flussonic, append, shift */
    var catchUpType: String
        get() = prefs.getString(KEY_CATCHUP_TYPE, "xtream") ?: "xtream"
        set(value) = prefs.edit().putString(KEY_CATCHUP_TYPE, value).apply()

    var favoriteChannels: Set<String>
        get() = prefs.getStringSet(KEY_FAVORITE_CHANNELS, emptySet()) ?: emptySet()
        set(value) = prefs.edit().putStringSet(KEY_FAVORITE_CHANNELS, value).apply()

    /** Ordered list of enabled app IDs on the home screen, stored as comma-separated string. */
    var enabledApps: List<String>
        get() {
            val raw = prefs.getString(KEY_ENABLED_APPS, null)
            if (raw.isNullOrBlank()) return DEFAULT_APPS
            val saved = raw.split(",").filter { it.isNotBlank() }
            // Auto-add any new app IDs that were added after the user last saved
            val missing = DEFAULT_APPS.filter { it !in saved }
            return if (missing.isEmpty()) saved else saved + missing
        }
        set(value) = prefs.edit().putString(KEY_ENABLED_APPS, value.joinToString(",")).apply()

    /** Selected sport types for the upcoming games section. Defaults to all. */
    var sportsTypes: Set<String>
        get() = prefs.getStringSet(KEY_SPORTS_TYPES, ALL_SPORTS) ?: ALL_SPORTS
        set(value) = prefs.edit().putStringSet(KEY_SPORTS_TYPES, value).apply()

    val isSetupComplete: Boolean
        get() = prefs.getBoolean(KEY_SETUP_COMPLETE, false)

    fun setSetupComplete() = prefs.edit().putBoolean(KEY_SETUP_COMPLETE, true).apply()

    fun addFavorite(channelId: String) {
        favoriteChannels = favoriteChannels + channelId
    }

    fun removeFavorite(channelId: String) {
        favoriteChannels = favoriteChannels - channelId
    }

    fun isFavorite(channelId: String): Boolean = channelId in favoriteChannels

    /** UI scale: 0 = Small, 1 = Medium (default), 2 = Large */
    var uiScale: Int
        get() = prefs.getInt(KEY_UI_SCALE, 1)
        set(value) = prefs.edit().putInt(KEY_UI_SCALE, value).apply()

    /** 4-digit PIN to lock Settings access */
    var settingsPin: String
        get() = prefs.getString(KEY_SETTINGS_PIN, "") ?: ""
        set(value) = prefs.edit().putString(KEY_SETTINGS_PIN, value).apply()

    /** Whether the PIN lock is enabled */
    var pinEnabled: Boolean
        get() = prefs.getBoolean(KEY_PIN_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_PIN_ENABLED, value).apply()

    /** GitHub repo to check for app updates (owner/repo format). */
    var appUpdateRepo: String
        get() = prefs.getString(KEY_APP_UPDATE_REPO, "Erambal/vistacore") ?: "Erambal/vistacore"
        set(value) = prefs.edit().putString(KEY_APP_UPDATE_REPO, value).apply()

    /** Whether automatic app update checks are enabled. Default true. */
    var appAutoUpdateEnabled: Boolean
        get() = prefs.getBoolean(KEY_APP_AUTO_UPDATE, true)
        set(value) = prefs.edit().putBoolean(KEY_APP_AUTO_UPDATE, value).apply()

    /** App UI language code (e.g. "en", "es", "fr"). Default "en". */
    var appLanguage: String
        get() = prefs.getString(KEY_APP_LANGUAGE, "en") ?: "en"
        set(value) = prefs.edit().putString(KEY_APP_LANGUAGE, value).apply()

    /** Preferred audio track language code. Empty = use stream default. */
    var preferredAudioLanguage: String
        get() = prefs.getString(KEY_PREFERRED_AUDIO_LANG, "") ?: ""
        set(value) = prefs.edit().putString(KEY_PREFERRED_AUDIO_LANG, value).apply()

    /** Preferred subtitle language code. Empty = subtitles off. */
    var preferredSubtitleLanguage: String
        get() = prefs.getString(KEY_PREFERRED_SUBTITLE_LANG, "") ?: ""
        set(value) = prefs.edit().putString(KEY_PREFERRED_SUBTITLE_LANG, value).apply()

    /** OpenSubtitles.com API key for subtitle search. */
    var openSubtitlesApiKey: String
        get() = prefs.getString(KEY_OPENSUBTITLES_API_KEY, "") ?: ""
        set(value) = prefs.edit().putString(KEY_OPENSUBTITLES_API_KEY, value).apply()

    fun hasIptvConfig(): Boolean {
        return when (sourceType) {
            SOURCE_M3U -> m3uUrl.isNotBlank()
            SOURCE_XTREAM -> xtreamServer.isNotBlank() && xtreamUsername.isNotBlank()
            else -> false
        }
    }
}
