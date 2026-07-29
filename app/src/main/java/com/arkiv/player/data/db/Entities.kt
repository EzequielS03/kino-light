package com.arkiv.player.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "items")
data class ItemEntity(
    @PrimaryKey val identifier: String,
    val title: String,
    val description: String?,
    val thumbnailUrl: String,
    val addedAt: Long,
    /** Override manual de tipo: "movie" | "series" | null (= detección automática). */
    val categoryOverride: String? = null,
    /** Origen: "archive" (default) | "torrent". */
    val source: String = "archive",
    /** Para torrents: bytes del .torrent en base64 (para re-streamear). Null si es archive. */
    val torrentData: String? = null,
    /** Sync: reloj de última modificación (LWW) y tombstone de borrado. */
    val updatedAt: Long = 0,
    val deleted: Boolean = false,
)

@Entity(
    tableName = "episodes",
    indices = [Index("itemId")],
)
data class EpisodeEntity(
    @PrimaryKey val id: String,
    val itemId: String,
    val section: String,
    val displayName: String,
    val orderIndex: Int,
    val durationSeconds: Double,
    val thumbPath: String?,
    val originalPath: String?,
    val originalFormat: String?,
    val originalSize: Long,
    val derivativePath: String?,
    val derivativeFormat: String?,
    val derivativeSize: Long,
    /** Para torrents: índice del archivo dentro del torrent. Null si es archive. */
    val torrentFileIndex: Int? = null,
    /**
     * Para series donde cada episodio es su propio torrent (ej. anime del catálogo):
     * bytes del .torrent de ESTE episodio en base64. Null = usar el torrent del ítem.
     */
    val torrentData: String? = null,
    /** Sync: reloj de última modificación (LWW) y tombstone de borrado. */
    val updatedAt: Long = 0,
    val deleted: Boolean = false,
)

@Entity(tableName = "playback")
data class PlaybackEntity(
    @PrimaryKey val episodeId: String,
    val positionMs: Long,
    val durationMs: Long,
    val watched: Boolean,
    val lastPlayedAt: Long,
    /** Sync: reloj de última modificación (LWW) y tombstone de borrado. */
    val updatedAt: Long = 0,
    val deleted: Boolean = false,
)

@Entity(tableName = "skip_markers")
data class SkipMarkerEntity(
    @PrimaryKey val itemId: String,
    val openingStartMs: Long?,
    val openingEndMs: Long?,
    val endingStartMs: Long?,
    val updatedAt: Long = 0,
    val deleted: Boolean = false,
)

/**
 * Arte de TMDB por ítem (local, no se sincroniza). `backdropsJson` es una lista JSON de URLs de
 * backdrops apaisados; el home usa la 1ª para la tarjeta y una al azar para el hero. Un ítem sin
 * match queda con backdrops vacíos (`"[]"`) pero con fila, para no re-buscar en cada carga.
 */
@Entity(tableName = "artwork")
data class ArtworkEntity(
    @PrimaryKey val itemId: String,
    val tmdbId: Int? = null,
    val tmdbType: String? = null,
    val backdropsJson: String = "[]",
    val fetchedAt: Long = 0,
) {
    val backdrops: List<String>
        get() = runCatching {
            val arr = org.json.JSONArray(backdropsJson)
            (0 until arr.length()).map { arr.getString(it) }
        }.getOrDefault(emptyList())
}

/**
 * Historial de búsquedas del catálogo (anime/películas). PK compuesta (query, kind): el mismo
 * texto buscado en pestañas distintas (movie/tv/anime) no se pisa entre sí.
 */
@Entity(tableName = "search_history", primaryKeys = ["query", "kind"])
data class SearchHistoryEntity(
    val query: String,
    val kind: String,
    val atMs: Long,
)

@Entity(tableName = "downloads")
data class DownloadEntity(
    @PrimaryKey val episodeId: String,
    val variant: String,      // "original" | "derivative"
    val state: String,        // "queued" | "downloading" | "completed" | "failed"
    val progress: Float,      // 0..1
    val localUri: String?,
    val bytes: Long,
)

/**
 * Still (fotograma oficial) de un capítulo, resuelto desde TMDB. Local y NO sincronizado, igual
 * que [ArtworkEntity]: es caché derivable, no datos del usuario. Va en su propia tabla y no como
 * columna de `episodes` a propósito — esa tabla tiene triggers de sync, y tocar 49 filas por serie
 * las empujaría a la nube sin necesidad.
 *
 * [stillUrl] null = ya se consultó y TMDB no tenía imagen; la fila igual queda para no repreguntar.
 */
@Entity(tableName = "episode_still")
data class EpisodeStillEntity(
    @PrimaryKey val episodeId: String,
    val stillUrl: String? = null,
    val fetchedAt: Long = 0,
)
