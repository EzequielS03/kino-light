package com.arkiv.player.data

import com.arkiv.player.data.db.EpisodeEntity
import com.arkiv.player.data.model.Episode

fun EpisodeEntity.toEpisode(): Episode = Episode(
    id = id,
    itemId = itemId,
    section = section,
    displayName = displayName,
    orderIndex = orderIndex,
    durationSeconds = durationSeconds,
    thumbPath = thumbPath,
    // torrentData is the entity's generic source payload (page URL if it's web, magnet if it's
    // torrent) — see EpisodeEntity. Exposed as sourceRef so the UI can tell where each row came
    // from without going back to the DB.
    sourceRef = torrentData,
    season = season,
    episode = episode,
)
