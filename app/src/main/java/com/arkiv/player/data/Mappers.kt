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
)

fun ArchiveItem.toItemEntity(addedAt: Long, categoryOverride: String? = null): ItemEntity = ItemEntity(
    identifier = identifier,
    title = title,
    description = description,
    thumbnailUrl = thumbnailUrl,
    addedAt = addedAt,
    categoryOverride = categoryOverride,
)
