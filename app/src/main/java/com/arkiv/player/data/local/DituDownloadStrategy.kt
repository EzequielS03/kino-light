package com.arkiv.player.data.local

import android.util.Log
import com.arkiv.player.data.ArkivRepository
import com.arkiv.player.data.SeriesItemIds
import com.arkiv.player.data.db.DownloadDao
import com.arkiv.player.data.gateway.ArkivApiClient
import com.arkiv.player.data.offline.ArkivOfflineApi
import com.arkiv.player.data.offline.NucDownloadItem
import kotlinx.coroutines.delay
import java.io.File

/**
 * Descarga episodios de Ditu (Caracol Streaming) via el backend offline.
 *
 * El stream de Ditu es MPEG-DASH (.mpd), no un archivo único descargable. Se delega al backend
 * (arkiv-offline + yt-dlp) usando el mismo patrón de staging de NucStagedStrategy, pero primero
 * se resuelve el ref de Ditu contra el gateway para obtener la URL del .mpd y pasarla directo al
 * backend (sin necesidad de web resolver — yt-dlp maneja DASH natively).
 */
class DituDownloadStrategy(
    private val repo: ArkivRepository,
    private val gateway: ArkivApiClient,
    private val api: ArkivOfflineApi,
    private val http: HttpRangeDownloader,
    private val dao: DownloadDao,
) : DownloadStrategy {

    override suspend fun download(
        episodeId: String,
        alreadyConfirmed: Boolean,
        targetDir: File,
        onProgress: (Long, Long) -> Unit,
    ): DownloadOutcome {
        val ref = repo.dituRefForEpisode(episodeId)
            ?: return DownloadOutcome.Failed("No se encontró el ref de Ditu para este episodio")

        val reproducible = runCatching { gateway.resolve(ref) }.getOrElse {
            return DownloadOutcome.Failed("No se pudo resolver el stream de Ditu: ${it.message}")
        }
        if (reproducible.url.isBlank()) {
            return DownloadOutcome.Failed("El gateway no devolvió URL de stream")
        }

        val ctx = runCatching { repo.subtitleContextForEpisode(episodeId) }.getOrNull()
        val season = ctx?.season ?: 0
        val episode = ctx?.episode ?: 0
        val itemId = episodeId.substringBefore("::")
        val seriesId = SeriesItemIds.seriesIdOrNull(itemId)
            ?: return DownloadOutcome.Failed("Los episodios de Ditu sin seriesId no se pueden descargar todavía")

        // Reusar un job ya terminado en el backend para el mismo episodio
        val existing = runCatching { api.library(seriesId) }.getOrNull()
            ?.firstOrNull { it.season == season && it.episode == episode }

        val itemIdOnNuc = existing?.itemId ?: run {
            dao.updateState(episodeId, LocalDownloadState.STAGING, null)
            val jobId = api.createJob(
                seriesId = seriesId,
                showTitle = repo.getEpisode(episodeId)?.displayName ?: seriesId,
                posterUrl = "",
                items = listOf(
                    NucDownloadItem(
                        season = season,
                        episode = episode,
                        pageUrl = episodeId,
                        streamUrl = reproducible.url,
                        extraHeaders = reproducible.headers,
                    ),
                ),
            ) ?: return DownloadOutcome.Failed("El backend no aceptó el trabajo (¿sin espacio o caído?)")

            val waitResult = awaitJob(jobId, onProgress)
            if (waitResult != null) return waitResult

            runCatching { api.library(seriesId) }.getOrNull()
                ?.firstOrNull { it.season == season && it.episode == episode }
                ?.itemId
                ?: return DownloadOutcome.Failed("El backend terminó pero no publicó el archivo")
        }

        dao.setStagingItem(episodeId, itemIdOnNuc)
        dao.updateState(episodeId, LocalDownloadState.DOWNLOADING, null)

        val base = api.baseUrlResolved()
        val url = api.streamUrl(itemIdOnNuc, base)
        val target = File(targetDir, LocalFilePaths.fileNameFor(episodeId, "ditu.mkv"))

        val result = http.download(url, target, emptyMap(), resumeKey = "nuc:item:$itemIdOnNuc") { done, total ->
            onProgress((StagingProgress.fromTransfer(done, total) * PROGRESS_SCALE).toLong(), PROGRESS_SCALE)
        }

        return result.fold(
            onSuccess = { file ->
                val deleted = runCatching { api.deleteLibraryItem(itemIdOnNuc) }.getOrDefault(false)
                if (deleted) dao.setStagingItem(episodeId, null)
                else Log.w(TAG, "no se pudo borrar item $itemIdOnNuc del backend; queda para el barrido")
                DownloadOutcome.Done(file)
            },
            onFailure = {
                DownloadOutcome.Failed(
                    it.message ?: "Falló la transferencia desde el backend",
                    transient = DownloadRetryPolicy.isTransient(it),
                )
            },
        )
    }

    private suspend fun awaitJob(jobId: Long, onProgress: (Long, Long) -> Unit): DownloadOutcome? {
        var waited = 0L
        while (waited < STAGING_TIMEOUT_MS) {
            val job = api.getJob(jobId)
            when (job?.status) {
                "done" -> return null
                "failed" -> return DownloadOutcome.Failed("El backend no pudo descargar este episodio de Ditu")
                else -> {
                    val p = job?.progress ?: 0f
                    onProgress((StagingProgress.fromStaging(p) * PROGRESS_SCALE).toLong(), PROGRESS_SCALE)
                }
            }
            delay(POLL_MS)
            waited += POLL_MS
        }
        return DownloadOutcome.Failed("El backend tardó demasiado descargando el episodio de Ditu")
    }

    private companion object {
        const val TAG = "ArkivLocalDl"
        const val POLL_MS = 3_000L
        const val STAGING_TIMEOUT_MS = 60L * 60 * 1000
        const val PROGRESS_SCALE = 1_000_000L
    }
}
