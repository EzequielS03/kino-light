package com.arkiv.player.data.local

import java.io.File

/** Resultado de intentar bajar un episodio. */
sealed interface DownloadOutcome {
    data class Done(val file: File) : DownloadOutcome
    /** Legacy of the torrent size gate (source removed in this branch's pruning): no strategy returns this today. */
    data class NeedsConfirmation(val fileSizeBytes: Long) : DownloadOutcome
    /**
     * [transient] = "esto puede andar en un rato" (corte de red, 5xx, torrent en uso). El worker lo
     * usa para decidir si devuelve `Result.retry()` (backoff de WorkManager) o marca la fila
     * `failed`. Por defecto false: un motivo nuevo que nadie clasificó no debe reintentarse solo.
     * Ver [DownloadRetryPolicy].
     */
    data class Failed(val reason: String, val transient: Boolean = false) : DownloadOutcome
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

    /**
     * Borra lo que [download] dejó FUERA de [targetDir], si dejó algo.
     *
     * `LocalDownloadManager.remove` sabe borrar un archivo y barrer por prefijo el directorio de
     * descargas, y con eso alcanzaba mientras toda descarga fue un archivo. Caracol no lo es: sus
     * bytes viven en un caché de media3 compartido por todos los capítulos, y quién sabe cuáles son
     * de cuál es su propia estrategia. Sin este gancho, "Quitar" borraba la fila y dejaba los megas
     * ocupando disco para siempre.
     *
     * Vacío por defecto: una estrategia que solo escribe un archivo no tiene nada que agregar.
     */
    suspend fun borrarRestos(episodeId: String, targetDir: File) = Unit
}
