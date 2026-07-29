package com.arkiv.player.tvservice

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * Worker periódico que revive el [TvConnectionService] en primer plano si Fire OS
 * (u otro fabricante agresivo con el ahorro de batería) lo mató en segundo plano.
 */
class TvKeepAliveWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        // Best-effort: re-arranca el servicio foreground si Fire OS lo mató.
        runCatching { TvConnectionService.start(applicationContext) }
        return Result.success()
    }

    companion object {
        fun schedule(context: Context) {
            val work = PeriodicWorkRequestBuilder<TvKeepAliveWorker>(15, TimeUnit.MINUTES).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                "arkiv_tv_keepalive",
                ExistingPeriodicWorkPolicy.KEEP,
                work,
            )
        }
    }
}
