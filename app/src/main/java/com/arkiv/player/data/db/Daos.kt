package com.arkiv.player.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/** Fila combinada para la fila "Continuar viendo" del inicio. */
data class ContinueRow(
    val episodeId: String,
    val itemId: String,
    val itemTitle: String,
    val displayName: String,
    val thumbPath: String?,
    val itemThumbnailUrl: String,
    /** Sinopsis del ítem (no del episodio); null en los ítems que se agregaron sin metadata. */
    val itemDescription: String?,
    val positionMs: Long,
    val durationMs: Long,
    val lastPlayedAt: Long,
)

/** Resumen de un ítem para la grilla de la biblioteca. */
data class LibraryRow(
    val identifier: String,
    val title: String,
    /** Sinopsis del ítem; null en los que se agregaron sin metadata (web, magnet suelto). */
    val description: String?,
    val thumbnailUrl: String,
    val episodeCount: Int,
    val durationSeconds: Double,
    val addedAt: Long,
    val categoryOverride: String?,
    val source: String,
) {
    val isTorrent: Boolean get() = source == "torrent"

    /** Override manual si existe; si no, detección automática (1 video = película). */
    val isMovie: Boolean get() = when (categoryOverride) {
        "movie" -> true
        "series" -> false
        else -> episodeCount <= 1
    }
}

@Dao
interface ItemDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertItem(item: ItemEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertEpisodes(episodes: List<EpisodeEntity>)

    @Query("DELETE FROM episodes WHERE itemId = :itemId")
    suspend fun deleteEpisodesOf(itemId: String)

    @Transaction
    suspend fun replaceItem(item: ItemEntity, episodes: List<EpisodeEntity>) {
        upsertItem(item)
        deleteEpisodesOf(item.identifier)
        upsertEpisodes(episodes)
    }

    @Query("DELETE FROM items WHERE identifier = :itemId")
    suspend fun deleteItem(itemId: String)

    @Query("SELECT * FROM items WHERE identifier = :itemId")
    suspend fun getItem(itemId: String): ItemEntity?

    @Query("UPDATE items SET categoryOverride = :value WHERE identifier = :itemId")
    suspend fun updateCategoryOverride(itemId: String, value: String?)

    @Query("UPDATE items SET title = :title, updatedAt = :updatedAt WHERE identifier = :itemId")
    suspend fun updateTitle(itemId: String, title: String, updatedAt: Long)

    @Query(
        """
        SELECT i.identifier, i.title, i.description, i.thumbnailUrl,
               (SELECT COUNT(*) FROM episodes e WHERE e.itemId = i.identifier AND e.deleted = 0) AS episodeCount,
               (SELECT COALESCE(SUM(e.durationSeconds), 0) FROM episodes e WHERE e.itemId = i.identifier AND e.deleted = 0) AS durationSeconds,
               i.addedAt, i.categoryOverride, i.source
        FROM items i
        WHERE i.deleted = 0
        ORDER BY i.addedAt DESC
        """
    )
    fun observeLibrary(): Flow<List<LibraryRow>>

    @Query("SELECT * FROM items WHERE identifier = :itemId")
    fun observeItem(itemId: String): Flow<ItemEntity?>

    // deleted = 0 en las dos: un episodio borrado sigue en la tabla como tombstone (para que el
    // borrado se propague por el sync), pero no es parte de la serie que el usuario importó — ni
    // para listarlo, ni para contarlo, ni para navegar al siguiente.
    @Query("SELECT * FROM episodes WHERE itemId = :itemId AND deleted = 0 ORDER BY orderIndex ASC")
    fun observeEpisodes(itemId: String): Flow<List<EpisodeEntity>>

    @Query("SELECT * FROM episodes WHERE id = :episodeId")
    suspend fun getEpisode(episodeId: String): EpisodeEntity?

    @Query("SELECT * FROM episodes WHERE itemId = :itemId AND deleted = 0 ORDER BY orderIndex ASC")
    suspend fun getEpisodesOf(itemId: String): List<EpisodeEntity>

    @Query("SELECT * FROM items")
    suspend fun getAllItems(): List<ItemEntity>

    @Query("SELECT * FROM episodes")
    suspend fun getAllEpisodes(): List<EpisodeEntity>

    // --- Sync en la nube (Plan 4): filas dirty por updatedAt + soft-delete (tombstone) ---
    @Query("SELECT * FROM items WHERE updatedAt > :cursor")
    suspend fun getItemsSince(cursor: Long): List<ItemEntity>

    @Query("SELECT * FROM episodes WHERE updatedAt > :cursor")
    suspend fun getEpisodesSince(cursor: Long): List<EpisodeEntity>

    /** Borrado suave: marca el tombstone; el trigger sube updatedAt para que se propague. */
    @Query("UPDATE items SET deleted = 1 WHERE identifier = :itemId")
    suspend fun softDeleteItem(itemId: String)

    @Query("UPDATE episodes SET deleted = 1 WHERE itemId = :itemId")
    suspend fun softDeleteEpisodesOf(itemId: String)
}

