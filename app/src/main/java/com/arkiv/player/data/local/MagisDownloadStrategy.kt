package com.arkiv.player.data.local

import com.arkiv.player.data.ArkivRepository
import com.arkiv.player.data.gateway.FuenteDeContenido
import java.io.File

/**
 * Magis: resuelve el `ref` guardado contra el gateway y baja el archivo del CDN.
 *
 * El CDN exige `Content-Auth` y `Content-License`, que llegan en la respuesta de `/v1/resolve`.
 * Esos tokens hacen falta para **bajar**, no para reproducir: una vez en disco el archivo se
 * reproduce como cualquier otro, sin depender de que el token siga vivo.
 *
 * Se resuelve en el momento de la descarga (no al encolar) porque el token del CDN vive ~48 h: un
 * ítem que estuvo en cola un día llegaría con el token vencido.
 */
class MagisDownloadStrategy(
    private val repo: ArkivRepository,
    private val gateway: FuenteDeContenido,
    private val http: HttpRangeDownloader,
) : DownloadStrategy {

    override suspend fun download(
        episodeId: String,
        alreadyConfirmed: Boolean,
        targetDir: File,
        onProgress: (Long, Long) -> Unit,
    ): DownloadOutcome {
        val ref = repo.magisRefForEpisode(episodeId)
            ?: return DownloadOutcome.Failed("No se encontró la fuente de Magis")

        val reproducible = runCatching { gateway.resolve(ref) }.getOrElse {
            return DownloadOutcome.Failed(
                it.message ?: "No se pudo resolver la fuente de Magis",
                transient = DownloadRetryPolicy.isTransient(it),
            )
        }
        if (reproducible.url.isBlank()) {
            return DownloadOutcome.Failed("Magis no devolvió un archivo descargable")
        }

        // La extensión sale de la URL del CDN (`..._media.ts` / `..._media.mp4`): el contenedor
        // importa para que el reproductor local elija bien el demuxer.
        val extension = reproducible.url.substringAfterLast('.', "mp4").take(4)
        val target = File(targetDir, LocalFilePaths.fileNameFor(episodeId, "magis.$extension"))

        // resumeKey explícito: la URL trae un token que cambia en cada resolución, así que usarla
        // como clave (el default) descartaría el `.part` en cada reintento y volvería a empezar.
        return http.download(
            reproducible.url,
            target,
            reproducible.headers,
            resumeKey = episodeId,
            onProgress = onProgress,
        ).fold(
            onSuccess = { DownloadOutcome.Done(it) },
            onFailure = {
                DownloadOutcome.Failed(
                    it.message ?: "Falló la descarga",
                    transient = DownloadRetryPolicy.isTransient(it),
                )
            },
        )
    }
}
