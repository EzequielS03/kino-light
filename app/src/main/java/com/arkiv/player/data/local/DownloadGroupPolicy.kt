package com.arkiv.player.data.local

import com.arkiv.player.data.db.DownloadRow
import com.arkiv.player.data.model.Episode

/** Carátula, título y fuente del ítem al que pertenece el grupo. Sale de `LibraryRow`. */
data class DownloadItemMeta(
    val title: String,
    val thumbnailUrl: String,
    val source: String,
)

/** Estado de un episodio de la serie en la pantalla de Descargas. */
sealed interface EpisodeDownloadStatus {
    /** Nunca se encoló: no hay fila en `downloads` para este episodio. */
    data object NotDownloaded : EpisodeDownloadStatus

    /** Tiene fila en `downloads`; el estado real vive en `row.state` (ver [LocalDownloadState]). */
    data class Tracked(val row: DownloadRow) : EpisodeDownloadStatus
}

/** Un episodio de la serie fusionado con su descarga, si la tiene. */
data class GroupedEpisode(
    val episode: Episode,
    val status: EpisodeDownloadStatus,
)

/** Cabecera + episodios de un ítem (serie o película) para la pantalla de Descargas. */
data class DownloadGroup(
    val itemId: String,
    val itemTitle: String,
    val itemThumbnailUrl: String,
    val source: String,
    /** TODOS los episodios del ítem, en orden natural -- no solo los que pasaron por la cola. */
    val episodes: List<GroupedEpisode>,
) {
    /** Una película (un solo episodio) no tiene nada que plegar: se muestra como fila simple. */
    val isSingleEpisode: Boolean get() = episodes.size <= 1
}

/**
 * Agrupa la cola de descargas por ítem y arma el resumen de cada cabecera ("3 de 24 guardados · 1
 * bajando"). Pure: doesn't touch Room or WorkManager, so it's tested on the JVM without
 * Robolectric (same convention as [DownloadQueuePolicy]/[FreeSpacePolicy]/[StagingProgress]).
 */
object DownloadGroupPolicy {

    /**
     * [episodesByItem] trae SIEMPRE la lista completa de episodios de cada ítem (no solo los
     * encolados): la pantalla ofrece "descargar" para los que todavía no tienen fila. [itemMeta] es
     * (título, carátula, source) por itemId, sale de `LibraryRow`.
     *
     * El orden de los grupos es el de aparición en [downloads] (que ya llega `ORDER BY createdAt
     * DESC` desde `observeDownloadRows`): el ítem con actividad más reciente queda arriba, igual que
     * la lista plana de antes.
     *
     * Un itemId sin metadata en [itemMeta] se salta -- puede pasar en el instante entre que se
     * encola un episodio y que `observeLibrary()` resuelve, o si el ítem se borró de la biblioteca
     * con descargas todavía en la tabla. Un itemId sin entrada en [episodesByItem] (todavía no
     * resolvió el fetch async de `episodesOf`) no se salta: muestra solo los episodios que ya se
     * conocen por [downloads] y se completa solo en cuanto el fetch resuelve, para no hacer
     * parpadear la pantalla a "vacío" mientras carga.
     */
    fun buildGroups(
        downloads: List<DownloadRow>,
        episodesByItem: Map<String, List<Episode>>,
        itemMeta: Map<String, DownloadItemMeta>,
    ): List<DownloadGroup> {
        val byItem = downloads.groupBy { it.itemId }
        val order = downloads.map { it.itemId }.distinct()
        return order.mapNotNull { itemId ->
            val meta = itemMeta[itemId] ?: return@mapNotNull null
            val trackedRows = byItem[itemId].orEmpty()
            val allEpisodes = episodesByItem[itemId]
            val grouped = if (allEpisodes != null) {
                val trackedByEpisode = trackedRows.associateBy { it.episodeId }
                allEpisodes.sortedBy { it.orderIndex }.map { ep ->
                    val status = trackedByEpisode[ep.id]?.let { EpisodeDownloadStatus.Tracked(it) }
                        ?: EpisodeDownloadStatus.NotDownloaded
                    GroupedEpisode(ep, status)
                }
            } else {
                trackedRows.map { row -> GroupedEpisode(row.toPlaceholderEpisode(), EpisodeDownloadStatus.Tracked(row)) }
            }
            DownloadGroup(itemId, meta.title, meta.thumbnailUrl, meta.source, grouped)
        }
    }