@Dao
interface PlaybackDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(playback: PlaybackEntity)

    @Query("SELECT * FROM playback WHERE episodeId = :episodeId")
    suspend fun get(episodeId: String): PlaybackEntity?

    @Query("SELECT * FROM playback WHERE episodeId = :episodeId")
    fun observe(episodeId: String): Flow<PlaybackEntity?>

    @Query(
        """
        SELECT p.episodeId AS episodeId, e.itemId AS itemId, i.title AS itemTitle,
               e.displayName AS displayName, e.thumbPath AS thumbPath,
               i.thumbnailUrl AS itemThumbnailUrl, i.description AS itemDescription,
               p.positionMs AS positionMs, p.durationMs AS durationMs,
               p.lastPlayedAt AS lastPlayedAt
        FROM playback p
        JOIN episodes e ON e.id = p.episodeId
        JOIN items i ON i.identifier = e.itemId
        WHERE p.watched = 0 AND p.positionMs > :minPositionMs AND i.deleted = 0 AND p.deleted = 0
        ORDER BY p.lastPlayedAt DESC
        LIMIT 60
        """
    )
    fun observeContinueWatching(minPositionMs: Long): Flow<List<ContinueRow>>

    @Query("SELECT * FROM playback WHERE episodeId IN (SELECT id FROM episodes WHERE itemId = :itemId)")
    fun observePlaybackForItem(itemId: String): Flow<List<PlaybackEntity>>

    @Query("SELECT * FROM playback")
    suspend fun getAllPlayback(): List<PlaybackEntity>

    // --- Sync en la nube (Plan 4) ---
    @Query("SELECT * FROM playback WHERE updatedAt > :cursor")
    suspend fun getPlaybackSince(cursor: Long): List<PlaybackEntity>

    @Query("UPDATE playback SET deleted = 1 WHERE episodeId = :episodeId")
    suspend fun softDeletePlayback(episodeId: String)
}

/** Descarga combinada con datos del episodio para mostrar en pantalla. */
data class DownloadRow(
    val episodeId: String,
    val itemId: String,
    val itemTitle: String,
    val displayName: String,
    val thumbPath: String?,
    val state: String,
    val progress: Float,
    val localUri: String?,
    val bytes: Long,
    val source: String,
    val error: String?,
    val bytesDone: Long,
)

