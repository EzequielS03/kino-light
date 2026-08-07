package com.arkiv.player.torrent

import android.util.Log
import org.libtorrent4j.SessionManager
import org.libtorrent4j.TorrentHandle
import java.io.File

/**
 * Descarga de torrent PERSISTENTE: baja el archivo completo y NO lo borra al terminar.
 *
 * Es la excepción explícita a la regla del motor, documentada en `TorrentEngine`: "descarga al
 * cacheDir y borra al parar". Acá el `savePath` está en `filesDir` (o el que ya tenga el handle si
 * `TorrentEngine.startPersistentDownload` lo compartió con un stream activo u otra descarga del
 * mismo pack — ver `attachToExistingHandle`), fuera del `workDir` que barre `sweepOrphans()`, y
 * [detach] quita el torrent de la sesión SIN el flag de borrado de archivos.
 *
 * El progreso se mide POR ARCHIVO (`handle.fileProgress()[fileIndex]` contra el tamaño real del
 * archivo), no con `status().totalWantedDone()/totalWanted()` (agregado de TODO lo "wanted" en el
 * handle): el handle puede estar COMPARTIDO con un stream activo o con otra descarga persistente del
 * mismo pack, y en ese caso el agregado del torrent entero incluiría piezas de otros archivos —
 * `fileProgress()` da el byte count exacto de este archivo sin importar qué más esté priorizado.
 */
class PersistentTorrentDownload internal constructor(
    private val session: SessionManager,
    private val handle: TorrentHandle,
    private val saveDir: File,
    private val relativePath: String,
    private val fileIndex: Int,
    private val fileSizeBytes: Long,
) {

    fun bytesDone(): Long =
        runCatching { handle.fileProgress()[fileIndex] }.getOrDefault(0L)

    /** Tamaño real del archivo (de la metadata del torrent), no depende de qué esté priorizado. */
    fun totalBytes(): Long = fileSizeBytes

    fun isComplete(): Boolean {
        val total = totalBytes()
        return total > 0 && bytesDone() >= total
    }

    /** Ruta real del archivo en disco: para un torrent multi-archivo incluye la carpeta del pack. */
    fun file(): File = File(saveDir, relativePath)

    /** Quita el torrent de la sesión conservando lo descargado. */
    fun detach() {
        runCatching { session.remove(handle) }
            .onFailure { Log.w(TAG, "detach: $it") }
    }

    /** Quita el torrent y borra lo descargado (cancelar). */
    fun discard() {
        detach()
        runCatching { saveDir.deleteRecursively() }
            .onFailure { Log.w(TAG, "discard: $it") }
    }

    private companion object { const val TAG = "ArkivTorrentDl" }
}
