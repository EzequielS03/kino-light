package com.arkiv.player.data.local

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.arkiv.player.AppGraph
import com.arkiv.player.data.db.DownloadEntity
import kotlinx.coroutines.runBlocking

/**
 * Procesa la cola de descargas al dispositivo, UNA a la vez.
 *
 * Secuencial y no en paralelo por tres razones concretas: `TorrentEngine` es de un stream activo a la
 * vez, el disco de blog no aguanta varios staging simultáneos (fase 2), y en el Fire TV Stick el
 * ancho de banda no sobra.
 *
 * Mismo patrón de auto-relanzamiento que `NucDownloadCheckWorker`: al terminar una fila se re-encola
 * para tomar la siguiente, en vez de iterar dentro de un solo `doWork()` — WorkManager no garantiza
 * un trabajo largo indefinido en background.
 */
class LocalDownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val graph = AppGraph.from(applicationContext)
        val dao = graph.database.downloadDao()

        val rows = dao.getAll().map { QueueRow(it.episodeId, it.state, it.createdAt) }
        val next = DownloadQueuePolicy.nextToProcess(rows) ?: return Result.success()
        val entity = dao.get(next.episodeId) ?: return Result.success()

        // `setForeground` puede lanzar: si la app está en background sin Activity visible reciente,
        // o si el sistema restringe el arranque de foreground services (ForegroundServiceStartNotAllowedException
        // en API 31+, o cualquier otra excepción de notificación/binder). Si eso pasa NO puede tumbar la
        // descarga: preferimos bajar el archivo sin notificación visible a no bajarlo. Por eso va con
        // runCatching en vez de dejar que la excepción se propague fuera de doWork().
        runCatching { setForeground(foregroundInfo("Descargando", entity.episodeId)) }
            .onFailure { Log.w(TAG, "no se pudo mostrar la notificación de foreground: ${it.message}") }

        val strategy = graph.downloadStrategies[entity.source]
        if (strategy == null) {
            dao.updateState(entity.episodeId, LocalDownloadState.FAILED, "Fuente no soportada: ${entity.source}")
            reschedule()
            return Result.success()
        }

        dao.updateState(entity.episodeId, LocalDownloadState.DOWNLOADING, null)

        val outcome = runCatching {
            strategy.download(
                episodeId = entity.episodeId,
                alreadyConfirmed = entity.sizeConfirmed,
                targetDir = graph.localDownloads.targetDir(),
                onProgress = { done, total -> persistProgress(dao, entity, done, total) },
            )
        }.getOrElse { DownloadOutcome.Failed(it.message ?: "Error inesperado") }

        when (outcome) {
            is DownloadOutcome.Done -> {
                dao.markCompleted(entity.episodeId, outcome.file.absolutePath)
                notifyDone(entity.episodeId)
            }
            is DownloadOutcome.NeedsConfirmation -> {
                dao.updateBytes(
                    entity.episodeId, LocalDownloadState.NEEDS_CONFIRMATION, 0f, 0, outcome.fileSizeBytes,
                )
                dao.updateState(
                    entity.episodeId, LocalDownloadState.NEEDS_CONFIRMATION,
                    "Pesa ${TorrentSizeGate.formatSize(outcome.fileSizeBytes)}",
                )
                notifyNeedsConfirmation(entity.episodeId, outcome.fileSizeBytes)
            }
            is DownloadOutcome.Failed -> {
                Log.w(TAG, "falló ${entity.episodeId}: ${outcome.reason}")
                dao.updateState(entity.episodeId, LocalDownloadState.FAILED, outcome.reason)
            }
        }

        reschedule()
        return Result.success()
    }

    /**
     * Escribe el progreso a Room, no más de una vez por segundo. Sin esta cadencia una descarga de
     * 4 GB haría decenas de miles de UPDATE (el callback llega cada 64 KB) y la UI, que observa la
     * tabla, se recompondría sin parar.
     *
     * `lastPersistMs` es un campo mutable de instancia, no un `companion object`/`var` compartido:
     * WorkManager crea una instancia NUEVA de `LocalDownloadWorker` en cada ejecución (vía
     * `WorkerFactory`, una por `doWork()`), así que arranca en 0 en cada pasada y no hay estado
     * pegajoso entre descargas ni corrupción por reuso — el ciclo de vida de esta instancia es
     * exactamente el de una sola llamada a `doWork()`.
     *
     * No es `suspend`: `DownloadStrategy.onProgress` es `(Long, Long) -> Unit`, un callback síncrono
     * (interfaz ya existente, no se puede tocar acá), y las dos estrategias lo invocan sincrónicamente
     * desde dentro de su propio `download()` suspendido (que ya corre en un dispatcher de I/O). Como
     * el DAO de Room es `suspend`, se puentea con `runBlocking` — aceptable porque el throttle de
     * más arriba lo reduce a como mucho una escritura por segundo, no una por cada chunk de 64 KB.
     */
    private var lastPersistMs = 0L
    private fun persistProgress(
        dao: com.arkiv.player.data.db.DownloadDao,
        entity: DownloadEntity,
        done: Long,
        total: Long,
    ) {
        val now = System.currentTimeMillis()
        if (now - lastPersistMs < PROGRESS_THROTTLE_MS) return
        lastPersistMs = now
        val progress = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else 0f
        runBlocking { dao.updateBytes(entity.episodeId, LocalDownloadState.DOWNLOADING, progress, done, total) }
    }

    /**
     * Re-encola para tomar la siguiente fila. A propósito NO llama a [schedule] (que usa `KEEP`):
     * en este punto la fila única `WORK_NAME` todavía figura en WorkManager como `RUNNING` — esta
     * misma ejecución no terminó de escribir su estado final hasta que `doWork()` retorna — y `KEEP`
     * mira exactamente los estados `ENQUEUED`/`RUNNING` para decidir si no hacer nada. El resultado
     * con `KEEP` acá sería un no-op silencioso en el 100% de las pasadas: la cola procesaría una fila
     * por cada `enqueue()` externo y nunca se auto-relanzaría, rompiendo el propósito central de este
     * worker. `REPLACE` sí fuerza la inserción de la siguiente pasada aunque esta instancia siga
     * "viva" un instante más — mismo patrón que ya usa `NucDownloadCheckWorker.schedule` para su
     * propio auto-relanzamiento.
     */
    private fun reschedule() {
        WorkManager.getInstance(applicationContext).enqueueUniqueWork(
            WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<LocalDownloadWorker>().build(),
        )
    }

    private fun foregroundInfo(title: String, text: String): ForegroundInfo {
        ensureChannel()
        val notif = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle(title).setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .build()
        return if (android.os.Build.VERSION.SDK_INT >= 29) {
            ForegroundInfo(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIF_ID, notif)
        }
    }

    private fun notifyDone(episodeId: String) =
        notify(episodeId.hashCode(), "Descarga completa", "Ya podés verlo sin conexión")

    private fun notifyNeedsConfirmation(episodeId: String, bytes: Long) = notify(
        episodeId.hashCode(),
        "Descarga pesada",
        "Pesa ${TorrentSizeGate.formatSize(bytes)}. Confirmá en Descargas para bajarla.",
    )

    private fun notify(id: Int, title: String, text: String) {
        ensureChannel()
        val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(
            id,
            NotificationCompat.Builder(applicationContext, CHANNEL_ID)
                .setContentTitle(title).setContentText(text)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setAutoCancel(true)
                .build(),
        )
    }

    private fun ensureChannel() {
        if (android.os.Build.VERSION.SDK_INT < 26) return
        val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Descargas", NotificationManager.IMPORTANCE_LOW)
        )
    }

    companion object {
        private const val TAG = "ArkivLocalDl"
        private const val CHANNEL_ID = "arkiv_local_downloads"
        private const val NOTIF_ID = 4711
        private const val PROGRESS_THROTTLE_MS = 1_000L
        private const val WORK_NAME = "arkiv_local_downloads"

        /**
         * KEEP y no REPLACE: si ya hay una pasada corriendo, encolar otra descarga no debe matarla a
         * mitad. Cuando termine, se re-encola sola (ver [reschedule], que sí usa REPLACE) y toma la
         * siguiente. Este `schedule` es el punto de entrada EXTERNO (desde `LocalDownloadManager`);
         * el auto-relanzamiento interno no pasa por acá.
         */
        fun schedule(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME,
                ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<LocalDownloadWorker>().build(),
            )
        }
    }
}