@Dao
interface SkipMarkerDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(marker: SkipMarkerEntity)

    @Query("SELECT * FROM skip_markers WHERE itemId = :itemId")
    suspend fun get(itemId: String): SkipMarkerEntity?

    @Query("SELECT * FROM skip_markers WHERE itemId = :itemId")
    fun observe(itemId: String): Flow<SkipMarkerEntity?>

    @Query("DELETE FROM skip_markers WHERE itemId = :itemId")
    suspend fun delete(itemId: String)

    @Query("SELECT * FROM skip_markers")
    suspend fun getAll(): List<SkipMarkerEntity>

    // --- Sync en la nube (Plan 4) ---
    @Query("SELECT * FROM skip_markers WHERE updatedAt > :cursor")
    suspend fun getMarkersSince(cursor: Long): List<SkipMarkerEntity>

    @Query("UPDATE skip_markers SET deleted = 1 WHERE itemId = :itemId")
    suspend fun softDeleteMarker(itemId: String)
}

@Dao
interface DownloadDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(download: DownloadEntity)

    @Query("SELECT * FROM downloads WHERE episodeId = :episodeId")
    suspend fun get(episodeId: String): DownloadEntity?

    @Query("SELECT * FROM downloads")
    suspend fun getAll(): List<DownloadEntity>

    @Query("SELECT * FROM downloads")
    fun observeAll(): Flow<List<DownloadEntity>>

    @Query("UPDATE downloads SET state = :state, error = :error WHERE episodeId = :episodeId")
    suspend fun updateState(episodeId: String, state: String, error: String?)

    /**
     * Progreso SIN tocar `state`. Antes esta consulta también escribía el estado, y como el callback
     * de progreso llega varias veces por segundo, la fase de staging (web) nunca podía quedarse en
     * `staging`: el primer tick la devolvía a `downloading`. El estado lo maneja quien conoce la fase
     * (el worker y la estrategia), no el contador de bytes.
     */
    @Query(
        "UPDATE downloads SET progress = :progress, bytesDone = :bytesDone, bytes = :bytes " +
            "WHERE episodeId = :episodeId"
    )
    suspend fun updateProgress(episodeId: String, progress: Float, bytesDone: Long, bytes: Long)

    /** Motivo del último tropiezo sin cambiar el estado (fila que va a reintentarse sola). */
    @Query("UPDATE downloads SET error = :error WHERE episodeId = :episodeId")
    suspend fun setError(episodeId: String, error: String?)

    /**
     * Escribe la ruta DESNUDA en `filePath` (no un `file://` en `localUri`): `localUri` es el formato
     * histórico que dejaba el `DownloadManager` del sistema y queda solo para las filas viejas. Quien
     * resuelve "¿dónde está el archivo?" para las dos columnas —y verifica que exista— es
     * `LocalLibrary.fileFor`, que es el ÚNICO lector de esto.
     */
    @Query(
        "UPDATE downloads SET state = 'completed', progress = 1.0, filePath = :filePath, error = NULL " +
            "WHERE episodeId = :episodeId"
    )
    suspend fun markCompleted(episodeId: String, filePath: String)

    @Query("UPDATE downloads SET sizeConfirmed = 1, state = 'queued', error = NULL WHERE episodeId = :episodeId")
    suspend fun markConfirmed(episodeId: String)

    @Query("UPDATE downloads SET stagingItemId = :stagingItemId WHERE episodeId = :episodeId")
    suspend fun setStagingItem(episodeId: String, stagingItemId: Long?)

    /**
     * Items de la NUC que quedaron colgados: la fila ya terminó de bajar al dispositivo pero el
     * DELETE /library falló. El barrido de arranque los reintenta.
     */
    @Query("SELECT stagingItemId FROM downloads WHERE stagingItemId IS NOT NULL AND state = 'completed'")
    suspend fun orphanStagingItems(): List<Long>

    /** El barrido de arranque limpia la marca tras borrar el item de la NUC con éxito. */
    @Query("UPDATE downloads SET stagingItemId = NULL WHERE stagingItemId = :stagingItemId")
    suspend fun clearStagingItem(stagingItemId: Long)

    @Query("DELETE FROM downloads WHERE episodeId = :episodeId")
    suspend fun delete(episodeId: String)

    /**
     * Origen de todo lo que YA está descargado en el dispositivo, para no bajar dos veces el mismo
     * capítulo cuando la serie quedó guardada bajo dos ítems distintos (ver
     * [com.arkiv.player.data.local.DuplicateDownloadPolicy], que es quien decide). El
     * `torrentFileIndex` sale de `episodes` porque el episodeId solo lleva el infohash, no el
     * archivo elegido dentro del torrent.
     */
    @Query(
        """
        SELECT d.episodeId AS episodeId, e.torrentFileIndex AS torrentFileIndex
        FROM downloads d
        JOIN episodes e ON e.id = d.episodeId
        WHERE d.state = 'completed'
        """
    )
    suspend fun completedOrigins(): List<com.arkiv.player.data.local.EpisodeOrigin>

    @Query(
        """
        SELECT d.episodeId AS episodeId, e.itemId AS itemId, i.title AS itemTitle,
               e.displayName AS displayName, e.thumbPath AS thumbPath,
               d.state AS state, d.progress AS progress, d.localUri AS localUri, d.bytes AS bytes,
               d.source AS source, d.error AS error, d.bytesDone AS bytesDone
        FROM downloads d
        JOIN episodes e ON e.id = d.episodeId
        JOIN items i ON i.identifier = e.itemId
        ORDER BY d.createdAt DESC
        """
    )
    fun observeDownloadRows(): Flow<List<DownloadRow>>
}

