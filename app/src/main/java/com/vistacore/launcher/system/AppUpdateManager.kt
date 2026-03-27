package com.vistacore.launcher.system

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.util.Log
import androidx.core.content.FileProvider
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Manages checking for app updates via GitHub Releases, downloading APKs,
 * and triggering installation.
 *
 * Checks: https://api.github.com/repos/{owner}/{repo}/releases/latest
 * Expects a release with:
 *   - tag_name like "v1.0.0" (parsed for version comparison)
 *   - An attached .apk asset
 *   - body as changelog text
 */
class AppUpdateManager(private val context: Context) {

    companion object {
        private const val TAG = "AppUpdate"
        private const val APK_FILE_NAME = "vistacore-update.apk"
        private const val PREFS_NAME = "vistacore_prefs"
        private const val KEY_LAST_UPDATE_CHECK = "last_app_update_check"
        private const val KEY_AVAILABLE_VERSION = "available_update_version"
        private const val KEY_AVAILABLE_CHANGELOG = "available_update_changelog"

        const val DEFAULT_GITHUB_REPO = "Erambal/vistacore"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /** Mirrors the GitHub Releases API response (only fields we need). */
    data class GitHubRelease(
        @SerializedName("tag_name") val tagName: String,
        @SerializedName("name") val name: String?,
        @SerializedName("body") val body: String?,
        @SerializedName("assets") val assets: List<GitHubAsset>
    )

    data class GitHubAsset(
        @SerializedName("name") val name: String,
        @SerializedName("browser_download_url") val downloadUrl: String,
        @SerializedName("size") val size: Long
    )

    data class UpdateInfo(
        val versionName: String,
        val apkUrl: String,
        val changelog: String = "",
        val apkSizeMb: String = ""
    )

    data class UpdateCheckResult(
        val available: Boolean,
        val info: UpdateInfo? = null,
        val currentVersionName: String = "",
        val error: String? = null
    )

    /** Check GitHub Releases for a newer version. */
    suspend fun checkForUpdate(githubRepo: String = DEFAULT_GITHUB_REPO): UpdateCheckResult {
        return withContext(Dispatchers.IO) {
            try {
                val currentVersionName = getCurrentVersionName()
                val currentVersion = parseVersion(currentVersionName)

                val apiUrl = "https://api.github.com/repos/$githubRepo/releases/latest"
                val request = Request.Builder()
                    .url(apiUrl)
                    .header("Accept", "application/vnd.github.v3+json")
                    .build()
                val response = client.newCall(request).execute()

                if (response.code == 404) {
                    return@withContext UpdateCheckResult(
                        available = false,
                        currentVersionName = currentVersionName,
                        error = "No releases found"
                    )
                }

                if (!response.isSuccessful) {
                    return@withContext UpdateCheckResult(
                        available = false,
                        currentVersionName = currentVersionName,
                        error = "GitHub returned ${response.code}"
                    )
                }

                val body = response.body?.string() ?: return@withContext UpdateCheckResult(
                    available = false,
                    currentVersionName = currentVersionName,
                    error = "Empty response"
                )

                val release = Gson().fromJson(body, GitHubRelease::class.java)

                // Find the .apk asset
                val apkAsset = release.assets.firstOrNull { it.name.endsWith(".apk") }
                    ?: return@withContext UpdateCheckResult(
                        available = false,
                        currentVersionName = currentVersionName,
                        error = "No APK in latest release"
                    )

                val releaseVersion = parseVersion(release.tagName)
                val available = compareVersions(releaseVersion, currentVersion) > 0

                val versionName = release.tagName.removePrefix("v")
                val changelog = release.body?.trim() ?: ""
                val sizeMb = "%.1f MB".format(apkAsset.size / 1_048_576.0)

                if (available) {
                    val info = UpdateInfo(versionName, apkAsset.downloadUrl, changelog, sizeMb)
                    saveAvailableUpdate(info)
                    saveLastCheckTime()
                    UpdateCheckResult(
                        available = true,
                        info = info,
                        currentVersionName = currentVersionName
                    )
                } else {
                    saveLastCheckTime()
                    UpdateCheckResult(
                        available = false,
                        currentVersionName = currentVersionName
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Update check failed", e)
                UpdateCheckResult(
                    available = false,
                    currentVersionName = getCurrentVersionName(),
                    error = e.message
                )
            }
        }
    }

    /** Download the APK using DownloadManager for reliability and progress tracking. */
    fun downloadUpdate(apkUrl: String, onComplete: (File?) -> Unit) {
        val apkFile = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), APK_FILE_NAME)
        if (apkFile.exists()) apkFile.delete()

        val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val request = DownloadManager.Request(Uri.parse(apkUrl))
            .setTitle("VistaCore Update")
            .setDescription("Downloading new version…")
            .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, APK_FILE_NAME)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)

        val downloadId = downloadManager.enqueue(request)

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
                if (id == downloadId) {
                    context.unregisterReceiver(this)
                    if (apkFile.exists()) {
                        onComplete(apkFile)
                    } else {
                        onComplete(null)
                    }
                }
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(
                receiver,
                IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
                Context.RECEIVER_EXPORTED
            )
        } else {
            context.registerReceiver(
                receiver,
                IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
            )
        }
    }

    /** Trigger the system package installer for the downloaded APK. */
    fun installApk(apkFile: File) {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apkFile
        )

        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(intent)
    }

    /** Convenience: download then auto-install. */
    fun downloadAndInstall(apkUrl: String) {
        downloadUpdate(apkUrl) { file ->
            if (file != null) {
                installApk(file)
            } else {
                Log.e(TAG, "Download failed, APK file not found")
            }
        }
    }

    fun getCurrentVersionName(): String {
        return try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "0.0.0"
        } catch (_: Exception) { "0.0.0" }
    }

    fun getLastCheckTime(): Long {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getLong(KEY_LAST_UPDATE_CHECK, 0)
    }

    fun getCachedUpdateVersion(): String? {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_AVAILABLE_VERSION, null)
    }

    fun getCachedChangelog(): String? {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_AVAILABLE_CHANGELOG, null)
    }

    fun clearCachedUpdate() {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .remove(KEY_AVAILABLE_VERSION)
            .remove(KEY_AVAILABLE_CHANGELOG)
            .apply()
    }

    // --- Version parsing & comparison ---

    private data class SemVer(val major: Int, val minor: Int, val patch: Int)

    /** Parse "v1.2.3" or "1.2.3" into SemVer. */
    private fun parseVersion(tag: String): SemVer {
        val clean = tag.removePrefix("v").trim()
        val parts = clean.split(".").mapNotNull { it.toIntOrNull() }
        return SemVer(
            parts.getOrElse(0) { 0 },
            parts.getOrElse(1) { 0 },
            parts.getOrElse(2) { 0 }
        )
    }

    /** Returns >0 if a is newer, <0 if older, 0 if equal. */
    private fun compareVersions(a: SemVer, b: SemVer): Int {
        if (a.major != b.major) return a.major - b.major
        if (a.minor != b.minor) return a.minor - b.minor
        return a.patch - b.patch
    }

    private fun saveLastCheckTime() {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putLong(KEY_LAST_UPDATE_CHECK, System.currentTimeMillis())
            .apply()
    }

    private fun saveAvailableUpdate(info: UpdateInfo) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString(KEY_AVAILABLE_VERSION, info.versionName)
            .putString(KEY_AVAILABLE_CHANGELOG, info.changelog)
            .apply()
    }
}
