package com.arkiv.player.data.local

/**
 * Estados de una fila de la tabla `downloads`. Son strings y no un enum porque Room ya los guarda
 * así desde la primera versión de la tabla y cambiarlo obligaría a un converter + migración de datos
 * sin ganar nada.
 */
object LocalDownloadState {
    const val QUEUED = "queued"
    /** Torrent que supera el umbral de tamaño: espera confirmación del usuario, no baja nada. */
    const val NEEDS_CONFIRMATION = "needs_confirmation"
    /** Solo web: la NUC está bajando el archivo, todavía no empezó la transferencia al dispositivo. */
    const val STAGING = "staging"
    const val DOWNLOADING = "downloading"
    const val COMPLETED = "completed"
    const val FAILED = "failed"
}

/** Fila mínima de la cola: lo único que la política necesita para decidir. */
data class QueueRow(val episodeId: String, val state: String, val createdAt: Long)

/**
 * Decide qué fila procesa el worker. La cola es de UNA a la vez (ver el spec: `TorrentEngine` es de
 * un stream activo, el disco de blog no aguanta varios staging, y el ancho de banda del Fire TV no
 * sobra), así que esto devuelve una sola fila o null.
 */
object DownloadQueuePolicy {

    /**
     * Lo ya empezado (`downloading` / `staging`) gana sobre lo encolado: si la app se mató a mitad de
     * una descarga de 4 GB, retomarla vale más que arrancar otra desde cero. Entre iguales, la más
     * vieja primero (FIFO).
     */
    fun nextToProcess(rows: List<QueueRow>): QueueRow? {
        val inFlight = rows.filter { it.state == LocalDownloadState.DOWNLOADING || it.state == LocalDownloadState.STAGING }
        if (inFlight.isNotEmpty()) return inFlight.minByOrNull { it.createdAt }
        return rows.filter { it.state == LocalDownloadState.QUEUED }.minByOrNull { it.createdAt }
    }

    fun isTerminal(state: String): Boolean =
        state == LocalDownloadState.COMPLETED || state == LocalDownloadState.FAILED

    fun isRetryable(state: String): Boolean =
        state == LocalDownloadState.FAILED || state == LocalDownloadState.NEEDS_CONFIRMATION
}