@Dao
interface ArtworkDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(artwork: ArtworkEntity)

    @Query("SELECT * FROM artwork WHERE itemId = :itemId")
    suspend fun get(itemId: String): ArtworkEntity?

    @Query("SELECT * FROM artwork")
    fun observeAll(): Flow<List<ArtworkEntity>>
}

@Dao
interface EpisodeStillDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(stills: List<EpisodeStillEntity>)

    @Query("SELECT * FROM episode_still WHERE episodeId IN (SELECT id FROM episodes WHERE itemId = :itemId)")
    suspend fun forItem(itemId: String): List<EpisodeStillEntity>

    @Query("SELECT * FROM episode_still WHERE episodeId IN (SELECT id FROM episodes WHERE itemId = :itemId)")
    fun observeForItem(itemId: String): Flow<List<EpisodeStillEntity>>
}

@Dao
interface NucLibraryItemDao {
    @Query("SELECT * FROM nuc_library_items WHERE seriesId = :seriesId")
    suspend fun forSeries(seriesId: String): List<NucLibraryItemEntity>

    @Query("SELECT * FROM nuc_library_items WHERE seriesId = :seriesId AND season = :season AND episode = :episode LIMIT 1")
    suspend fun find(seriesId: String, season: Int, episode: Int): NucLibraryItemEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<NucLibraryItemEntity>)

    @Query("DELETE FROM nuc_library_items WHERE seriesId = :seriesId")
    suspend fun clearForSeries(seriesId: String)

    @Query("SELECT * FROM nuc_library_items ORDER BY seriesId, season, episode")
    suspend fun getAll(): List<NucLibraryItemEntity>
}

@Dao
interface SeriesPlaybackPrefDao {
    @Query("SELECT * FROM series_playback_prefs WHERE seriesId = :seriesId LIMIT 1")
    suspend fun get(seriesId: String): SeriesPlaybackPrefEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(pref: SeriesPlaybackPrefEntity)
}

@Dao
interface LocalActiveJobDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(job: LocalActiveJobEntity)

    @Query("DELETE FROM local_active_jobs WHERE jobId = :jobId")
    suspend fun delete(jobId: Long)

    @Query("SELECT * FROM local_active_jobs")
    suspend fun getAll(): List<LocalActiveJobEntity>
}

@Dao
interface SearchHistoryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: SearchHistoryEntity)

    @Query("SELECT * FROM search_history WHERE kind = :kind ORDER BY atMs DESC LIMIT :limit")
    suspend fun recent(kind: String, limit: Int = 20): List<SearchHistoryEntity>

    @Query("DELETE FROM search_history")
    suspend fun clear()
}
