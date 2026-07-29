package com.arkiv.player.sync

import com.arkiv.player.data.db.EpisodeEntity
import com.arkiv.player.data.db.ItemEntity
import com.arkiv.player.data.db.PlaybackEntity
import com.arkiv.player.data.db.SkipMarkerEntity
import org.json.JSONArray
import org.json.JSONObject

/** Snapshot de los datos sincronizables (biblioteca + progreso + marcadores). */
data class SyncSnapshot(
    val items: List<ItemEntity>,
    val episodes: List<EpisodeEntity>,
    val playback: List<PlaybackEntity>,
    val markers: List<SkipMarkerEntity>,
) {
    fun toJson(): String {
        val root = JSONObject()
        root.put("items", JSONArray().apply {
            items.forEach {
                put(
                    JSONObject()
                        .put("identifier", it.identifier)
                        .put("title", it.title)
                        .put("description", it.description ?: JSONObject.NULL)
                        .put("thumbnailUrl", it.thumbnailUrl)
                        .put("addedAt", it.addedAt)
                        .put("categoryOverride", it.categoryOverride ?: JSONObject.NULL)
                        .put("source", it.source)
                        .put("torrentData", it.torrentData ?: JSONObject.NULL),
                )
            }
        })
        root.put("episodes", JSONArray().apply {
            episodes.forEach {
                put(
                    JSONObject()
                        .put("id", it.id).put("itemId", it.itemId).put("section", it.section)
                        .put("displayName", it.displayName).put("orderIndex", it.orderIndex)
                        .put("durationSeconds", it.durationSeconds)
                        .put("thumbPath", it.thumbPath ?: JSONObject.NULL)
                        .put("originalPath", it.originalPath ?: JSONObject.NULL)
                        .put("originalFormat", it.originalFormat ?: JSONObject.NULL)
                        .put("originalSize", it.originalSize)
                        .put("derivativePath", it.derivativePath ?: JSONObject.NULL)
                        .put("derivativeFormat", it.derivativeFormat ?: JSONObject.NULL)
                        .put("derivativeSize", it.derivativeSize)
                        .put("torrentFileIndex", it.torrentFileIndex ?: JSONObject.NULL),
                )
            }
        })
        root.put("playback", JSONArray().apply {
            playback.forEach {
                put(
                    JSONObject()
                        .put("episodeId", it.episodeId).put("positionMs", it.positionMs)
                        .put("durationMs", it.durationMs).put("watched", it.watched)
                        .put("lastPlayedAt", it.lastPlayedAt),
                )
            }
        })
        root.put("markers", JSONArray().apply {
            markers.forEach {
                put(
                    JSONObject()
                        .put("itemId", it.itemId)
                        .put("openingStartMs", it.openingStartMs ?: JSONObject.NULL)
                        .put("openingEndMs", it.openingEndMs ?: JSONObject.NULL)
                        .put("endingStartMs", it.endingStartMs ?: JSONObject.NULL)
                        .put("updatedAt", it.updatedAt),
                )
            }
        })
        return root.toString()
    }

    companion object {
        private fun JSONObject.optLongOrNull(key: String): Long? =
            if (isNull(key)) null else getLong(key)

        private fun JSONObject.optStringOrNull(key: String): String? =
            if (isNull(key)) null else getString(key)

        fun fromJson(text: String): SyncSnapshot {
            val root = JSONObject(text)
            val items = root.getJSONArray("items").mapObjects {
                ItemEntity(
                    identifier = it.getString("identifier"),
                    title = it.getString("title"),
                    description = it.optStringOrNull("description"),
                    thumbnailUrl = it.getString("thumbnailUrl"),
                    addedAt = it.getLong("addedAt"),
                    categoryOverride = if (it.has("categoryOverride")) it.optStringOrNull("categoryOverride") else null,
                    source = if (it.has("source")) it.optString("source", "archive") else "archive",
                    torrentData = if (it.has("torrentData")) it.optStringOrNull("torrentData") else null,
                )
            }
            val episodes = root.getJSONArray("episodes").mapObjects {
                EpisodeEntity(
                    id = it.getString("id"), itemId = it.getString("itemId"),
                    section = it.getString("section"), displayName = it.getString("displayName"),
                    orderIndex = it.getInt("orderIndex"), durationSeconds = it.getDouble("durationSeconds"),
                    thumbPath = it.optStringOrNull("thumbPath"),
                    originalPath = it.optStringOrNull("originalPath"),
                    originalFormat = it.optStringOrNull("originalFormat"),
                    originalSize = it.getLong("originalSize"),
                    derivativePath = it.optStringOrNull("derivativePath"),
                    derivativeFormat = it.optStringOrNull("derivativeFormat"),
                    derivativeSize = it.getLong("derivativeSize"),
                    torrentFileIndex = if (it.has("torrentFileIndex") && !it.isNull("torrentFileIndex")) it.getInt("torrentFileIndex") else null,
                )
            }
            val playback = root.getJSONArray("playback").mapObjects {
                PlaybackEntity(
                    episodeId = it.getString("episodeId"), positionMs = it.getLong("positionMs"),
                    durationMs = it.getLong("durationMs"), watched = it.getBoolean("watched"),
                    lastPlayedAt = it.getLong("lastPlayedAt"),
                )
            }
            val markers = root.getJSONArray("markers").mapObjects {
                SkipMarkerEntity(
                    itemId = it.getString("itemId"),
                    openingStartMs = it.optLongOrNull("openingStartMs"),
                    openingEndMs = it.optLongOrNull("openingEndMs"),
                    endingStartMs = it.optLongOrNull("endingStartMs"),
                    updatedAt = it.optLong("updatedAt", 0),
                )
            }
            return SyncSnapshot(items, episodes, playback, markers)
        }

        private inline fun <T> JSONArray.mapObjects(transform: (JSONObject) -> T): List<T> =
            (0 until length()).map { transform(getJSONObject(it)) }
    }
}
