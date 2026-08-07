package com.arkiv.player.data.local

import android.util.Log
import com.arkiv.player.data.ArkivRepository
import com.arkiv.player.data.EpisodeTorrent
import com.arkiv.player.torrent.TorrentEngine
import com.arkiv.player.torrent.TorrentMeta
import kotlinx.coroutines.delay
import java.io.File

/**
 * Torrent: baja el archivo completo a un directorio persistente y lo mueve a su nombre final.
 *
 * Acá vive la compuerta de tamaño (ver [TorrentSizeGate]): se aplica DESPUÉS de resolver la
 * metadata, que es el primer momento en que se conoce el peso del archivo elegido — y no el del pack.
 */
class TorrentDownloadStrategy(
    private val repo: ArkivRepository,
    private val engine: TorrentEngine,
    /** `LocalDownloadManager::hasFreeSpaceFor`. Inyectado para no meter `StatFs` acá. */
    private val hasSpace: (Long) -> Boolean,
) : DownloadStrategy {

    override suspend fun download(
        episodeId: String,
        alreadyConfirmed: Boolean,
        targetDir: File,
        onProgress: (Long, Long) -> Unit,
    ): DownloadOutcome {
        val src = repo.torrentSourceForEpisode(episodeId)
            ?: return DownloadOutcome.Failed("No se encontró el torrent guardado")

        val (meta, fileIndex) = resolve(src) ?: return DownloadOutcome.Failed("No se pudo leer el torrent")
        val file = meta.files.getOrNull(fileIndex)
            ?: return DownloadOutcome.Failed("El torrent no tiene el archivo pedido")

        if (TorrentSizeGate.needsConfirmation(file.sizeBytes, alreadyConfirmed)) {
            Log.i(TAG, "compuerta de tamaño: ${file.name} pesa ${TorrentSizeGate.formatSize(file.sizeBytes)}")
            return DownloadOutcome.NeedsConfirmation(file.sizeBytes)
        }

        // Mismo momento que la compuerta de tamaño: resolver la metadata es lo primero que revela
        // cuánto pesa el archivo, así que es lo antes que se puede fallar por espacio sin haber
        // bajado nada.
        if (!hasSpace(file.sizeBytes)) {
            return DownloadOutcome.Failed("No hay espacio suficiente en el dispositivo")
        }

        val workDir = File(targetDir, "torrents/${LocalFilePaths.torrentDirName(episodeId)}")
        // `startPersistentDownload` devuelve null por varias causas que NO se pueden distinguir desde
        // acá sin cambiar su firma (ver su KDoc en TorrentEngine): índice de archivo inválido, el
        // arranque en frío no llegó a tiempo, o —la más común en la práctica— que el infohash YA tiene
        // un handle activo (el usuario está viendo otro episodio del mismo pack ahora mismo) y el
        // método a propósito no se le "pega" para no arriesgar esa reproducción. Como no hay forma
        // honesta de saber cuál de las tres pasó, el mensaje cubre la causa más probable y da una
        // salida accionable en vez de sonar a fallo definitivo.
        val download = engine.startPersistentDownload(meta, fileIndex, workDir)
        if (download == null) {
            // `startPersistentDownload` puede haber llegado a crear `workDir` (mkdirs) antes de
            // fallar por timeout esperando el handle, sin dejar ningún handle vivo que lo limpie: se
            // limpia acá para no dejar un directorio vacío/huérfano colgado (no-op si nunca se creó).
            runCatching { workDir.deleteRecursively() }
            return DownloadOutcome.Failed(
                "No se pudo iniciar la descarga: el torrent podría estar en uso ahora mismo " +
                    "(reproduciéndose u otra descarga en curso). Cerrá el reproductor y probá de nuevo " +
                    "en unos minutos.",
            )
        }

        var lastBytes = 0L
        var stalledMs = 0L
        while (!download.isComplete()) {
            delay(POLL_MS)
            val done = download.bytesDone()
            onProgress(done, download.totalBytes())
            // Corte por ESTANCAMIENTO, no por tiempo total: `bytesDone()` es monótono no decreciente,
            // así que basarse en "done == 0" deja el corte muerto para siempre apenas se baja el primer
            // byte. Nada de tope de tiempo total tampoco — un torrent legítimo con pocos seeds puede
            // tardar horas y matarlo por reloj sería peor que el bug (a diferencia de
            // `PREBUFFER_CAP_MS` en PlayerViewModel, que SÍ es un tope de 30s de tiempo total, pero para
            // esperar el buffer de cabeza+cola del streaming, no para bajar el archivo completo).
            stalledMs = if (done > lastBytes) 0 else stalledMs + POLL_MS
            lastBytes = done
            if (stalledMs >= STALL_TIMEOUT_MS) {
                download.discard()
                return if (done == 0L) {
                    DownloadOutcome.Failed("No se encontró ningún peer para este torrent")
                } else {
                    DownloadOutcome.Failed("La descarga se estancó sin peers y no pudo continuar")
                }
            }
        }

        download.detach()
        val downloaded = download.file()
        if (!downloaded.exists()) {
            // El torrent se reporta completo pero el archivo esperado no está: no queda nada que
            // conservar, así que se limpia el directorio de trabajo igual que en el resto de las salidas.
            runCatching { workDir.deleteRecursively() }
            return DownloadOutcome.Failed("El torrent terminó pero no dejó archivo")
        }

        // Mover a <targetDir>/<episodeId>.<ext> para que todas las fuentes dejen el archivo con el
        // mismo esquema de nombre y LocalLibrary no tenga que saber de dónde vino.
        val target = File(targetDir, LocalFilePaths.fileNameFor(episodeId, downloaded.name))
        if (target.exists()) target.delete()
        val moved = downloaded.renameTo(target)
        if (!moved) {
            // `renameTo` suele fallar por cruce de filesystem; el `copyTo` de respaldo puede a su vez
            // fallar por disco lleno o permisos. Si eso pasa, el archivo original queda intacto (el
            // `delete()` no llega a correr) pero hay que devolver `Failed` en vez de dejar propagar la
            // excepción, que rompería el contrato del resto de esta función.
            val copyResult = runCatching {
                downloaded.copyTo(target, overwrite = true)
                downloaded.delete()
            }
            if (copyResult.isFailure) {
                runCatching { workDir.deleteRecursively() }
                val reason = copyResult.exceptionOrNull()?.message ?: "motivo desconocido"
                return DownloadOutcome.Failed("No se pudo mover el archivo descargado: $reason")
            }
        }
        runCatching { workDir.deleteRecursively() }
        return DownloadOutcome.Done(target)
    }

    /** Devuelve (metadata, índice del archivo a bajar) resolviendo bytes o magnet. */
    private suspend fun resolve(src: EpisodeTorrent): Pair<TorrentMeta, Int>? = when (src) {
        is EpisodeTorrent.Bytes -> engine.resolveTorrent(src.data)?.let { it to src.fileIndex }
        is EpisodeTorrent.Magnet -> engine.resolveMagnet(src.uri)?.let { meta ->
            val picked = engine.pickVideo(meta) ?: return null
            meta to picked.index
        }
    }

    private companion object {
        const val TAG = "ArkivTorrentDl"
        const val POLL_MS = 1_000L
        const val STALL_TIMEOUT_MS = 180_000L
    }
}
