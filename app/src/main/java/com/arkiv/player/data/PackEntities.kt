package com.arkiv.player.data

import com.arkiv.player.data.catalog.PackFileRow
import com.arkiv.player.data.db.EpisodeEntity
import com.arkiv.player.data.db.ItemEntity

/** Construye (item + episodios) de un pack. Puro/JVM (sin android.*) para poder testearse. El
 *  Base64 de los infoBytes se pasa ya codificado por el repositorio. */
object PackEntities {
    fun build(
        title: String, posterUrl: String, description: String?, infoHashHex: String,
        infoBase64: String, rows: List<PackFileRow>, addedAt: Long,
    ): Pair<ItemEntity, List<EpisodeEntity>> {
        val itemId = "torrent:$infoHashHex"
        val item = ItemEntity(
            identifier = itemId, title = title, description = description, thumbnailUrl = posterUrl,
            addedAt = addedAt, categoryOverride = "series", source = "torrent", torrentData = infoBase64,
        )
        val episodes = rows.map { r ->
            EpisodeEntity(
                id = "$itemId::${r.index}", itemId = itemId, section = r.section, displayName = r.label,
                orderIndex = r.orderIndex, durationSeconds = 0.0, thumbPath = null, originalPath = null,
                originalFormat = null, originalSize = r.sizeBytes, derivativePath = null,
                derivativeFormat = null, derivativeSize = 0, season = r.season, episode = r.episode,
                torrentFileIndex = r.index, torrentData = null,
            )
        }
        return item to episodes
    }
}
