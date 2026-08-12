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
    /**
     * Still y título del capítulo según TMDB. Los escriben DOS caminos distintos, no uno:
     * `ensureEpisodeStills` (torrent, web y archive, preguntándole a TMDB) y, en Magis,
     * `addMagisSeason`/`addMagisSource`, con lo que el gateway ya cruzó contra TMDB al entregar los
     * capítulos. Null si el capítulo no tiene fila en `episode_still` (p.ej. una película) o si no
     * se pudo resolver el dato.
     */
    val stillUrl: String? = null,
    val episodeTitle: String? = null,
    /**
     * Numeración del capítulo, para la línea de datos del héroe del home (ver
     * [com.arkiv.player.ui.EtiquetaDeCapitulo.lineaDeHeroe]). `season`/`episode` son null cuando el
     * nombre del archivo no declaraba numeración; ahí manda `orderIndex`, que en packs de torrent
     * codifica temporada*1000 + episodio y en archive.org es un correlativo 0..N-1.
     */
    val season: Int? = null,
    val episode: Int? = null,
    val orderIndex: Int = 0,
    /**
     * Cuántos episodios vivos tiene el ítem. Entra en [isMovie] junto con [categoryOverride]; no
     * se usa solo, porque un capítulo suelto recién agregado (Magis, web, torrent de catálogo,
     * anime) también da 1 y NO es una película (ver [categoryOverride]).
     */
    val episodeCount: Int = 0,
    /**
     * Override manual del ítem ("movie"/"series"/null), igual que en `items.categoryOverride`.
     * Todas las fuentes con capítulos lo escriben como "series" desde el primer capítulo (ver
     * `MagisEntities`), justamente para que [isMovie] no confunda ese primer capítulo con una
     * película mientras `episodeCount` todavía vale 1.
     */
    val categoryOverride: String? = null,
    /**
     * Ruta en disco del frame capturado, o null si el capítulo todavía no tiene uno. Gana sobre
     * `stillUrl` y el resto: ver [com.arkiv.player.miniaturas.EleccionDeMiniatura].
     *
     * NO sale de la query: el nombre del archivo se deriva del episodeId por hash, así que la
     * única fuente de verdad es el disco. Lo llena el repositorio al mapear.
     */
    val framePath: String? = null,
) {
    /** Misma regla que [LibraryRow.isMovie]: override manual si existe, si no, detección por cantidad. */
    val isMovie: Boolean get() = when (categoryOverride) {
        "movie" -> true
        "series" -> false
        else -> episodeCount <= 1
    }
}

/** Resumen de un ítem para la grilla de la biblioteca. */
/** Fila cruda para decidir a qué series preguntarles por capítulos nuevos. Ver `SeriesPorRevisar`. */
data class SerieConProgresoRow(
    val itemId: String,
    val source: String,
    val episodios: Int,
    val ultimoVistoMs: Long,
)

/** Lo visto de un ítem, para la sección "Ya visto" de la biblioteca del TV. */
data class VistoRow(
    val itemId: String,
    val episodios: Int,
    val ultimoVistoMs: Long,
)

