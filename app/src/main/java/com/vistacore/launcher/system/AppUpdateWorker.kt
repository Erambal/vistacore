package com.vistacore.launcher.system

import android.content.Context
import android.util.Log
import androidx.work.*
import com.vistacore.launcher.data.PrefsManager
import java.util.concurrent.TimeUnit

/**
 * WorkManager worker that periodically checks for app updates in the background.
 * If an update is found, the info is cached so the UI can show a notification.
 */
class AppUpdateWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "AppUpdateWorker"
        private const val WORK_NAME = "vistacore_app_update_check"

        /** Schedule periodic update checks (default every 12 hours). */
        fun schedule(context: Context, intervalHours: Long = 12) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = PeriodicWorkRequestBuilder<AppUpdateWorker>(
                intervalHours, TimeUnit.HOURS
            )
                .setConstraints(constraints)
                .setInitialDelay(1, TimeUnit.MINUTES)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
            Log.d(TAG, "Scheduled app update checks every $intervalHours hours")
        }

        /** Cancel periodic update checks. */
        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
            Log.d(TAG, "Cancelled app update checks")
        }

        /** Run a one-time immediate check. */
        fun checkNow(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = OneTimeWorkRequestBuilder<AppUpdateWorker>()
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context).enqueue(request)
            Log.d(TAG, "Triggered immediate app update check")
        }
    }

    override suspend fun doWork(): Result {
        return try {
            val prefs = PrefsManager(applicationContext)
            val repo = prefs.appUpdateRepo

            if (repo.isBlank()) {
                Log.d(TAG, "No update repo configured, skipping")
                return Result.success()
            }

            val manager = AppUpdateManager(applicationContext)
            val result = manager.checkForUpdate(repo)

            if (result.available && result.info != null) {
                Log.i(TAG, "Update available: v${result.info.versionName}")
            } else if (result.error != null) {
                Log.w(TAG, "Update check error: ${result.error}")
            } else {
                Log.d(TAG, "App is up to date (v${result.currentVersionName})")
            }

            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Update check failed", e)
            Result.retry()
        }
    }
}
