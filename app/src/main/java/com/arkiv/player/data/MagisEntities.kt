package com.arkiv.player.data

import com.arkiv.player.data.db.EpisodeEntity
import com.arkiv.player.data.db.ItemEntity

/**
 * Construye (ítem + episodio) de lo que llega de Magis. Puro/JVM (sin `android.*`) para poder
 * testearse sin Room; el repositorio solo lo guarda. Mismo molde que [PackEntities].
 *
 * **Una temporada es UN ítem, y sus capítulos son sus episodios.** Antes cada capítulo era su
 * propio ítem (`magis:<contentId>:e1`, `…:e2`, …), y como la categoría se detecta sola por
 * cantidad de episodios (`LibraryRow.isMovie`: `episodeCount <= 1`), un capítulo suelto se leía
 * como **película** y la serie aparecía en la fila equivocada del home. Acá se hace lo mismo que
 * ya hacían el resto de las fuentes con capítulos (`addWebSeriesEpisode`, `addSeriesEpisode`,
 * `addAnimeEpisode`): id estable por serie + `categoryOverride = "series"` desde el primer
 * capítulo, sin esperar a que haya dos.
 *
 * El id sale del `contentId` del portal —que en Magis ya es por temporada— y no del `ref`: el ref
 * se re-emite en cada búsqueda y un id derivado de él perdería la marca de "voy por aquí".
 */
object MagisEntities {

    const val PREFIX = "magis:"

    /** El ítem de una película o de una temporada entera. */
    fun itemIdDe(contentId: String): String = PREFIX + contentId

    /**
     * El id que tenía un capítulo cuando cada uno era su propio ítem.
     *
     * Sirve para barrer esas filas: quedaron en la biblioteca como tarjetas-película sueltas y no
     * hay migración de Room que las toque (ver los planes en `docs/superpowers/plans/`), así que se
     * limpian al volver a guardar ese mismo capítulo.
     */
    fun idLegacyDeCapitulo(contentId: String, episode: Int): String = "${itemIdDe(contentId)}:e$episode"

    /**
     * [episode] > 0 = capítulo de una serie; 0 = película (el camino de siempre, intacto).
     *
     * [ref] es el del capítulo (o el de la película) y viaja en el episodio, que es lo que el
     * player resuelve al reproducir. [seriesRef] es el de la TEMPORADA y viaja en el ítem, que es
     * lo que sirve para preguntarle al portal si salió un capítulo nuevo; en blanco NO pisa el que
     * ya estaba guardado, porque los refs caducan y uno vencido es mejor que ninguno.
     *
     * [existente] es la fila que ya está en la base, si la hay: `upsertItem` es un REPLACE (borra e
     * inserta), así que lo que no se copie de ahí se pierde —la fecha de alta reordenaría el home y
     * `episodiosVistosEnLista` volvería a prender el badge sobre capítulos ya mirados.
     */
    fun build(
        contentId: String,
        ref: String,
        title: String,
        episode: Int,
        episodeTitle: String,
        posterUrl: String,
        ahora: Long,
        seriesRef: String,
        existente: ItemEntity?,
    ): Pair<ItemEntity, EpisodeEntity> {
        val itemId = itemIdDe(contentId)
        val esCapitulo = episode > 0
        val item = ItemEntity(
            identifier = itemId,
            title = title.ifBlank { "Magis" },
            description = null,
            thumbnailUrl = posterUrl,
            addedAt = existente?.addedAt ?: ahora,
            categoryOverride = if (esCapitulo) "series" else existente?.categoryOverride,
            source = "magis",
            torrentData = if (esCapitulo) {
                seriesRef.ifBlank { existente?.torrentData?.ifBlank { null } ?: ref }
            } else {
                ref
            },
            episodiosVistosEnLista = existente?.episodiosVistosEnLista,
            tmdbId = existente?.tmdbId,
        )
        val ep = if (esCapitulo) {
            EpisodeEntity(
                id = "$itemId::e$episode",
                itemId = itemId,
                section = "",
                displayName = "E$episode" + episodeTitle.trim().takeIf { it.isNotBlank() }?.let { "  $it" }.orEmpty(),
                orderIndex = episode,
                durationSeconds = 0.0,
                thumbPath = null,
                originalPath = null,
                originalFormat = null,
                originalSize = 0,
                derivativePath = null,
                derivativeFormat = null,
                derivativeSize = 0,
                // Numerado a propósito: es con esto que `CapitulosFaltantes` sabe cuál falta.
                season = null,
                episode = episode,
                torrentFileIndex = null,
                torrentData = ref,
            )
        } else {
            EpisodeEntity(
                id = "$itemId::0",
                itemId = itemId,
                section = "",
                displayName = MetadataParser.cleanName(title),
                orderIndex = 0,
                durationSeconds = 0.0,
                thumbPath = null,
                originalPath = null,
                originalFormat = null,
                originalSize = 0,
                derivativePath = null,
                derivativeFormat = null,
                derivativeSize = 0,
                torrentFileIndex = null,
                torrentData = ref,
            )
        }
        return item to ep
    }
}