/** Cuándo se reprodujo por última vez algo de un ítem, para ordenar la biblioteca. */
data class UltimaReproduccionRow(
    val itemId: String,
    val ultimaMs: Long,
)

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
    /** Cuántos episodios se le mostraron al usuario la última vez. Null = nunca. Ver `ContadorDeNuevos`. */
    val episodiosVistosEnLista: Int? = null,
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

    /**
     * Deja registrado cuántos episodios se le mostraron al usuario, que es lo que apaga el badge
     * de "hay capítulos nuevos". Se hace con UPDATE puntual y no con `upsertItem` a propósito: el
     * upsert es REPLACE y pisaría el resto de la fila con lo que tenga en memoria quien llame.
     */
    @Query("UPDATE items SET episodiosVistosEnLista = :cuantos WHERE identifier = :itemId")
    suspend fun marcarEpisodiosVistos(itemId: String, cuantos: Int)

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
               i.addedAt, i.categoryOverride, i.source, i.episodiosVistosEnLista
        FROM items i
        WHERE i.deleted = 0
        ORDER BY i.addedAt DESC
        """
    )
    fun observeLibrary(): Flow<List<LibraryRow>>

    /**
     * Las series de la biblioteca con lo justo para decidir a cuáles preguntarles si salió un
     * capítulo nuevo: fuente, cuántos episodios tienen y cuándo se reprodujo algo de ellas por
     * última vez.
     *
     * El `MAX(lastPlayedAt)` es el de CUALQUIER episodio de la serie: da igual por cuál vas, lo
     * que importa es que la estés viendo. `LEFT JOIN` para que una serie sin progreso aparezca con
     * 0 y la descarte el filtro puro, en vez de desaparecer acá (ver [SeriesPorRevisar]).
     */
    @Query(
        """
        SELECT i.identifier AS itemId, i.source AS source,
               (SELECT COUNT(*) FROM episodes e WHERE e.itemId = i.identifier AND e.deleted = 0) AS episodios,
               COALESCE((SELECT MAX(p.lastPlayedAt) FROM playback p
                         JOIN episodes e2 ON e2.id = p.episodeId
                         WHERE e2.itemId = i.identifier AND p.deleted = 0), 0) AS ultimoVistoMs
        FROM items i
        WHERE i.deleted = 0
        """
    )
    suspend fun seriesConProgreso(): List<SerieConProgresoRow>

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

    @Query("DELETE FROM items")
    suspend fun deleteAllItems()

    @Query("DELETE FROM episodes")
    suspend fun deleteAllEpisodes()
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
               p.lastPlayedAt AS lastPlayedAt,
               s.stillUrl AS stillUrl, s.title AS episodeTitle,
               e.season AS season, e.episode AS episode, e.orderIndex AS orderIndex,
               (SELECT COUNT(*) FROM episodes e2 WHERE e2.itemId = e.itemId AND e2.deleted = 0) AS episodeCount,
               i.categoryOverride AS categoryOverride
        FROM playback p
        JOIN episodes e ON e.id = p.episodeId
        JOIN items i ON i.identifier = e.itemId
        LEFT JOIN episode_still s ON s.episodeId = p.episodeId
        WHERE p.watched = 0 AND p.positionMs > :minPositionMs AND i.deleted = 0 AND p.deleted = 0
        ORDER BY p.lastPlayedAt DESC
        LIMIT 60
        """
    )
    fun observeContinueWatching(minPositionMs: Long): Flow<List<ContinueRow>>

    /**
     * Los ítems con capítulos ya vistos, con cuántos y cuándo fue el último.
     *
     * Gemela de [observeContinueWatching] pero al revés (`watched = 1`): lo que sale de "Continuar
     * viendo" al terminarlo tiene que aterrizar en algún lado, y hasta ahora no aterrizaba en
     * ninguno.
     *
     * NO hace `JOIN items`: el filtro por ítem vivo lo aplica `VistosDeLaBiblioteca.cruzar`, que ya
     * recibe los grupos (y los grupos ya excluyen los borrados). Sumar el join acá duplicaría esa
     * regla en dos lugares.
     */
    @Query(
        """
        SELECT e.itemId AS itemId, COUNT(*) AS episodios, MAX(p.lastPlayedAt) AS ultimoVistoMs
        FROM playback p
        JOIN episodes e ON e.id = p.episodeId
        WHERE p.watched = 1 AND p.deleted = 0 AND e.deleted = 0
        GROUP BY e.itemId
        """
    )
    fun observeVistos(): Flow<List<VistoRow>>

    /**
     * Cuándo se reprodujo por última vez CUALQUIER capítulo de cada ítem, para el orden de la
     * biblioteca (ver [com.arkiv.player.data.biblioteca.OrdenDeBiblioteca]).
     *
     * Gemela de [observeVistos] pero SIN el filtro `watched = 1`: acá cuenta igual el capítulo
     * terminado que el que quedó a medias. Si solo contara lo terminado, una serie que estás viendo
     * ahora mismo no subiría hasta que termines el capítulo; si solo contara lo de a medias, se caería
     * del tope justo al terminarlo.
     *
     * NO hace `JOIN items`: el filtro por ítem vivo lo aplica quien cruza este mapa contra la
     * biblioteca, que ya excluye los borrados. Mismo criterio que [observeVistos].
     */
    @Query(
        """
        SELECT e.itemId AS itemId, MAX(p.lastPlayedAt) AS ultimaMs
        FROM playback p
        JOIN episodes e ON e.id = p.episodeId
        WHERE p.deleted = 0 AND e.deleted = 0
        GROUP BY e.itemId
        """
    )
    fun observeUltimaReproduccion(): Flow<List<UltimaReproduccionRow>>

    @Query("SELECT * FROM playback WHERE episodeId IN (SELECT id FROM episodes WHERE itemId = :itemId)")
    fun observePlaybackForItem(itemId: String): Flow<List<PlaybackEntity>>

    @Query("SELECT * FROM playback")
    suspend fun getAllPlayback(): List<PlaybackEntity>

    // --- Sync en la nube (Plan 4) ---
    @Query("SELECT * FROM playback WHERE updatedAt > :cursor")
    suspend fun getPlaybackSince(cursor: Long): List<PlaybackEntity>

    @Query("UPDATE playback SET deleted = 1 WHERE episodeId = :episodeId")
    suspend fun softDeletePlayback(episodeId: String)

    @Query("DELETE FROM playback")
    suspend fun deleteAllPlayback()
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

    @Query("DELETE FROM skip_markers")
    suspend fun deleteAllMarkers()
}

