package com.arkiv.player.data.offline

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.arkiv.player.AppGraph
import java.util.concurrent.TimeUnit

/**
 * Poll de fondo del estado de un job de descarga de arkiv-offline (NUC), para avisar con una
 * notificación local cuando termina aunque la app esté en segundo plano. Se dispara al crear el
 * job (Task 8, ver `downloadPack`/`downloadEpisode` en `AnimeShowDetailScreen`/`CineDetailScreen`)
 * y, mientras siga en curso, se re-encola a sí mismo cada 30s (mismo patrón de auto-relanzamiento
 * que `TvKeepAliveWorker`, pero con `OneTimeWorkRequest` en vez de periódico porque el intervalo
 * solo aplica mientras el job no haya terminado).
 */
class NucDownloadCheckWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val jobId = inputData.getLong(KEY_JOB_ID, -1L)
        if (jobId <= 0) return Result.failure()
        val api = AppGraph.from(applicationContext).arkivOfflineApi
        val job = api.getJob(jobId) ?: return Result.retry()
        return when (job.status) {
            "done" -> { notify(job.jobId, "Descarga completa", "Tu descarga terminó"); Result.success() }
            "failed" -> { notify(job.jobId, "Descarga falló", "No se pudo completar la descarga"); Result.success() }
            else -> {
                // sigue en curso ("queued"/"downloading"): re-encolar otra pasada en 30s (WorkManager
                // no soporta un poll continuo dentro de un mismo doWork() de forma confiable en background)
                schedule(applicationContext, jobId, delaySeconds = 30)
                Result.success()
            }
        }
    }

    private fun notify(jobId: Long, title: String, text: String) {
        val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Descargas NUC", NotificationManager.IMPORTANCE_DEFAULT))
        }
        val notif = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle(title).setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setAutoCancel(true)
            .build()
        nm.notify(jobId.toInt(), notif)
    }

    companion object {
        private const val KEY_JOB_ID = "job_id"
        private const val CHANNEL_ID = "arkiv_nuc_downloads"

        /** Nombre del trabajo único por job: [schedule] lo encola y [cancel] lo mata con este mismo id. */
        fun workName(jobId: Long): String = "nuc_download_check_$jobId"

        fun schedule(context: Context, jobId: Long, delaySeconds: Long = 0) {
            val work = OneTimeWorkRequestBuilder<NucDownloadCheckWorker>()
                .setInputData(workDataOf(KEY_JOB_ID to jobId))
                .setInitialDelay(delaySeconds, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                workName(jobId), ExistingWorkPolicy.REPLACE, work,
            )
        }

        /**
         * Cancela el poll de un job. Sin esto, cancelar la descarga borraba el job en la NUC pero
         * dejaba vivo el worker: `getJob()` devolvía 404 -> `Result.retry()` -> WorkManager lo
         * reintentaba con backoff PARA SIEMPRE (nunca llega a un estado terminal que lo detenga).
         */
        fun cancel(context: Context, jobId: Long) {
            WorkManager.getInstance(context).cancelUniqueWork(workName(jobId))
        }
    }
}
