package com.arkiv.player.data

import com.arkiv.player.data.db.EpisodeEntity
import com.arkiv.player.data.db.ItemEntity

/**
 * Construye (ítem + episodio) para un contenido de Ditu (Caracol Streaming). Puro/JVM.
 *
 * El ref es el token opaco que `/v1/resolve` necesita para obtener el stream.
 */
object DituEntities {

    const val PREFIX = "ditu:"

    fun itemIdDe(contentId: String): String = PREFIX + contentId
    fun itemIdDeSerie(bundleId: String): String = "ditu:serie:$bundleId"

    fun episodioIdDePelicula(itemId: String): String = "$itemId::0"
    fun episodioIdDeSerie(itemId: String, season: Int, number: Int): String =
        "$itemId::S${season.toString().padStart(2, '0')}E${number.toString().padStart(2, '0')}"

    fun build(
        contentId: String,
        ref: String,
        title: String,
        posterUrl: String,
        ahora: Long,
        existente: ItemEntity?,
    ): Pair<ItemEntity, EpisodeEntity> {
        val itemId = itemIdDe(contentId)
        val item = ItemEntity(
            identifier = itemId,
            title = title.ifBlank { "Caracol" },
            description = null,
            thumbnailUrl = posterUrl.ifBlank { existente?.thumbnailUrl.orEmpty() },
            addedAt = existente?.addedAt ?: ahora,
            source = "ditu",
            torrentData = ref,
            episodiosVistosEnLista = existente?.episodiosVistosEnLista,
            tmdbId = existente?.tmdbId,
            tipo = "movie",
            tituloCanonico = existente?.tituloCanonico,
        )
        val ep = EpisodeEntity(
            id = episodioIdDePelicula(itemId),
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
        return item to ep
    }

    fun buildEpisodio(
        bundleId: String,
        serieTitle: String,
        posterUrl: String,
        epRef: String,
        epTitle: String,
        epNumber: Int,
        epSeason: Int,
        orderIndex: Int,
        ahora: Long,
        existente: ItemEntity?,
        tmdbId: Int? = null,
        tituloCanonico: String? = null,
    ): Pair<ItemEntity, EpisodeEntity> {
        val itemId = itemIdDeSerie(bundleId)
        val item = ItemEntity(
            identifier = itemId,
            title = serieTitle.ifBlank { "Caracol" },
            description = null,
            thumbnailUrl = posterUrl.ifBlank { existente?.thumbnailUrl.orEmpty() },
            addedAt = existente?.addedAt ?: ahora,
            source = "ditu",
            torrentData = null,
            episodiosVistosEnLista = existente?.episodiosVistosEnLista,
            tmdbId = tmdbId?.takeIf { it > 0 } ?: existente?.tmdbId,
            tipo = "tv",
            tituloCanonico = tituloCanonico?.takeIf { it.isNotBlank() } ?: existente?.tituloCanonico,
        )
        val section = "T${epSeason.toString().padStart(2, '0')}"
        val ep = EpisodeEntity(
            id = episodioIdDeSerie(itemId, epSeason, epNumber),
            itemId = itemId,
            section = section,
            displayName = epTitle.ifBlank { "Episodio $epNumber" },
            orderIndex = orderIndex,
            durationSeconds = 0.0,
            thumbPath = null,
            originalPath = null,
            originalFormat = null,
            originalSize = 0,
            derivativePath = null,
            derivativeFormat = null,
            derivativeSize = 0,
            torrentFileIndex = null,
            torrentData = epRef,
        )
        return item to ep
    }
}
