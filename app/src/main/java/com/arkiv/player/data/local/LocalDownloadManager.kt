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
     * Borra un item de la NUC. Inyectado para no acoplar la fachada al cliente REST. Sin default a
     * propósito: `wakeWorker` tampoco lo tiene, y un default que no borra nada (`{ false }`)
     * convertiría un olvido de cableado en `AppGraph` en un barrido que corre sin error y sin
     * lograr nada — el disco de la NUC se seguiría llenando y nadie se enteraría hasta que
     * `fits` empezara a rechazar trabajos. Mejor que sea un error de compilación.
     */
    private val deleteNucItem: suspend (Long) -> Boolean,
) {
    private val appContext = context.applicationContext
    private val downloadDao = db.downloadDao()

    /** `Android/data/<pkg>/files/Movies`. Cae a filesDir si no hay almacenamiento externo montado. */
    fun targetDir(): File =
        (appContext.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: File(appContext.filesDir, "Movies"))
            .apply { mkdirs() }

    fun observeRows(): Flow<List<DownloadRow>> = downloadDao.observeDownloadRows()

    /** Mide el disco real y delega la decisión en [FreeSpacePolicy], que es lo testeable. */
    fun hasFreeSpaceFor(bytes: Long): Boolean =
        FreeSpacePolicy.fits(StatFs(targetDir().absolutePath).availableBytes, bytes)

    /**
     * Encola un episodio. Idempotente: si ya hay una fila que no falló, no hace nada — así tocar dos
     * veces el botón no duplica la descarga.
     */
    suspend fun enqueue(episodeId: String, source: String) = withContext(Dispatchers.IO) {
        val existing = downloadDao.get(episodeId)
        if (existing != null && existing.state != LocalDownloadState.FAILED) return@withContext
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
        downloadDao.updateState(episodeId, LocalDownloadState.QUEUED, null)
        wakeWorker(appContext)
    }

    /** Borra la fila y el archivo (y el parcial, si quedó a medias). */
    suspend fun remove(episodeId: String) = withContext(Dispatchers.IO) {
        val row = downloadDao.get(episodeId)
        val path = row?.filePath ?: row?.localUri?.removePrefix("file://")
        if (path != null) {
            // Cubre el nombre exacto que dejaron descargas viejas (pre-migración), que puede no
            // seguir el patrón sanitize(episodeId) + extensión que arma LocalFilePaths.fileNameFor.
            val file = File(path)
            runCatching { file.delete() }
            runCatching { LocalFilePaths.partOf(file).delete() }
        }
        // Barrido por prefijo: para archive/web el nombre destino es determinista
        // (LocalFilePaths.fileNameFor = sanitize(episodeId) + extensión), así que esto cubre el
        // archivo final Y el ".part" aunque la fila todavía no tenga filePath (QUEUED/DOWNLOADING,
        // que es cuando el usuario más suele tocar "Quitar"). Sin esto el .part queda huérfano: nadie
        // más lo referencia ni lo limpia, y se come el disco justo lo que FreeSpacePolicy protege.
        val prefix = "${LocalFilePaths.sanitize(episodeId)}."
        runCatching {
            targetDir().listFiles { f -> f.name.startsWith(prefix) }
                ?.forEach { f -> runCatching { f.delete() } }
        }
        runCatching { File(targetDir(), "torrents/${LocalFilePaths.torrentDirName(episodeId)}").deleteRecursively() }
        downloadDao.delete(episodeId)
    }

    /**
     * Borra de la NUC los items que ya se transfirieron al dispositivo pero cuyo DELETE falló en su
     * momento (blog caído, red cortada). Sin esto el disco de la NUC se llena de archivos que ya
     * nadie va a reproducir, y `fits` empieza a rechazar trabajos nuevos.
     */
    suspend fun sweepNucOrphans() = withContext(Dispatchers.IO) {
        for (itemId in downloadDao.orphanStagingItems()) {
            if (runCatching { deleteNucItem(itemId) }.getOrDefault(false)) {
                downloadDao.clearStagingItem(itemId)
            }
        }
    }
}
