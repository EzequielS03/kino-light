package com.arkiv.player.sync

import com.arkiv.player.data.MarcadorDeCapitulo
import com.arkiv.player.data.db.EpisodeEntity
import com.arkiv.player.data.db.ItemEntity
import com.arkiv.player.data.db.LiveFavoriteEntity
import com.arkiv.player.data.db.LiveRecentEntity
import com.arkiv.player.data.db.PlaybackEntity
import com.arkiv.player.data.db.SkipMarkerEntity
import org.json.JSONArray
import org.json.JSONObject

/**
 * Snapshot de los datos sincronizables (biblioteca + progreso + marcadores).
 *
 * `updatedAt` y `deleted` viajan SIEMPRE: son el reloj y el tombstone con los que [SyncMerge]
 * decide quién gana. Sin ellos el sync no podía hacer otra cosa que espejar una punta sobre la
 * otra, y un borrado llegaba al otro lado como una fila viva.
 *
 * Los campos que se leen con `opt…` son compatibles hacia atrás: un snapshot de la versión anterior
 * (sin esas claves) entra igual, con los valores por defecto.
 */
data class SyncSnapshot(
    val items: List<ItemEntity>,
    val episodes: List<EpisodeEntity>,
    val playback: List<PlaybackEntity>,
    val markers: List<SkipMarkerEntity>,
    /** Favoritos de TV en vivo. Mismo esquema de sync que [markers]: LWW + tombstone. */
    val liveFavorites: List<LiveFavoriteEntity> = emptyList(),
    /** Recientes de TV en vivo. LWW sin tombstone (se poda por antigüedad). */
    val liveRecents: List<LiveRecentEntity> = emptyList(),
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
                        .put("torrentData", it.torrentData ?: JSONObject.NULL)
                        .put("updatedAt", it.updatedAt)
                        .put("deleted", it.deleted)
                        .put("episodiosVistosEnLista", it.episodiosVistosEnLista ?: JSONObject.NULL)
                        .put("tmdbId", it.tmdbId ?: JSONObject.NULL),
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
                        .put("torrentFileIndex", it.torrentFileIndex ?: JSONObject.NULL)
                        .put("season", it.season ?: JSONObject.NULL)
                        .put("episode", it.episode ?: JSONObject.NULL)
                        .put("torrentData", it.torrentData ?: JSONObject.NULL)
                        .put("updatedAt", it.updatedAt)
                        .put("deleted", it.deleted),
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
                        .put("id", it.id)
                        .put("itemId", it.itemId)
                        .put("episodeId", it.episodeId)
                        .put("openingStartMs", it.openingStartMs ?: JSONObject.NULL)
                        .put("openingEndMs", it.openingEndMs ?: JSONObject.NULL)
                        .put("endingStartMs", it.endingStartMs ?: JSONObject.NULL)
                        .put("updatedAt", it.updatedAt)
                        .put("origen", it.origen),
                )
            }
        })
        root.put("liveFavorites", JSONArray().apply {
            liveFavorites.forEach {
                put(
                    JSONObject()
                        .put("code", it.code)
                        .put("nombre", it.nombre)
                        .put("numero", it.numero)
                        .put("logo", it.logo ?: JSONObject.NULL)
                        .put("updatedAt", it.updatedAt)
                        .put("deleted", it.deleted),
                )
            }
        })
        root.put("liveRecents", JSONArray().apply {
            liveRecents.forEach {
                put(
                    JSONObject()
                        .put("code", it.code)
                        .put("nombre", it.nombre)
                        .put("vistoAt", it.vistoAt)
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

        private fun JSONObject.optIntOrNull(key: String): Int? =
            if (!has(key) || isNull(key)) null else getInt(key)

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
                    updatedAt = it.optLong("updatedAt", 0),
                    deleted = it.optBoolean("deleted", false),
                    episodiosVistosEnLista = it.optIntOrNull("episodiosVistosEnLista"),
                    tmdbId = it.optIntOrNull("tmdbId"),
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
                    season = it.optIntOrNull("season"),
                    episode = it.optIntOrNull("episode"),
                    torrentData = it.optStringOrNull("torrentData"),
                    updatedAt = it.optLong("updatedAt", 0),
                    deleted = it.optBoolean("deleted", false),
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
                val itemId = it.getString("itemId")
                // Compatibilidad: un snapshot de la versión anterior no manda `id`/`episodeId`
                // (los marcadores eran uno por serie) -- se recalcula la llave igual, para que no
                // entre en blanco.
                val episodeId = it.optStringOrNull("episodeId") ?: ""
                SkipMarkerEntity(
                    id = it.optStringOrNull("id") ?: MarcadorDeCapitulo.idDe(itemId, episodeId),
                    itemId = itemId,
                    episodeId = episodeId,
                    openingStartMs = it.optLongOrNull("openingStartMs"),
                    openingEndMs = it.optLongOrNull("openingEndMs"),
                    endingStartMs = it.optLongOrNull("endingStartMs"),
                    updatedAt = it.optLong("updatedAt", 0),
                    origen = it.optStringOrNull("origen") ?: MarcadorDeCapitulo.ORIGEN_MANUAL,
                )
            }
            // Compatibilidad: un snapshot de la versión anterior no manda estas claves.
            val liveFavorites = if (root.has("liveFavorites")) {
                root.getJSONArray("liveFavorites").mapObjects {
                    LiveFavoriteEntity(
                        code = it.getString("code"),
                        nombre = it.getString("nombre"),
                        numero = it.getInt("numero"),
                        logo = it.optStringOrNull("logo"),
                        updatedAt = it.optLong("updatedAt", 0),
                        deleted = it.optBoolean("deleted", false),
                    )
                }
            } else {
                emptyList()
            }
            val liveRecents = if (root.has("liveRecents")) {
                root.getJSONArray("liveRecents").mapObjects {
                    LiveRecentEntity(
                        code = it.getString("code"),
                        nombre = it.getString("nombre"),
                        vistoAt = it.getLong("vistoAt"),
                        updatedAt = it.optLong("updatedAt", 0),
                    )
                }
            } else {
                emptyList()
            }
            return SyncSnapshot(items, episodes, playback, markers, liveFavorites, liveRecents)
        }

        private inline fun <T> JSONArray.mapObjects(transform: (JSONObject) -> T): List<T> =
            (0 until length()).map { transform(getJSONObject(it)) }
    }
}
