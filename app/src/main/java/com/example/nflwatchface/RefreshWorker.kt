package com.example.nflwatchface

import android.content.ComponentName
import android.content.Context
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/** Asks the watch to re-run both data sources right now. */
class RefreshWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        listOf(BearsScoreService::class.java, BearsDetailService::class.java).forEach { svc ->
            ComplicationDataSourceUpdateRequester
                .create(applicationContext, ComponentName(applicationContext, svc))
                .requestUpdateAll()
        }
        return Result.success()
    }
}

/** Keeps exactly one pending refresh scheduled (replaces the previous one). */
object Refresher {
    private const val NAME = "bears-refresh"

    fun schedule(context: Context, delayMs: Long?) {
        val wm = WorkManager.getInstance(context)
        if (delayMs == null) {
            wm.cancelUniqueWork(NAME)
            return
        }
        val request = OneTimeWorkRequestBuilder<RefreshWorker>()
            .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
            .setConstraints(
                Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
            )
            .build()
        wm.enqueueUniqueWork(NAME, ExistingWorkPolicy.REPLACE, request)
    }
}
