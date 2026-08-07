package com.arkiv.player.torrent

import android.util.Log
import org.libtorrent4j.SessionManager
import org.libtorrent4j.TorrentHandle
import java.io.File

/**
 * Descarga de torrent PERSISTENTE: baja el archivo completo y NO lo borra al terminar.
 *
 * Es la excepción explícita a la regla del motor, documentada en `TorrentEngine`: "descarga al
 * cacheDir y borra al parar". Acá el `savePath` es siempre el `saveDir` pedido por el llamador, fuera
 * del `workDir` que barre `sweepOrphans()`, y [detach] quita el torrent de la sesión SIN el flag de
 * borrado de archivos.
 *
 * El [handle] es SIEMPRE exclusivamente nuestro: `TorrentEngine.startPersistentDownload` nunca lo
 * comparte con un stream activo ni con otra descarga (si el infohash ya tiene un handle en la
 * sesión, devuelve `null` en vez de adjuntarse — ver el KDoc de ese método para el porqué). Por eso
 * [detach] con `session.remove(handle)` incondicional es seguro: nunca puede sacar de la sesión el
 * torrent de otra pantalla.
 *
 * El progreso se mide POR ARCHIVO (`handle.fileProgress()[fileIndex]` contra el tamaño real del
 * archivo, no con `status().totalWantedDone()/totalWanted()`): aunque el handle no se comparte, ese
 * agregado redondea a granularidad de PIEZA, así que en los bordes del archivo (pieza compartida con
 * el archivo IGNORE vecino en el pack) podía sobrestimar bytes; `fileProgress()` da el byte count
 * exacto de este archivo sin ese error de redondeo.
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
