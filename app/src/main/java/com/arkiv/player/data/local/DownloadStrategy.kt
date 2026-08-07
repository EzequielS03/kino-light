package com.arkiv.player.data.local

import java.io.File

/** Resultado de intentar bajar un episodio. */
sealed interface DownloadOutcome {
    data class Done(val file: File) : DownloadOutcome
    /** Torrent que supera el umbral: no se bajó nada, espera confirmación del usuario. */
    data class NeedsConfirmation(val fileSizeBytes: Long) : DownloadOutcome
    data class Failed(val reason: String) : DownloadOutcome
}

/**
 * Cómo se baja UNA fuente. El worker elige la implementación por `source` y no sabe nada de
 * libtorrent, de la NUC ni de archive.org.
 *
 * Las estrategias resuelven el origen consultando el repositorio por `episodeId` (igual que hace hoy
 * `PlayerViewModel`), en vez de recibirlo por parámetro: así la tabla `downloads` no duplica datos
 * que ya viven en `items`/`episodes` y no hay dos fuentes de verdad que se puedan desincronizar.
 */
interface DownloadStrategy {
    suspend fun download(
        episodeId: String,
        alreadyConfirmed: Boolean,
        targetDir: File,
        onProgress: (bytesDone: Long, totalBytes: Long) -> Unit,
    ): DownloadOutcome
}