@Dao
interface LiveFavoriteDao {
    @Query("SELECT * FROM live_favorites WHERE deleted = 0 ORDER BY numero")
    fun flowTodos(): Flow<List<LiveFavoriteEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun guardar(f: LiveFavoriteEntity)

    // NO se toca updatedAt acá (el brief original lo ponía en 0): esta tabla SÍ viaja por el
    // sync, y `updatedAt = 0` es la marca que usa el resto del código para "nunca se subió"
    // (ver KDoc de SyncTriggers.ddl y MIGRATION_7_8 en ArkivDatabase). Poner el borrado en 0
    // haría que el trigger de UPDATE no lo resellara (WHEN NEW.updatedAt = OLD.updatedAt no se
    // cumpliría) y el tombstone se quedaría sin `updatedAt` para siempre: el borrado nunca
    // llegaría al otro dispositivo. Se deja que el trigger sea quien selle, igual que
    // `softDeleteMarker`/`softDeleteItem`.
    @Query("UPDATE live_favorites SET deleted = 1 WHERE code = :code")
    suspend fun borrar(code: String)

    @Query("SELECT EXISTS(SELECT 1 FROM live_favorites WHERE code = :code AND deleted = 0)")
    suspend fun esFavorito(code: String): Boolean

    // --- Sync (mismo patrón que skip_markers) ---
    @Query("SELECT * FROM live_favorites")
    suspend fun getAll(): List<LiveFavoriteEntity>
}

@Dao
interface LiveRecentDao {
    @Query("SELECT * FROM live_recents ORDER BY vistoAt DESC LIMIT :limite")
    fun flowUltimos(limite: Int = 20): Flow<List<LiveRecentEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun anotar(r: LiveRecentEntity)

    // --- Sync (mismo patrón que skip_markers) ---
    @Query("SELECT * FROM live_recents")
    suspend fun getAll(): List<LiveRecentEntity>
}

@Dao
interface LiveChannelCacheDao {
    @Query("SELECT * FROM live_channels_cache WHERE categoria = :categoria ORDER BY numero")
    suspend fun deCategoria(categoria: Int): List<LiveChannelCacheEntity>

    /**
     * Filas cacheadas de una lista puntual de canales (por `code`), sin filtrar por categoría --
     * para enriquecer con logo/número datos que llegan de otra fuente que no trae categoría propia
     * (los "recientes" de la fila del home, ver
     * `canalesRecientesParaHome` en `ui/live/RecentLiveChannels.kt`). Puede devolver más de una
     * fila por `code` (un canal puede estar cacheado en varias categorías del portal): logo/numero
     * no cambian entre categorías, así que a quien llama le da igual cuál le llegue.
     */
    @Query("SELECT * FROM live_channels_cache WHERE code IN (:codes)")
    suspend fun deCodigos(codes: List<String>): List<LiveChannelCacheEntity>

