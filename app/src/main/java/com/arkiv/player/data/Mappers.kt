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
    // torrentData is the entity's generic source payload: Ditu's ref today, or the page URL/magnet
    // that the now-removed web/torrent sources left in rows saved before this branch's pruning —
    // see EpisodeEntity. Exposed as sourceRef so the UI can tell where each row came from without
    // going back to the DB.
    sourceRef = torrentData,
    season = season,
    episode = episode,
)
