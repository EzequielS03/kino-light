package com.arkiv.player.data.local

import android.util.Log
import com.arkiv.player.data.ArkivRepository
import com.arkiv.player.data.SeriesItemIds
import com.arkiv.player.data.db.DownloadDao
import com.arkiv.player.data.offline.ArkivOfflineApi
import com.arkiv.player.data.offline.NucDownloadItem
import kotlinx.coroutines.delay
import java.io.File

/**
 * Fuentes web: la NUC (arkiv-offline) hace de ESTACIÓN DE PASO, nunca de destino.
 *
 * El resolver web devuelve HLS (.m3u8) con frecuencia, que no es un archivo descargable. El backend
 * ya resuelve eso con yt-dlp, y `GET /stream/{item}` sirve el resultado con Range (RFC 7233), así que
 * la transferencia al dispositivo es reanudable. Ver el spec para por qué se descartó remuxar HLS con
 * libVLC en el propio dispositivo y por qué NO hay un camino directo para mp4 progresivo.
 */
class NucStagedStrategy(
    private val repo: ArkivRepository,
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
        val pageUrl = repo.webSourceForEpisode(episodeId)
            ?: return DownloadOutcome.Failed("No se encontró la fuente web")
        val ctx = runCatching { repo.subtitleContextForEpisode(episodeId) }.getOrNull()
        val season = ctx?.season ?: 0
        val episode = ctx?.episode ?: 0
        // SeriesItemIds.seriesIdOrNull (no un removePrefix manual acá): devuelve null para una
        // película web suelta (identifier "web:<hash>", sin el prefijo "web:series:"). El backend de
        // la NUC solo entiende (seriesId, season, episode) — no hay convención de "película" en su
        // API — así que en vez de mandarle un seriesId inventado (el itemId completo, sin traducir) y
        // crear un job fantasma que después hay que limpiar a mano, se falla ACÁ con un mensaje claro.
        val itemId = episodeId.substringBefore("::")
        val seriesId = SeriesItemIds.seriesIdOrNull(itemId)
            ?: return DownloadOutcome.Failed(
                "Las películas web todavía no se pueden descargar al dispositivo (solo series)",
            )

        // 1) Reusar lo que ya esté en la NUC para esta (serie, temporada, capítulo, pageUrl).
        val existing = runCatching { api.library(seriesId) }.getOrNull()
            ?.firstOrNull { it.season == season && it.episode == episode && it.sourceRef == pageUrl }

        val itemIdOnNuc = existing?.itemId ?: run {
            // Fase de STAGING: la NUC baja del origen y el dispositivo todavía no escribió un byte.
            // Sin esto la fila decía "Bajando 12%" mientras la NUC trabajaba durante minutos u
            // horas, y el estado `staging` (con su texto "Preparando en el servidor" y su prioridad
            // en la cola) no lo escribía nadie. El progreso de esta fase ya NO pisa el estado: el
            // DAO tiene un `updateProgress` que no toca `state`.
            dao.updateState(episodeId, LocalDownloadState.STAGING, null)
            val jobId = api.createJob(
                seriesId = seriesId,
                showTitle = repo.getEpisode(episodeId)?.displayName ?: seriesId,
                posterUrl = "",
                items = listOf(NucDownloadItem(season = season, episode = episode, pageUrl = pageUrl)),
            ) ?: return DownloadOutcome.Failed("La NUC no aceptó el trabajo (¿sin espacio o caída?)")

            // 2) Esperar a que la NUC termine de bajar del origen.
            val waitResult = awaitJob(jobId, onProgress)
            if (waitResult != null) return waitResult

            runCatching { api.library(seriesId) }.getOrNull()
                ?.firstOrNull { it.season == season && it.episode == episode && it.sourceRef == pageUrl }
                ?.itemId
                ?: return DownloadOutcome.Failed("La NUC terminó pero no publicó el archivo")
        }

        // Se guarda ANTES de transferir (no solo si el borrado final falla): así, si el proceso muere
        // o la transferencia falla a mitad, el id igual queda registrado. Hoy el barrido de arranque
        // (Task 19) solo mira filas en estado 'completed', así que únicamente recupera el caso "se
        // transfirió bien pero el DELETE /library falló" — pero dejarlo escrito desde acá no le cuesta
        // nada y es estrictamente más seguro que escribirlo recién al final.
        dao.setStagingItem(episodeId, itemIdOnNuc)

        // Termina el staging y arranca la transferencia real al dispositivo: de acá en más el
        // progreso sí son bytes que llegan al disco del celular.
        dao.updateState(episodeId, LocalDownloadState.DOWNLOADING, null)

        // 3) Transferir de la NUC al dispositivo (Range → reanudable).
        val base = api.baseUrlResolved()
        val url = api.streamUrl(itemIdOnNuc, base)
        val target = File(targetDir, LocalFilePaths.fileNameFor(episodeId, pageUrl))

        // El progreso de ESTA fase se reporta con StagingProgress.fromTransfer, igual que la fase de
        // staging usa fromStaging: las dos mapean a la MISMA escala (PROGRESS_SCALE de 0 a 1, repartida
        // en mitades). Si acá se reenviaran los bytes reales de `http.download` tal cual (done, total),
        // la barra saltaría hacia atrás al terminar el staging: fromStaging(1f) deja la barra en 0.5,
        // pero un (done=0, total=archivo) crudo la mostraría en 0. fromTransfer(0, total) da 0.5
        // también, así que con el mapeo la transición es continua.
        //
        // `resumeKey` es el ítem de la NUC y NO la URL: `baseUrlResolved()` prueba LAN antes que
        // túnel, así que la MISMA copia se sirve desde dos URLs distintas según dónde esté el
        // celular. Con la URL como clave, volver a casa (o salir) después de un corte descartaría un
        // parcial de varios GB perfectamente válido.
        val result = http.download(url, target, emptyMap(), resumeKey = "nuc:item:$itemIdOnNuc") { done, total ->
            onProgress((StagingProgress.fromTransfer(done, total) * PROGRESS_SCALE).toLong(), PROGRESS_SCALE)
        }

        return result.fold(
            onSuccess = { file ->
                // 4) Liberar el disco de la NUC. Best-effort: si falla, el stagingItemId queda en la
                // fila (ya se guardó arriba) y el barrido de arranque lo reintenta (ver Task 19).
                val deleted = runCatching { api.deleteLibraryItem(itemIdOnNuc) }.getOrDefault(false)
                if (deleted) dao.setStagingItem(episodeId, null)
                else Log.w(TAG, "no se pudo borrar el item $itemIdOnNuc de la NUC; queda para el barrido")
                DownloadOutcome.Done(file)
            },
            // Solo la TRANSFERENCIA es reintentable sola (corte de red con el `.part` intacto). Los
            // fallos del lado de la NUC (job rechazado, staging fallido o eterno) quedan definitivos
            // y reintentables a mano desde la pantalla, tal como los describe el spec: si blog está
            // caído o sin cuota, martillarlo con backoff no lo va a arreglar.
            onFailure = {
                DownloadOutcome.Failed(
                    it.message ?: "Falló la transferencia desde la NUC",
                    transient = DownloadRetryPolicy.isTransient(it),
                )
            },
        )
    }

    /** Devuelve null si el job terminó bien, o el DownloadOutcome de fallo. */
    private suspend fun awaitJob(jobId: Long, onProgress: (Long, Long) -> Unit): DownloadOutcome? {
        var waited = 0L
        while (waited < STAGING_TIMEOUT_MS) {
            val job = api.getJob(jobId)
            when (job?.status) {
                "done" -> return null
                "failed" -> return DownloadOutcome.Failed("La NUC no pudo bajar este capítulo")
                else -> {
                    // El progreso del job viaja en 0..1; se mapea a la primera mitad de la barra
                    // (misma escala PROGRESS_SCALE que usa la fase de transferencia más abajo).
                    val p = job?.progress ?: 0f
                    onProgress((StagingProgress.fromStaging(p) * PROGRESS_SCALE).toLong(), PROGRESS_SCALE)
                }
            }
            delay(POLL_MS)
            waited += POLL_MS
        }
        return DownloadOutcome.Failed("La NUC tardó demasiado")
    }

    private companion object {
        const val TAG = "ArkivLocalDl"
        const val POLL_MS = 3_000L
        const val STAGING_TIMEOUT_MS = 60L * 60 * 1000   // 1 h: un capítulo grande por HLS tarda
        /** Escala artificial para reportar progreso (staging y transferencia) por el mismo callback de bytes. */
        const val PROGRESS_SCALE = 1_000_000L
    }
}