    @Query("DELETE FROM live_channels_cache WHERE categoria = :categoria")
    suspend fun limpiar(categoria: Int)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun guardar(filas: List<LiveChannelCacheEntity>)

    @Transaction
    suspend fun reemplazar(categoria: Int, filas: List<LiveChannelCacheEntity>) {
        limpiar(categoria)
        guardar(filas)
    }
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

    /**
     * Archivos que siguen referenciados por OTRAS filas. Pasa cuando el worker adopta el archivo de
     * un gemelo en vez de re-descargarlo: borrar ese archivo al quitar cualquiera de las dos filas
     * dejaría a la otra diciendo "Listo" sobre algo que ya no está (ver
     * `DuplicateDownloadPolicy.deletablePaths`).
     *
     * Mira solo `filePath` y no el `localUri` histórico: quien adopta un archivo siempre pasa por
     * `markCompleted`, que escribe `filePath`. Un `localUri` solo puede ser el lado ADOPTADO, y ese
     * lado ya queda protegido porque el adoptante copió esa misma ruta a su `filePath`.
     */
    @Query("SELECT filePath FROM downloads WHERE filePath IS NOT NULL AND episodeId != :exceptEpisodeId")
    suspend fun filePathsReferencedByOthers(exceptEpisodeId: String): List<String>

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

    @Query("SELECT * FROM search_history WHERE kind = :kind ORDER BY atMs DESC LIMIT :limit")
    fun observeRecent(kind: String, limit: Int = 10): Flow<List<SearchHistoryEntity>>

    @Query("DELETE FROM search_history WHERE kind = :kind AND lower(query) = lower(:query)")
    suspend fun deleteOne(kind: String, query: String)

    @Query("DELETE FROM search_history WHERE kind = :kind")
    suspend fun clearKind(kind: String)

    @Query("DELETE FROM search_history")
    suspend fun clear()
}

@Dao
interface RecentTitleDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: RecentTitleEntity)

    @Query("SELECT * FROM recent_titles ORDER BY atMs DESC LIMIT :limit")
    fun observeRecent(limit: Int = 12): Flow<List<RecentTitleEntity>>

    @Query("DELETE FROM recent_titles WHERE id = :id")
    suspend fun deleteOne(id: String)

    @Query("DELETE FROM recent_titles")
    suspend fun clear()

    /** Borra lo que pase del tope. Cada fila arrastra una URL de póster: conviene podar. */
    @Query("DELETE FROM recent_titles WHERE id NOT IN (SELECT id FROM recent_titles ORDER BY atMs DESC LIMIT :keep)")
    suspend fun trim(keep: Int)
}

@Dao
interface EpisodeFrameDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(frame: EpisodeFrameEntity)

    @Query("SELECT * FROM episode_frame WHERE episodeId = :episodeId AND deleted = 0")
    suspend fun get(episodeId: String): EpisodeFrameEntity?

    @Query("DELETE FROM episode_frame WHERE episodeId = :episodeId")
    suspend fun borrar(episodeId: String)

    /**
     * Filas (sin borrar) de los capítulos de un ítem, para el detalle de una serie. Misma forma
     * que [EpisodeStillDao.observeForItem]: el repositorio la usa solo como DISPARADOR del Flow
     * (ver `ArkivRepository.observeEpisodeFrames`), no como fuente de la ruta.
     */
    @Query("SELECT * FROM episode_frame WHERE deleted = 0 AND episodeId IN (SELECT id FROM episodes WHERE itemId = :itemId)")
    fun observeForItem(itemId: String): Flow<List<EpisodeFrameEntity>>

    /**
     * Se lleva TODAS las filas de una sola vez, para el wipe de logout: ahí no hay una lista de
     * capítulos que recorrer (los `items`/`episodes` se borran en el mismo barrido) y borrar de a
     * uno exigiría leer antes lo que se va a borrar.
     */
    @Query("DELETE FROM episode_frame")
    suspend fun borrarTodo()
}
