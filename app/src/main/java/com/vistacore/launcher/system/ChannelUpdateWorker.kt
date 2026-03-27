package com.vistacore.launcher.system

import android.content.Context
import android.util.Log
import androidx.work.*
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonWriter
import com.vistacore.launcher.data.PrefsManager
import com.vistacore.launcher.iptv.Channel
import com.vistacore.launcher.iptv.DispatcharrVodClient
import com.vistacore.launcher.iptv.M3UParser
import com.vistacore.launcher.iptv.XtreamAuth
import com.vistacore.launcher.iptv.XtreamClient
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.FileReader
import java.io.FileWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

class ChannelUpdateWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "ChannelUpdate"
        private const val WORK_NAME = "channel_auto_update"
        private const val CACHE_PREFS = "vistacore_channel_cache"
        private const val KEY_LAST_UPDATE = "last_update_time"
        private const val CACHE_FILE = "channels_cache.json"
        private const val MOVIES_CACHE_FILE = "movies_cache.json"
        private const val SERIES_CACHE_FILE = "series_cache.json"

        private val gson = Gson()

        fun schedule(context: Context, intervalHours: Long = 6) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = PeriodicWorkRequestBuilder<ChannelUpdateWorker>(
                intervalHours, TimeUnit.HOURS
            )
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }

        fun refreshNow(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = OneTimeWorkRequestBuilder<ChannelUpdateWorker>()
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context).enqueue(request)
        }

        fun getLastUpdateTime(context: Context): Long {
            return context.getSharedPreferences(CACHE_PREFS, Context.MODE_PRIVATE)
                .getLong(KEY_LAST_UPDATE, 0)
        }

        /** Load channels from file cache using streaming reader. Returns null if no cache exists. */
        fun getCachedChannels(context: Context): List<Channel>? = readCacheFile(context, CACHE_FILE)

        /** Load only movies from cache. */
        fun getCachedMovies(context: Context): List<Channel>? = readCacheFile(context, MOVIES_CACHE_FILE)

        /** Load only series from cache. */
        fun getCachedSeries(context: Context): List<Channel>? = readCacheFile(context, SERIES_CACHE_FILE)

        private fun readCacheFile(context: Context, fileName: String): List<Channel>? {
            // Try gzipped file first, fall back to plain JSON
            val gzFile = File(context.filesDir, "$fileName.gz")
            val plainFile = File(context.filesDir, fileName)
            val file = when {
                gzFile.exists() && gzFile.length() > 0 -> gzFile
                plainFile.exists() && plainFile.length() > 0 -> plainFile
                else -> return null
            }
            val isGzip = file.name.endsWith(".gz")

            return try {
                val channels = mutableListOf<Channel>()
                val inputStream = if (isGzip) {
                    InputStreamReader(GZIPInputStream(FileInputStream(file), 65536))
                } else {
                    java.io.BufferedReader(FileReader(file), 65536)
                }
                JsonReader(inputStream).use { reader ->
                    reader.isLenient = true
                    reader.beginArray()
                    while (reader.hasNext()) {
                        reader.beginObject()
                        var id = ""; var name = ""; var streamUrl = ""; var logoUrl = ""
                        var category = "Uncategorized"; var number = 0
                        var contentType = com.vistacore.launcher.iptv.ContentType.LIVE
                        var epgId = ""
                        while (reader.hasNext()) {
                            val key = reader.nextName()
                            // Handle null JSON values safely
                            if (reader.peek() == com.google.gson.stream.JsonToken.NULL) {
                                reader.nextNull()
                                continue
                            }
                            when (key) {
                                "id" -> id = reader.nextString()
                                "name" -> name = reader.nextString()
                                "streamUrl" -> streamUrl = reader.nextString()
                                "logoUrl" -> logoUrl = reader.nextString()
                                "category" -> category = reader.nextString()
                                "number" -> number = reader.nextInt()
                                "contentType" -> {
                                    val ct = reader.nextString()
                                    contentType = try { com.vistacore.launcher.iptv.ContentType.valueOf(ct) } catch (_: Exception) { com.vistacore.launcher.iptv.ContentType.LIVE }
                                }
                                "epgId" -> epgId = reader.nextString()
                                else -> reader.skipValue()
                            }
                        }
                        reader.endObject()
                        channels.add(Channel(id, name, streamUrl, logoUrl, category, number, contentType, epgId))
                    }
                    reader.endArray()
                }
                Log.d(TAG, "Read ${channels.size} channels from $fileName")
                channels
            } catch (e: Exception) {
                Log.e(TAG, "Failed to read cache $fileName", e)
                file.delete()
                null
            }
        }

        private fun writeCacheFile(context: Context, fileName: String, channels: List<Channel>) {
            val gzFileName = "$fileName.gz"
            val tempFile = File(context.filesDir, "$gzFileName.tmp")
            val file = File(context.filesDir, gzFileName)
            // Delete old uncompressed file if exists
            File(context.filesDir, fileName).delete()
            JsonWriter(OutputStreamWriter(GZIPOutputStream(FileOutputStream(tempFile), 65536))).use { writer ->
                writer.beginArray()
                for (ch in channels) {
                    writer.beginObject()
                    writer.name("id").value(ch.id)
                    writer.name("name").value(ch.name)
                    writer.name("streamUrl").value(ch.streamUrl)
                    writer.name("logoUrl").value(ch.logoUrl ?: "")
                    writer.name("category").value(ch.category ?: "Uncategorized")
                    writer.name("number").value(ch.number)
                    writer.name("contentType").value(ch.contentType.name)
                    writer.name("epgId").value(ch.epgId ?: "")
                    writer.endObject()
                }
                writer.endArray()
            }
            tempFile.renameTo(file)
        }

        /** Save channels to file cache — also splits into movies/series caches. */
        fun cacheChannels(context: Context, channels: List<Channel>) {
            try {
                // Write main cache (live channels only — keeps file small)
                val liveChannels = channels.filter { it.contentType == com.vistacore.launcher.iptv.ContentType.LIVE }
                writeCacheFile(context, CACHE_FILE, liveChannels)

                // Write separate movie and series caches
                val movies = channels.filter { it.contentType == com.vistacore.launcher.iptv.ContentType.MOVIE }
                writeCacheFile(context, MOVIES_CACHE_FILE, movies)

                val series = channels.filter { it.contentType == com.vistacore.launcher.iptv.ContentType.SERIES }
                writeCacheFile(context, SERIES_CACHE_FILE, series)

                context.getSharedPreferences(CACHE_PREFS, Context.MODE_PRIVATE)
                    .edit()
                    .putLong(KEY_LAST_UPDATE, System.currentTimeMillis())
                    .apply()
                Log.d(TAG, "Cached ${liveChannels.size} live, ${movies.size} movies, ${series.size} series")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to write channel cache", e)
            }
        }
    }

    override suspend fun doWork(): Result {
        val prefs = PrefsManager(applicationContext)

        if (!prefs.hasIptvConfig()) {
            return Result.success()
        }

        return try {
            val channels = when (prefs.sourceType) {
                PrefsManager.SOURCE_M3U -> M3UParser().parse(prefs.m3uUrl)
                PrefsManager.SOURCE_XTREAM -> {
                    val auth = XtreamAuth(prefs.xtreamServer, prefs.xtreamUsername, prefs.xtreamPassword)
                    val xc = XtreamClient(auth)
                    val live = xc.getChannels()
                    val movies = try { xc.getMovies() } catch (_: Exception) { emptyList() }
                    val series = try { xc.getSeries() } catch (_: Exception) { emptyList() }
                    live + movies + series
                }
                else -> emptyList()
            }

            cacheChannels(applicationContext, channels)
            Log.d(TAG, "Channel update complete: ${channels.size} channels cached")
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Channel update failed: ${e.message}")
            Result.retry()
        }
    }
}
