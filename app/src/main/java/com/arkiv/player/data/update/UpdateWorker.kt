package com.arkiv.player.data.update

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.arkiv.player.ArkivApp

/** Periodic OTA check (every 6h via WorkManager, see [ArkivApp.onCreate]). */
class UpdateWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val graph = (applicationContext as ArkivApp).graph
        return runCatching { graph.checkForUpdate() }.fold({ Result.success() }, { Result.retry() })
    }
}