    /** "3 de 24 guardados · 1 bajando" -- omite cláusulas en cero. */
    fun summarize(episodes: List<GroupedEpisode>): String {
        val total = episodes.size
        var saved = 0
        var downloading = 0
        var staging = 0
        var queued = 0
        var failed = 0
        var needsConfirmation = 0
        for (grouped in episodes) {
            val row = (grouped.status as? EpisodeDownloadStatus.Tracked)?.row ?: continue
            when (row.state) {
                LocalDownloadState.COMPLETED -> saved++
                LocalDownloadState.DOWNLOADING -> downloading++
                LocalDownloadState.STAGING -> staging++
                LocalDownloadState.QUEUED -> queued++
                LocalDownloadState.FAILED -> failed++
                LocalDownloadState.NEEDS_CONFIRMATION -> needsConfirmation++
            }
        }
        val clauses = mutableListOf("$saved de $total guardados")
        if (downloading > 0) clauses += "$downloading bajando"
        if (staging > 0) clauses += "$staging preparando"
        if (queued > 0) clauses += "$queued en cola"
        if (failed > 0) clauses += "$failed con error"
        if (needsConfirmation > 0) clauses += "$needsConfirmation por confirmar"
        return clauses.joinToString(" · ")
    }

    /**
     * Episodios que "cancelar todos" tiene que frenar: encolados o en vuelo. Pensado para pasarse,
     * fila por fila, a [LocalDownloadManager.cancel] -- es el único camino que corta de verdad el
     * worker cuando la que está en vuelo es una de estas (ver el KDoc de `cancel`); llamarlo también
     * para las encoladas de más no hace nada raro, porque `cancel` ya distingue cuál es la fila que
     * corre. Sin pasar por acá, "cancelar todos" solo tacharía filas de la cola dejando la descarga
     * en curso corriendo sola -- el bug de archivo huérfano que ya se arregló una vez.
     */
    fun activeEpisodeIds(group: DownloadGroup): List<String> =
        group.episodes.mapNotNull { (it.status as? EpisodeDownloadStatus.Tracked)?.row }
            .filter {
                it.state == LocalDownloadState.QUEUED ||
                    it.state == LocalDownloadState.DOWNLOADING ||
                    it.state == LocalDownloadState.STAGING
            }
            .map { it.episodeId }

    /** Episodios que "reintentar fallidos" tiene que reencolar. */
    fun failedEpisodeIds(group: DownloadGroup): List<String> =
        group.episodes.mapNotNull { (it.status as? EpisodeDownloadStatus.Tracked)?.row }
            .filter { it.state == LocalDownloadState.FAILED }
            .map { it.episodeId }

    /** Todos los episodios con fila en `downloads` (cualquier estado), para "quitar todos". */
    fun trackedEpisodeIds(group: DownloadGroup): List<String> =
        group.episodes.mapNotNull { (it.status as? EpisodeDownloadStatus.Tracked)?.row?.episodeId }

    /**
     * El primer episodio del grupo que ya se puede reproducir sin red (descarga `COMPLETED`), en el
     * orden en que [DownloadGroup.episodes] ya los trae (orden natural de la serie) -- no el primero
     * que terminó de bajar. Null si todavía no hay ninguno completo.
     */
    fun firstPlayableEpisodeId(group: DownloadGroup): String? =
        group.episodes
            .firstOrNull { (it.status as? EpisodeDownloadStatus.Tracked)?.row?.state == LocalDownloadState.COMPLETED }
            ?.episode?.id

    /**
     * Thumbnail to render for a Downloads row: the episode's own artwork ([episodeThumb], from
     * `Episode.thumbPath`/`DownloadRow.thumbPath`) when there is one, otherwise the item's poster
     * ([itemThumbnailUrl], from `items.thumbnailUrl`). Today's sources (Magis, Caracol) never set a
     * per-episode thumb, so in practice this always resolves to the item poster -- kept as a
     * fallback chain rather than always using the item poster so a future source that does provide
     * one is picked up for free.
     */
    fun rowThumbnail(episodeThumb: String?, itemThumbnailUrl: String): String = episodeThumb ?: itemThumbnailUrl

    /**
     * Reconstruye un [Episode] mínimo a partir de una fila de `downloads`, para el caso (transitorio)
     * en que todavía no resolvió `episodesOf(itemId)`. Los campos que no viajan en [DownloadRow]
     * quedan en su valor neutro: no se muestran en la fila (ver `DownloadItem` en la UI) y se
     * reemplazan por los reales apenas llega el fetch completo.
     */
    private fun DownloadRow.toPlaceholderEpisode(): Episode = Episode(
        id = episodeId,
        itemId = itemId,
        section = "",
        displayName = displayName,
        orderIndex = 0,
        durationSeconds = 0.0,
        thumbPath = thumbPath,
    )
}
