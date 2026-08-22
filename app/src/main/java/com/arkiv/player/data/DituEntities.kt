package com.arkiv.player.data

import com.arkiv.player.data.db.EpisodeEntity
import com.arkiv.player.data.db.ItemEntity

/**
 * Construye (ítem + episodio) para un contenido de Ditu (Caracol Streaming). Puro/JVM.
 *
 * Solo películas por ahora: series de Ditu se agregan cuando se valide el flujo base.
 * El ref es el token opaco que `/v1/resolve` necesita para obtener el stream.
 */
object DituEntities {

    const val PREFIX = "ditu:"

    fun itemIdDe(contentId: String): String = PREFIX + contentId

    fun episodioIdDePelicula(itemId: String): String = "$itemId::0"

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
            thumbnailUrl = posterUrl,
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
}
