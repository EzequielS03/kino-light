package com.arkiv.player.data.local

import com.arkiv.player.data.ArchiveUrls
import com.arkiv.player.data.ArkivRepository
import com.arkiv.player.data.Quality
import com.arkiv.player.data.SettingsStore
import com.arkiv.player.data.model.Episode
import com.arkiv.player.data.model.VideoVariant
import java.io.File

/**
 * archive.org: descarga HTTP directa de la variante elegida. Reemplaza al `Downloader` viejo, que
 * usaba el `DownloadManager` del sistema — ver el spec para por qué se unificó a OkHttp.
 */
class ArchiveDownloadStrategy(
    private val repo: ArkivRepository,
    private val settings: SettingsStore,
    private val http: HttpRangeDownloader,
    /** `LocalDownloadManager::hasFreeSpaceFor`. Inyectado para no meter `StatFs` acá. */
    private val hasSpace: (Long) -> Boolean,
) : DownloadStrategy {

    override suspend fun download(
        episodeId: String,
        alreadyConfirmed: Boolean,
        targetDir: File,
        onProgress: (Long, Long) -> Unit,
    ): DownloadOutcome {
        val episode = repo.getEpisode(episodeId)
            ?: return DownloadOutcome.Failed("No se encontró el episodio")
        val variant = variantFor(episode)
            ?: return DownloadOutcome.Failed("Este episodio no tiene un archivo descargable")

        // archive.org publica el tamaño en la metadata, así que acá se sabe ANTES de bajar un byte.
        if (!hasSpace(variant.sizeBytes)) {
            return DownloadOutcome.Failed("No hay espacio suficiente en el dispositivo")
        }

        val url = ArchiveUrls.download(episode.itemId, variant.path)
        val target = File(targetDir, LocalFilePaths.fileNameFor(episodeId, variant.path))

        // resumeKey por defecto = la URL: es justo lo que hace falta acá. Si el usuario cambia
        // `downloadQuality` entre dos intentos, `variantFor` elige otra variante, la URL cambia y el
        // `.part` de la variante anterior se descarta en vez de mezclarse con el archivo nuevo.
        return http.download(url, target, mapOf("User-Agent" to USER_AGENT), onProgress = onProgress)
            .fold(
                onSuccess = { DownloadOutcome.Done(it) },
                onFailure = {
                    DownloadOutcome.Failed(
                        it.message ?: "Falló la descarga",
                        transient = DownloadRetryPolicy.isTransient(it),
                    )
                },
            )
    }

    /** Misma elección de variante que hacía el Downloader viejo, para no cambiar de comportamiento. */
    private fun variantFor(episode: Episode): VideoVariant? = when (settings.downloadQuality.value) {
        Quality.DERIVATIVE -> episode.derivative ?: episode.original
        Quality.ORIGINAL -> episode.original ?: episode.derivative
    }

    private companion object {
        const val USER_AGENT = "Arkiv/0.1 (personal)"
    }
}
