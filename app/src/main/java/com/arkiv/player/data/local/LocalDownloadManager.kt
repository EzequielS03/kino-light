package com.arkiv.player.data.local

import android.content.Context
import android.os.Environment
import android.os.StatFs
import com.arkiv.player.data.db.ArkivDatabase
import com.arkiv.player.data.db.DownloadEntity
import com.arkiv.player.data.db.DownloadRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File

/** Qué pasó al encolar. La UI solo necesita distinguir "se encoló" de "ya lo tenías". */
enum class EnqueueOutcome {
    QUEUED,

    /** Ya había una fila en curso (encolada, bajando, …) para ESTE episodio. */
    ALREADY_QUEUED,

    /** Ese contenido ya está en el dispositivo: este episodio, o su gemelo bajo otro ítem. */
    ALREADY_DOWNLOADED,
}

/**
 * Fachada de las descargas al dispositivo: lo único que toca la UI. Encola en Room y despierta al
 * worker; no baja nada por su cuenta.
 */
class LocalDownloadManager(
    context: Context,
    db: ArkivDatabase,
    /** Inyectado para poder testear sin WorkManager; en producción es `LocalDownloadWorker::schedule`. */
    private val wakeWorker: (Context) -> Unit,
    /**
     * Corta la pasada que está corriendo y relanza la cola. En producción es
     * `LocalDownloadWorker::restart`. Es lo único que puede detener de verdad una descarga en curso:
     * la fachada no tiene forma de hablarle a la estrategia que está adentro del worker.
     */
    private val restartWorker: (Context) -> Unit,
) {
    private val appContext = context.applicationContext
    private val downloadDao = db.downloadDao()
    private val itemDao = db.itemDao()

    /** `Android/data/<pkg>/files/Movies`. Cae a filesDir si no hay almacenamiento externo montado. */
    fun targetDir(): File =
        (appContext.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: File(appContext.filesDir, "Movies"))
            .apply { mkdirs() }

    fun observeRows(): Flow<List<DownloadRow>> = downloadDao.observeDownloadRows()

    /** Mide el disco real y delega la decisión en [FreeSpacePolicy], que es lo testeable. */
    fun hasFreeSpaceFor(bytes: Long): Boolean =
        FreeSpacePolicy.fits(StatFs(targetDir().absolutePath).availableBytes, bytes)

    /**
     * Bytes disponibles en el disco donde viven las descargas. Es la misma medición que usa
     * [hasFreeSpaceFor], expuesta para poder MOSTRARLA: la biblioteca del TV la necesita para que el
     * disco lleno deje de ser una sorpresa. Bloqueante (toca el filesystem), así que se llama fuera
     * del hilo principal o dentro de un `produceState`.
     */
    fun espacioLibreBytes(): Long = StatFs(targetDir().absolutePath).availableBytes

    /**
     * Encola un episodio. Idempotente: si ya hay una fila que no falló, no hace nada — así tocar dos
     * veces el botón no duplica la descarga.
     *
     * Y un paso más: tampoco encola si ESE MISMO CONTENIDO ya está descargado bajo otro ítem de la
     * biblioteca (la misma serie guardada dos veces, ver [DuplicateDownloadPolicy]). Eso evita bajar
     * los mismos gigabytes dos veces incluso con los ítems duplicados que ya existen, que no se
     * migran. El resultado le dice al llamador qué pasó para que la UI pueda avisarle al usuario que
     * ya lo tiene (ver `DuplicateDownloadPolicy.skippedNotice`).
     */
    suspend fun enqueue(episodeId: String, source: String): EnqueueOutcome = withContext(Dispatchers.IO) {
        val existing = downloadDao.get(episodeId)
        if (existing != null && existing.state != LocalDownloadState.FAILED) {
            return@withContext if (existing.state == LocalDownloadState.COMPLETED) {
                EnqueueOutcome.ALREADY_DOWNLOADED
            } else {
                EnqueueOutcome.ALREADY_QUEUED
            }
        }
        val target = EpisodeOrigin(episodeId, itemDao.getEpisode(episodeId)?.torrentFileIndex)
        val twin = DuplicateDownloadPolicy.completedDuplicateOf(target, downloadDao.completedOrigins())
        if (twin != null) return@withContext EnqueueOutcome.ALREADY_DOWNLOADED
        downloadDao.upsert(
            DownloadEntity(
                episodeId = episodeId,
                variant = "",
                state = LocalDownloadState.QUEUED,
                progress = 0f,
                localUri = null,
                bytes = 0,
                source = source,
                createdAt = System.currentTimeMillis(),
            )
        )
        wakeWorker(appContext)
        EnqueueOutcome.QUEUED
    }

    /** El usuario aceptó bajar un torrent que superaba el umbral de tamaño. */
    suspend fun confirmSize(episodeId: String) = withContext(Dispatchers.IO) {
        downloadDao.markConfirmed(episodeId)
        wakeWorker(appContext)
    }

    /**
     * Vuelve a encolar una fila fallida. El `.part` que haya quedado se conserva a propósito: el
     * descargador reanuda desde ahí con `Range` en vez de empezar de cero.
     */
    suspend fun retry(episodeId: String) = withContext(Dispatchers.IO) {
        val row = downloadDao.get(episodeId) ?: return@withContext
        if (!DownloadQueuePolicy.isRetryable(row.state)) return@withContext
        // Una fila vieja pudo quedar apuntando a la estrategia equivocada (ver [FuenteDeDescarga]);
        // reencolarla tal cual la haría fallar con el mismo mensaje para siempre.
        val fuente = FuenteDeDescarga.para(episodeId)
        if (row.source != fuente) downloadDao.updateSource(episodeId, fuente)
        downloadDao.updateState(episodeId, LocalDownloadState.QUEUED, null)
        wakeWorker(appContext)
    }

    /**
     * DETIENE una descarga en curso sin borrar nada: la fila queda `failed` con motivo "Cancelada"
     * y el `.part` (o el directorio del torrent) intacto, así que "Reintentar" reanuda desde donde
     * iba en vez de empezar de cero.
     *
     * Cómo llega la señal hasta la estrategia: no hay canal directo con el worker, así que se corta
     * el worker entero ([restartWorker], que es un `enqueueUniqueWork` con REPLACE). La corrutina
     * recibe la cancelación, la estrategia de torrent suelta el handle en su `finally` y el
     * descargador HTTP corta el bucle de escritura en su `ensureActive()`. La pasada nueva que
     * REPLACE deja encolada toma la siguiente fila de la cola.
     *
     * Solo corta si esta fila es la que está en vuelo: la cola es de UNA a la vez, así que una fila
     * en `downloading`/`staging` ES la que está corriendo, y una en `queued` no está corriendo nada
     * (cortar por ella mataría la descarga ajena que sí está en curso).
     */
    suspend fun cancel(episodeId: String) = withContext(Dispatchers.IO) {
        val row = downloadDao.get(episodeId) ?: return@withContext
        if (DownloadQueuePolicy.isTerminal(row.state)) return@withContext
        val inFlight = row.state == LocalDownloadState.DOWNLOADING || row.state == LocalDownloadState.STAGING
        // El estado se escribe ANTES de cortar: si no, la pasada nueva encuentra la fila todavía en
        // `downloading` y la vuelve a tomar de inmediato (nextToProcess prefiere lo ya empezado).
        downloadDao.updateState(episodeId, LocalDownloadState.FAILED, "Cancelada")
        if (inFlight) restartWorker(appContext)
    }

    /**
     * Borra la fila y el archivo (y el parcial, si quedó a medias). Si la descarga está corriendo,
     * primero la DETIENE: sin eso la estrategia seguía trabajando sobre una fila que ya no existe —
     * el torrent seguía escribiendo en el `workDir` recién borrado y, al terminar, movía varios GB a
     * un archivo que ninguna fila referenciaba (disco muerto permanente), y la descarga HTTP seguía
     * gastando datos móviles escribiendo a un inode ya desenlazado.
     */
    suspend fun remove(episodeId: String) = withContext(Dispatchers.IO) {
        val row = downloadDao.get(episodeId)
        val inFlight = row != null &&
            (row.state == LocalDownloadState.DOWNLOADING || row.state == LocalDownloadState.STAGING)
        // Orden deliberado: 1) sacar la fila de la cola, 2) cortar el worker, 3) recién ahí borrar
        // los archivos. Si se borrara primero, la pasada nueva podría volver a tomar la fila; si se
        // cortara sin borrar la fila, ídem. Queda una ventana mínima en la que la estrategia todavía
        // no se enteró de la cancelación y puede recrear su directorio de trabajo: es benigna,
        // porque la propia estrategia limpia lo suyo al salir y el archivo final ya no se produce.
        downloadDao.delete(episodeId)
        if (inFlight) restartWorker(appContext)
        val path = row?.filePath ?: row?.localUri?.removePrefix("file://")
        // Dos filas pueden compartir el MISMO archivo: cuando el worker encuentra que ese contenido
        // ya estaba en disco bajo otro ítem, adopta el archivo del gemelo en vez de re-descargarlo
        // (ver LocalDownloadWorker.adoptTwinIfAlreadyDownloaded). Borrarlo desde CUALQUIERA de las
        // dos dejaría a la otra diciendo "Listo" sobre un archivo que ya no está.
        //
        // El filtro se aplica a los DOS caminos de borrado de acá abajo, no solo al explícito: el
        // barrido por prefijo borra por NOMBRE, y el archivo compartido se llama con el episodeId
        // del gemelo ORIGINAL. O sea que quitar al adoptante efectivamente no lo toca, pero quitar
        // al original sí lo barría aunque el borrado explícito lo hubiera salteado — el archivo
        // desaparecía y el adoptante quedaba mintiendo. Ver DuplicateDownloadPolicy.deletablePaths.
        val referenced = downloadDao.filePathsReferencedByOthers(episodeId).toSet()
        if (path != null && DuplicateDownloadPolicy.canDeleteFile(path, referenced)) {
            // Cubre el nombre exacto que dejaron descargas viejas (pre-migración), que puede no
            // seguir el patrón sanitize(episodeId) + extensión que arma LocalFilePaths.fileNameFor.
            val file = File(path)
            runCatching { file.delete() }
            runCatching { LocalFilePaths.partOf(file).delete() }
            runCatching { LocalFilePaths.originOf(file).delete() }
        }
        // Barrido por prefijo: para archive/web el nombre destino es determinista
        // (LocalFilePaths.fileNameFor = sanitize(episodeId) + extensión), así que esto cubre el
        // archivo final Y el ".part" (y su marca de origen ".part.src") aunque la fila todavía no
        // tenga filePath (QUEUED/DOWNLOADING, que es cuando el usuario más suele tocar "Quitar").
        // Sin esto el .part queda huérfano: nadie más lo referencia ni lo limpia, y se come el disco
        // justo lo que FreeSpacePolicy protege. Los .part nunca son el filePath de otra fila, así
        // que el filtro de compartidos no cambia nada para ellos.
        val prefix = "${LocalFilePaths.sanitize(episodeId)}."
        runCatching {
            val candidates = targetDir().listFiles { f -> f.name.startsWith(prefix) }.orEmpty()
                .map { it.absolutePath }
            DuplicateDownloadPolicy.deletablePaths(candidates, referenced)
                .forEach { p -> runCatching { File(p).delete() } }
        }
        runCatching { File(targetDir(), "torrents/${LocalFilePaths.torrentDirName(episodeId)}").deleteRecursively() }
    }
}
