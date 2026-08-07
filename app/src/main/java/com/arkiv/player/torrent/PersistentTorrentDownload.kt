package com.arkiv.player.torrent

import android.util.Log
import org.libtorrent4j.SessionManager
import org.libtorrent4j.TorrentHandle
import java.io.File

/**
 * Descarga de torrent PERSISTENTE: baja el archivo completo y NO lo borra al terminar.
 *
 * Es la excepción explícita a la regla del motor, documentada en `TorrentEngine`: "descarga al
 * cacheDir y borra al parar". Acá el `savePath` está en `filesDir`, fuera del `workDir` que barre
 * `sweepOrphans()`, y [detach] quita el torrent de la sesión SIN el flag de borrado de archivos.
 */
class PersistentTorrentDownload internal constructor(
    private val session: SessionManager,
    private val handle: TorrentHandle,
    private val saveDir: File,
    private val relativePath: String,
) {

    fun bytesDone(): Long =
        runCatching { handle.status().totalWantedDone() }.getOrDefault(0L)

    /** Solo el archivo elegido está en `wanted` (el resto del pack quedó en IGNORE). */
    fun totalBytes(): Long =
        runCatching { handle.status().totalWanted() }.getOrDefault(0L)

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
