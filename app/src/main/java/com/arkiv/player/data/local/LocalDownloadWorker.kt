package com.arkiv.player.data.local

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
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
import com.arkiv.player.MainActivity
import com.arkiv.player.playback.ACTION_OPEN_PLAYER
import com.arkiv.player.playback.EXTRA_EPISODE_ID
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

        // Nombre de verdad para los avisos (antes mostraban el id crudo del episodio) y cuántos
        // esperan turno: como la cola es de UNA a la vez, sin ese dato los demás capítulos parecen
        // haberse perdido. Se calcula ACÁ, antes de la compuerta de gemelos, para que todos los
        // avisos de esta pasada —incluido el de "ya lo tenías"— puedan decir de qué capítulo hablan.
        val episodio = graph.database.itemDao().getEpisode(entity.episodeId)
        val serie = episodio?.let { graph.database.itemDao().getItem(it.itemId)?.title }
        nombreDelCapitulo = AvisoDeDescarga.nombre(serie, episodio?.displayName)
        tituloDeLaNotificacion = AvisoDeDescarga.titulo(serie, episodio?.displayName)
        enCola = rows.count { it.state == LocalDownloadState.QUEUED && it.episodeId != entity.episodeId }

        // La compuerta de duplicados corre TAMBIÉN acá, no solo en `LocalDownloadManager.enqueue`.
        // Dos motivos, los dos reales:
        //  1. Las filas que YA estaban en la cola nunca vuelven a pasar por `enqueue`. En el
        //     dispositivo del usuario había justo eso: `web:series:tt30217403::31fe74c5` completed
        //     (461 MB en disco) y `web:series:anilist171018::31fe74c5` queued, esperando turno para
        //     bajar el mismo archivo otra vez.
        //  2. Dos gemelos encolados en el mismo lote pasan los dos por `enqueue` sin que ninguno
        //     esté completed todavía. Como la cola es de UNA a la vez, cuando el segundo llega acá
        //     el primero ya terminó y esta compuerta lo agarra.
        if (adoptTwinIfAlreadyDownloaded(graph, dao, entity)) {
            reschedule()
            return Result.success()
        }

        // `setForeground` puede lanzar: si la app está en background sin Activity visible reciente,
        // o si el sistema restringe el arranque de foreground services (ForegroundServiceStartNotAllowedException
        // en API 31+, o cualquier otra excepción de notificación/binder). Si eso pasa NO puede tumbar la
        // descarga: preferimos bajar el archivo sin notificación visible a no bajarlo. Por eso va con
        // runCatching en vez de dejar que la excepción se propague fuera de doWork().
        // Cada fila que arranca REEMPLAZA la notificación anterior (mismo NOTIF_ID), así que al
        // pasar al siguiente capítulo de la cola la notificación se convierte en la de ese capítulo,
        // con su propio progreso.
        runCatching { setForeground(foregroundInfo(tituloDeLaNotificacion, null, entity.episodeId)) }
            .onFailure { Log.w(TAG, "no se pudo mostrar la notificación de foreground: ${it.message}") }

        val strategy = graph.downloadStrategies[entity.source]
        if (strategy == null) {
            dao.updateState(entity.episodeId, LocalDownloadState.FAILED, "Fuente no soportada: ${entity.source}")
            reschedule()
            return Result.success()
        }

        dao.updateState(entity.episodeId, LocalDownloadState.DOWNLOADING, null)

        val outcome = try {
            strategy.download(
                episodeId = entity.episodeId,
                alreadyConfirmed = entity.sizeConfirmed,
                targetDir = graph.localDownloads.targetDir(),
                onProgress = { done, total -> persistProgress(dao, entity, done, total) },
            )
        } catch (ce: kotlinx.coroutines.CancellationException) {
            // NO se traga la cancelación (antes iba dentro de un runCatching, que la atrapaba igual
            // que cualquier otra excepción). Tragársela tenía dos consecuencias feas: la fila
            // quedaba en `failed` con un motivo inventado aunque el usuario solo hubiera cancelado,
            // y —peor— el `finally` de la estrategia de torrent nunca corría dentro de esta
            // corrutina, dejando el TorrentHandle vivo en la sesión para siempre. Relanzarla deja
            // que la estrategia limpie y que WorkManager marque el trabajo como CANCELLED.
            throw ce
        } catch (t: Throwable) {
            DownloadOutcome.Failed(t.message ?: "Error inesperado", transient = DownloadRetryPolicy.isTransient(t))
        }

        when (outcome) {
            is DownloadOutcome.Done -> {
                // Chequeo LO MÁS TARDE POSIBLE, justo antes de escribir el estado: la cancelación de
                // WorkManager (disparada por "Quitar") llega de forma ASÍNCRONA, y ni el `while` de
                // `TorrentDownloadStrategy` ni el `renameTo` final de `HttpRangeDownloader` tienen un
                // punto de suspensión después del último chequeo cancelable — así que la estrategia
                // puede terminar de escribir el archivo destino milisegundos después de que
                // `LocalDownloadManager.remove` ya borró la fila y barrió el directorio. Si la fila ya
                // no está, este archivo es justo lo que ese barrido no llegó a agarrar: no hay ninguna
                // otra limpieza que lo vaya a recoger después, así que se borra acá y NO se notifica
                // "Descarga completa" de algo que el usuario ya eliminó.
                if (dao.get(entity.episodeId) == null) {
                    Log.i(
                        TAG,
                        "la fila de ${entity.episodeId} se quitó mientras terminaba de bajar; se descarta el archivo",
                    )
                    runCatching { outcome.file.delete() }
                    runCatching { LocalFilePaths.partOf(outcome.file).delete() }
                    runCatching { LocalFilePaths.originOf(outcome.file).delete() }
                } else {
                    dao.markCompleted(entity.episodeId, outcome.file.absolutePath)
                    notifyDone(entity.episodeId)
                }
            }
            is DownloadOutcome.NeedsConfirmation -> {
                // Mismo cuidado que en `Done`, pero acá lo único engañoso es la notificación: no hay
                // archivo bajado que limpiar (el torrent recién resolvió metadata, no bajó bytes), y
                // los `UPDATE` de Room sobre una fila ya borrada no fallan ni tienen efecto (el WHERE
                // no matchea nada). Lo que sí sería un engaño es "Confirmá en Descargas para bajarla"
                // sobre una fila que el usuario ya quitó — no hay nada que confirmar.
                if (dao.get(entity.episodeId) != null) {
                    dao.updateProgress(entity.episodeId, 0f, 0, outcome.fileSizeBytes)
                    dao.updateState(
                        entity.episodeId, LocalDownloadState.NEEDS_CONFIRMATION,
                        "Pesa ${TorrentSizeGate.formatSize(outcome.fileSizeBytes)}",
                    )
                    notifyNeedsConfirmation(entity.episodeId, outcome.fileSizeBytes)
                }
            }
            is DownloadOutcome.Failed -> {
                Log.w(TAG, "falló ${entity.episodeId}: ${outcome.reason} (transitorio=${outcome.transient})")
                // Acá NO hace falta el mismo chequeo: esta rama no notifica nada visible (solo loguea
                // y escribe estado), y un `UPDATE`/`Result.retry()` sobre una fila ya borrada no
                // reintroduce la fila ni engaña a nadie — en el peor caso, si el usuario la volvió a
                // encolar mientras tanto, es la MISMA fila (incluso mismo episodeId) y el motivo del
                // error es información legítima para ella.
                // Corte de red a mitad de 4 GB: el `.part` está intacto y `Range` reanuda, pero
                // nadie disparaba esa reanudación porque todo fallo terminaba en `failed`. Ahora los
                // fallos transitorios devuelven `Result.retry()`: WorkManager reintenta ESTE mismo
                // request con backoff exponencial y la fila sigue en `downloading`, así que
                // `nextToProcess` la vuelve a elegir a ella (lo empezado gana sobre lo encolado).
                // NO se re-encola la cola acá: hacerlo con REPLACE mataría el retry programado.
                if (DownloadRetryPolicy.shouldRetry(outcome.transient, runAttemptCount)) {
                    dao.setError(entity.episodeId, outcome.reason)
                    return Result.retry()
                }
                dao.updateState(entity.episodeId, LocalDownloadState.FAILED, outcome.reason)
            }
        }

        reschedule()
        return Result.success()
    }

    /**
     * Si el contenido de [entity] ya está en disco bajo OTRO ítem, no lo baja: **adopta el archivo
     * del gemelo** (marca esta fila `completed` con el mismo `filePath`) y devuelve `true`.
     *
     * Por qué adoptar y no borrar la fila ni marcarla `failed`:
     *  - Borrarla en silencio deja el capítulo como "no descargado" para siempre y el botón de la UI
     *    no hace nada visible: el usuario vuelve a tocarlo y vuelve a no pasar nada.
     *  - `failed` refleja algo que no pasó (no falló nada) y encima invita a "Reintentar", que
     *    volvería a caer acá.
     *  - `completed` apuntando al archivo del gemelo dice la verdad ("ya lo tenés"), deja la fila
     *    visible y quitable en Descargas, y además hace que ESE capítulo se pueda ver sin conexión
     *    desde su propio ítem: `LocalLibrary.fileFor` resuelve el mismo archivo y la biblioteca le
     *    pinta el tilde. Sin esto quedaba sin tilde y reproduciéndose por red teniendo el archivo
     *    ahí al lado.
     *
     * Que dos filas compartan `filePath` es deliberado y está contemplado en
     * `LocalDownloadManager.remove`, que no borra el archivo si otra fila lo referencia.
     *
     * Si el archivo del gemelo ya no existe (el usuario lo borró por fuera), NO se adopta nada y la
     * descarga sigue su curso normal: la compuerta es "ya está en disco", no "alguna vez estuvo".
     */
    private suspend fun adoptTwinIfAlreadyDownloaded(
        graph: AppGraph,
        dao: com.arkiv.player.data.db.DownloadDao,
        entity: DownloadEntity,
    ): Boolean {
        val target = EpisodeOrigin(
            entity.episodeId,
            graph.database.itemDao().getEpisode(entity.episodeId)?.torrentFileIndex,
        )
        val twinId = DuplicateDownloadPolicy.completedDuplicateOf(target, dao.completedOrigins())
            ?: return false
        val twin = dao.get(twinId) ?: return false
        val path = twin.filePath ?: twin.localUri?.removePrefix("file://") ?: return false
        if (!java.io.File(path).let { it.exists() && it.length() > 0L }) return false

        Log.i(TAG, "${entity.episodeId} ya está en disco como $twinId; se adopta el archivo en vez de bajarlo")
        // El tamaño se copia del gemelo: es el del archivo que esta fila va a servir, y sin esto la
        // pantalla de Descargas mostraría 0 B para algo que sí ocupa disco.
        dao.updateProgress(entity.episodeId, 1f, twin.bytesDone, twin.bytes)
        dao.markCompleted(entity.episodeId, path)
        dao.setError(entity.episodeId, DuplicateDownloadPolicy.ADOPTED_REASON)
        // Si esta fila venía a medias (se reanudó una descarga que ya no hace falta), su `.part`
        // queda huérfano: nadie más lo referencia ni lo limpia. Mismo barrido por prefijo que
        // `LocalDownloadManager.remove`, que por usar sanitize(episodeId) solo toca archivos de ESTE
        // episodio — nunca el del gemelo, que se llama con el episodeId del gemelo.
        runCatching {
            val prefix = "${LocalFilePaths.sanitize(entity.episodeId)}."
            graph.localDownloads.targetDir().listFiles { f -> f.name.startsWith(prefix) }
                ?.forEach { f -> runCatching { f.delete() } }
        }
        notifyAlreadyDownloaded(entity.episodeId)
        return true
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

    /** Nombre, título y cola de la fila que esta pasada baja; los usan los avisos. */
    private var nombreDelCapitulo: String? = null
    private var tituloDeLaNotificacion = "Bajando un capítulo"
    private var enCola = 0
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
        // `updateProgress` y no `updateBytes`: escribir el estado junto con el progreso hacía que la
        // fase de staging (web) fuera inalcanzable — el primer tick de progreso devolvía la fila de
        // `staging` a `downloading`. El estado lo escribe quien conoce la fase.
        runBlocking { dao.updateProgress(entity.episodeId, progress, done, total) }
        // Con el mismo throttle: la notificación se queda en el 0% inicial toda la descarga si nadie
        // la vuelve a emitir. `total <= 0` es tamaño desconocido -> barra indeterminada.
        actualizarNotificacion(entity.episodeId, if (total > 0) progress else null)
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
            request(),
        )
    }

    /**
     * La notificación de la descarga en curso. [fraccion] null = todavía no se sabe cuánto falta, y
     * entonces la barra va indeterminada en vez de mentir con un 0% clavado.
     *
     * `setOnlyAlertOnce` porque esta notificación se re-emite cada segundo con el progreso nuevo: sin
     * eso, cada actualización volvería a "avisar".
     */
    private fun notificacionDeProgreso(title: String, fraccion: Float?, episodeId: String) =
        NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(AvisoDeDescarga.subtitulo(fraccion, enCola))
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setProgress(100, ((fraccion ?: 0f) * 100).toInt(), fraccion == null)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            // Frenar una descarga sin tener que abrir la app y buscar el capítulo.
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Cancelar descarga",
                intentDeCancelar(episodeId),
            )
            .build()

    /** Dispara [AccionesDeDescargaReceiver], que cancela sin abrir nada. */
    private fun intentDeCancelar(episodeId: String): PendingIntent = PendingIntent.getBroadcast(
        applicationContext,
        episodeId.hashCode(),
        Intent(applicationContext, AccionesDeDescargaReceiver::class.java).apply {
            action = AccionesDeDescargaReceiver.ACTION_CANCELAR
            putExtra(AccionesDeDescargaReceiver.EXTRA_EPISODE_ID, episodeId)
        },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /** Abre el reproductor en ESE capítulo (no en el que estuviera sonando). */
    private fun intentDeVer(episodeId: String): PendingIntent = PendingIntent.getActivity(
        applicationContext,
        episodeId.hashCode(),
        Intent(applicationContext, MainActivity::class.java).apply {
            action = ACTION_OPEN_PLAYER
            putExtra(EXTRA_EPISODE_ID, episodeId)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /**
     * Re-emite la notificación de foreground con el progreso nuevo. Va por `NotificationManager` y no
     * por `setForeground`: es la MISMA notificación (mismo id) y actualizarla no pasa por el servicio,
     * así que no puede tumbar la descarga si el sistema restringe el arranque de foreground services.
     */
    private fun actualizarNotificacion(episodeId: String, fraccion: Float?) {
        runCatching {
            val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIF_ID, notificacionDeProgreso(tituloDeLaNotificacion, fraccion, episodeId))
        }
    }

    private fun foregroundInfo(title: String, fraccion: Float?, episodeId: String): ForegroundInfo {
        ensureChannel()
        val notif = notificacionDeProgreso(title, fraccion, episodeId)
        return if (android.os.Build.VERSION.SDK_INT >= 29) {
            ForegroundInfo(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIF_ID, notif)
        }
    }

    /**
     * "Descarga completa" + QUÉ capítulo terminó + un botón para verlo ahí mismo. Antes decía solo
     * "Descarga completa": con varias descargas seguidas no había forma de saber cuál era cuál, y
     * para verlo había que abrir la app y volver a buscar el capítulo a mano.
     */
    private fun notifyDone(episodeId: String) = notify(
        episodeId.hashCode(),
        "Descarga completa",
        AvisoDeDescarga.listo(null, nombreDelCapitulo),
        verEpisodeId = episodeId,
    )

    private fun notifyAlreadyDownloaded(episodeId: String) = notify(
        episodeId.hashCode(),
        "Ya lo tenías descargado",
        "Ese capítulo ya estaba en el dispositivo; no se bajó de nuevo",
    )

    private fun notifyNeedsConfirmation(episodeId: String, bytes: Long) = notify(
        episodeId.hashCode(),
        "Descarga pesada",
        "Pesa ${TorrentSizeGate.formatSize(bytes)}. Confírmala en Descargas para bajarla.",
    )

    private fun notify(id: Int, title: String, text: String, verEpisodeId: String? = null) {
        ensureChannel()
        val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val builder = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle(title).setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setAutoCancel(true)
        if (verEpisodeId != null) {
            // Tocar el aviso y tocar el botón hacen lo mismo: abrir ESE capítulo.
            val ver = intentDeVer(verEpisodeId)
            builder.setContentIntent(ver)
                .addAction(android.R.drawable.ic_media_play, "Ver capítulo", ver)
        }
        nm.notify(id, builder.build())
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
        private const val RETRY_BACKOFF_SECONDS = 30L

        /**
         * Backoff explícito para los `Result.retry()` de los fallos transitorios. Arranca en 30 s y
         * duplica: 30 s, 1 min, 2 min… Suficiente para que un WiFi que parpadea vuelva, y corto
         * comparado con lo que tarda una descarga de varios GB.
         */
        private fun request() = OneTimeWorkRequestBuilder<LocalDownloadWorker>()
            .setBackoffCriteria(
                androidx.work.BackoffPolicy.EXPONENTIAL,
                RETRY_BACKOFF_SECONDS,
                java.util.concurrent.TimeUnit.SECONDS,
            )
            .build()

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
                request(),
            )
        }

        /**
         * CORTA lo que se esté bajando ahora mismo y relanza la cola desde cero.
         *
         * Es el mecanismo con el que `LocalDownloadManager.cancel/remove` detienen de verdad una
         * descarga en curso: `REPLACE` cancela el trabajo único —incluido el que está RUNNING, cuya
         * corrutina recibe la cancelación— y encola una pasada nueva en el mismo acto. Hacerlo en
         * dos pasos (`cancelUniqueWork` + `schedule` con KEEP) tenía una carrera: mientras el
         * trabajo cancelado sigue figurando como RUNNING, el KEEP es un no-op y la cola quedaba
         * dormida hasta el próximo `enqueue`.
         *
         * La fila cancelada tiene que estar YA borrada (o fuera de la cola) cuando esto se llama, o
         * la pasada nueva la vuelve a tomar.
         */
        fun restart(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                request(),
            )
        }
    }
}
