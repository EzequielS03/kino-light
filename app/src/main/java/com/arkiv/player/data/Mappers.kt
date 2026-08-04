package com.arkiv.player.data

import com.arkiv.player.data.db.EpisodeEntity
import com.arkiv.player.data.db.ItemEntity
import com.arkiv.player.data.model.ArchiveItem
import com.arkiv.player.data.model.Episode
import com.arkiv.player.data.model.VideoVariant

fun EpisodeEntity.toEpisode(): Episode = Episode(
    id = id,
    itemId = itemId,
    section = section,
    displayName = displayName,
    orderIndex = orderIndex,
    durationSeconds = durationSeconds,
    thumbPath = thumbPath,
    original = originalPath?.let { VideoVariant(it, originalFormat.orEmpty(), originalSize) },
    derivative = derivativePath?.let { VideoVariant(it, derivativeFormat.orEmpty(), derivativeSize) },
    // torrentData es el payload genérico de fuente de la entidad (pageUrl si es web, magnet si es
    // torrent, null si es archive) — ver EpisodeEntity. Se expone como sourceRef para que la UI
    // pueda saber de qué sitio salió cada fila sin volver a la base.
    sourceRef = torrentData,
)

fun Episode.toEntity(): EpisodeEntity = EpisodeEntity(
    id = id,
    itemId = itemId,
    section = section,
    displayName = displayName,
    orderIndex = orderIndex,
    durationSeconds = durationSeconds,
    thumbPath = thumbPath,
    originalPath = original?.path,
    originalFormat = original?.format,
    originalSize = original?.sizeBytes ?: 0L,
    derivativePath = derivative?.path,
    derivativeFormat = derivative?.format,
    derivativeSize = derivative?.sizeBytes ?: 0L,
    // Vuelta simétrica de toEpisode: sin esto un ida-y-vuelta entidad→dominio→entidad borraría la
    // fuente del episodio. Hoy el único llamador guarda ítems de archive.org (sourceRef siempre
    // null), así que no cambia nada en la práctica; está para que no sea una trampa mañana.
    torrentData = sourceRef,
)

fun ArchiveItem.toItemEntity(addedAt: Long, categoryOverride: String? = null): ItemEntity = ItemEntity(
    identifier = identifier,
    title = title,
    description = description,
    thumbnailUrl = thumbnailUrl,
    addedAt = addedAt,
    categoryOverride = categoryOverride,
)
