package com.arkiv.player.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/** Combined row for the home's "Continue watching" row. */
data class ContinueRow(
    val episodeId: String,
    val itemId: String,
    val itemTitle: String,
    val displayName: String,
    val thumbPath: String?,
    val itemThumbnailUrl: String,
    /** The item's synopsis (not the episode's); null on items added with no metadata. */
    val itemDescription: String?,
    val positionMs: Long,
    val durationMs: Long,
    val lastPlayedAt: Long,
    /**
     * Episode still and title from TMDB. Written by two different paths, not one:
     * `ensureEpisodeStills` (everything except Magis -- Ditu today, plus legacy torrent/web/archive
     * rows saved before this branch's pruning -- by asking TMDB) and, for Magis,
     * `addMagisSeason`/`addMagisSource`, with what the gateway already matched against TMDB when it
     * handed over the chapters. Null if the chapter has no row in `episode_still` (e.g. a movie) or
     * if the data couldn't be resolved.
     */
    val stillUrl: String? = null,
    val episodeTitle: String? = null,
    /**
     * Chapter numbering, for the home hero's data line (see
     * [com.arkiv.player.ui.EtiquetaDeCapitulo.lineaDeHeroe]). `season`/`episode` are null when the
     * file name declared no numbering; then `orderIndex` decides, which does NOT mean the same
     * thing across every source — [com.arkiv.player.data.NumeracionCodificada] handles that, and
     * to decide it also needs `itemId` (already above) and `section`.
     */
    val season: Int? = null,
    val episode: Int? = null,
    val orderIndex: Int = 0,
    /** The episode's section ("Temporada 1" on sources that number, "" or the folder if not). */
    val section: String = "",
    /**
     * How many live episodes the item has. Feeds into [isMovie] together with [categoryOverride];
     * not used alone, because a freshly added standalone chapter (Magis, web, catalog torrent,
     * anime) also gives 1 and is NOT a movie (see [categoryOverride]).
     */
    val episodeCount: Int = 0,
    /**
     * The item's manual override ("movie"/"series"/null), same as `items.categoryOverride`.
     * Every source with chapters writes it as "series" from the first chapter on (see
     * `MagisEntities`), precisely so [isMovie] doesn't mistake that first chapter for a movie
     * while `episodeCount` is still 1.
     */
    val categoryOverride: String? = null,
    /**
     * On-disk path of the captured frame, or null if the chapter doesn't have one yet. Wins over
     * `stillUrl` and the rest: see [com.arkiv.player.thumbnails.ThumbnailChoice].
     *
     * Does NOT come from the query: the file name is derived from the episodeId by hash, so the
     * only source of truth is the disk. The repository fills it in when mapping.
     */
    val framePath: String? = null,
) {
    /** Same rule as [LibraryRow.isMovie]: manual override if it exists, otherwise detection by count. */
    val isMovie: Boolean get() = when (categoryOverride) {
        "movie" -> true
        "series" -> false
        else -> episodeCount <= 1
    }
}

/**
 * A chapter's progress with the item it belongs to and the chapter that comes NEXT in the list.
 *
 * The "next" one comes resolved from SQL because [com.arkiv.player.data.PorDondeVas] needs it to
 * offer the chapter that follows the last one you finished, and pulling each series' whole
 * chapter list into memory to figure it out would mean fetching thousands of rows to use one.
 */
data class ProgresoConSiguienteRow(
    val episodeId: String,
    val itemId: String,
    val positionMs: Long,
    val watched: Boolean,
    val lastPlayedAt: Long,
    /** The same item's next chapter, or null if this is the last one. */
    val siguienteEpisodeId: String?,
)

/** Raw row to decide which series to ask about new chapters. See `SeriesPorRevisar`. */
data class SerieConProgresoRow(
    val itemId: String,
    val source: String,
    val episodios: Int,
    val ultimoVistoMs: Long,
)

/** What's been watched of an item, for the TV library's "Ya visto" section. */
data class VistoRow(
    val itemId: String,
    val episodios: Int,
    val ultimoVistoMs: Long,
)

/** When something from an item was last played, to order the library. */
data class UltimaReproduccionRow(
    val itemId: String,
    val ultimaMs: Long,
)

/** Summary of an item for the library grid. */
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
    /** Cuántos episodios se le mostraron al usuario la última vez. Null = nunca. Ver `NewEpisodeCounter`. */
    val episodiosVistosEnLista: Int? = null,
    /**
     * The work this item IS, according to TMDB. Filled in when it's added from search, or by
     * [com.arkiv.player.data.gateway.repararIdentidadDeMagis]'s title canonization for Magis items
     * that came in without it (0 is treated the same as absent).
     *
     * Existe acá porque es la llave que le falta a la biblioteca para agrupar: un capítulo suelto
     * guardado con el título del capítulo ("T1 - E7: Construido por los hombres") no le pega a
     * ninguna búsqueda de TMDB, así que `artwork` nunca le resuelve nada. Ver [LibraryGrouping].
     */
    val tmdbId: Int? = null,
    /**
     * "tv" o "movie" según la obra que este ítem ES, verificado por el gateway contra TMDB.
     * Vacío cuando nadie lo sabe: mejor un hueco que un tipo inventado.
     *
     * NO es [isMovie] ni lo reemplaza: `isMovie` sigue decidiendo qué pasa al tocar la tarjeta
     * (una película reproduce directo, una serie abre la lista). Esto solo le dice a
     * [LibraryGrouping] si el `tmdbId` es de una serie, porque agrupar por el id de una película
     * junta obras distintas.
     */
    val tipo: String? = null,
) {
    /** Override manual si existe; si no, detección automática (1 video = película). */
    val isMovie: Boolean get() = when (categoryOverride) {
        "movie" -> true
        "series" -> false
        else -> episodeCount <= 1
    }
}

/** Una reproducción con su capítulo y su ítem, para el historial de "Para ti". Solo lectura. */
data class FilaDeHistorial(
    val episodeId: String,
    val positionMs: Long,
    val durationMs: Long,
    val watched: Boolean,
    val lastPlayedAt: Long,
    val episodio: Int?,
    val itemId: String,
    val titulo: String,
    val tituloCanonico: String?,
    val tipo: String?,
    val categoryOverride: String?,
    val tmdbId: Int?,
)

@Dao
interface ItemDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertItem(item: ItemEntity)

    /**
     * Records how many episodes were shown to the user, which is what turns off the "new
     * chapters" badge. Done with a point UPDATE and not with `upsertItem` on purpose: the upsert
     * is a REPLACE and would overwrite the rest of the row with whatever the caller has in memory.
     */
    @Query("UPDATE items SET episodiosVistosEnLista = :count WHERE identifier = :itemId")
    suspend fun markEpisodesSeen(itemId: String, count: Int)

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

    @Query("SELECT * FROM items WHERE identifier = :itemId")
    suspend fun getItem(itemId: String): ItemEntity?

    @Query("UPDATE items SET categoryOverride = :value WHERE identifier = :itemId")
    suspend fun updateCategoryOverride(itemId: String, value: String?)

    /**
     * Rename by hand. Clears `tituloCanonico` on purpose: what the person wrote is what's shown,
     * and if the canonical one were left set, the library's query (which prefers it) would keep
     * showing TMDB's name — the rename wouldn't show up anywhere.
     */
    @Query(
        "UPDATE items SET title = :title, tituloCanonico = NULL, updatedAt = :updatedAt " +
            "WHERE identifier = :itemId",
    )
    suspend fun updateTitle(itemId: String, title: String, updatedAt: Long)

    @Query(
        """
        SELECT i.identifier, COALESCE(NULLIF(TRIM(i.tituloCanonico), ''), i.title) AS title, i.description, i.thumbnailUrl,
               (SELECT COUNT(*) FROM episodes e WHERE e.itemId = i.identifier AND e.deleted = 0) AS episodeCount,
               (SELECT COALESCE(SUM(e.durationSeconds), 0) FROM episodes e WHERE e.itemId = i.identifier AND e.deleted = 0) AS durationSeconds,
               i.addedAt, i.categoryOverride, i.source, i.episodiosVistosEnLista, i.tmdbId, i.tipo
        FROM items i
        WHERE i.deleted = 0
        ORDER BY i.addedAt DESC
        """
    )
    fun observeLibrary(): Flow<List<LibraryRow>>

    /**
     * The library's series with just enough to decide which ones to ask whether a new chapter
     * came out: source, how many episodes they have and when something of theirs last played.
     *
     * The `MAX(lastPlayedAt)` is that of ANY episode of the series: whichever one you're on
     * doesn't matter, what matters is that you're watching it. `LEFT JOIN` so a series with no
     * progress shows up with 0 and the pure filter discards it, instead of disappearing here
     * (see [SeriesPorRevisar]).
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
    suspend fun seriesWithProgress(): List<SerieConProgresoRow>

    @Query("SELECT * FROM items WHERE identifier = :itemId")
    fun observeItem(itemId: String): Flow<ItemEntity?>

    // deleted = 0 on both: a deleted episode stays in the table as a tombstone (so the deletion
    // propagates through sync), but it isn't part of the series the user imported — not to list
    // it, not to count it, not to navigate to the next one.
    @Query("SELECT * FROM episodes WHERE itemId = :itemId AND deleted = 0 ORDER BY orderIndex ASC")
    fun observeEpisodes(itemId: String): Flow<List<EpisodeEntity>>

    @Query("SELECT * FROM episodes WHERE id = :episodeId")
    suspend fun getEpisode(episodeId: String): EpisodeEntity?

    @Query("SELECT * FROM episodes WHERE itemId = :itemId AND deleted = 0 ORDER BY orderIndex ASC")
    suspend fun getEpisodesOf(itemId: String): List<EpisodeEntity>

    @Query("SELECT * FROM items")
    suspend fun getAllItems(): List<ItemEntity>

    /** Soft delete: sets the tombstone; the trigger bumps updatedAt so it propagates. */
    @Query("UPDATE items SET deleted = 1 WHERE identifier = :itemId")
    suspend fun softDeleteItem(itemId: String)

    @Query("UPDATE episodes SET deleted = 1 WHERE itemId = :itemId")
    suspend fun softDeleteEpisodesOf(itemId: String)

    /**
     * A single episode. Used by `ArkivRepository.addMagisSeason` to sweep the one a save shaped
     * as a movie left over a series (see `MagisEntities.episodioIdDePelicula`).
     */
    @Query("UPDATE episodes SET deleted = 1 WHERE id = :episodeId")
    suspend fun softDeleteEpisode(episodeId: String)
}

@Dao
interface PlaybackDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(playback: PlaybackEntity)

    @Query("SELECT * FROM playback WHERE episodeId = :episodeId")
    suspend fun get(episodeId: String): PlaybackEntity?

    @Query("SELECT * FROM playback WHERE episodeId = :episodeId")
    fun observe(episodeId: String): Flow<PlaybackEntity?>

    /**
     * All live progress, with each one's next chapter, for
     * [com.arkiv.player.data.PorDondeVas] to build the "Continue watching" row.
     *
     * Does NOT filter by `watched` or by position, on purpose: filtering here was exactly the
     * bug. The old query asked for `watched = 0`, so of a series watched daily only the ABANDONED
     * chapters survived and the row ended up offering a chapter from thirty back (Dragon Ball on
     * device, 2026-08-13: e136 finished last night, the card showed e104). To know where you're
     * at you also have to see what's finished, which is what says where you left off; the
     * filtering is done by the rule, which has the whole series' context, not the row-by-row query.
     *
     * The tiebreak by `id` in the next-chapter subselect is NOT cosmetic: two chapters with the
     * same `orderIndex` (happens when the source doesn't number) would make `> orderIndex` skip
     * over the sibling.
     */
    @Query(
        """
        SELECT p.episodeId AS episodeId, e.itemId AS itemId,
               p.positionMs AS positionMs, p.watched AS watched, p.lastPlayedAt AS lastPlayedAt,
               (SELECT e2.id FROM episodes e2
                 WHERE e2.itemId = e.itemId AND e2.deleted = 0
                   AND (e2.orderIndex > e.orderIndex
                        OR (e2.orderIndex = e.orderIndex AND e2.id > e.id))
                 ORDER BY e2.orderIndex ASC, e2.id ASC
                 LIMIT 1) AS siguienteEpisodeId
        FROM playback p
        JOIN episodes e ON e.id = p.episodeId
        JOIN items i ON i.identifier = e.itemId
        WHERE p.deleted = 0 AND e.deleted = 0 AND i.deleted = 0
        """
    )
    fun observeProgressWithNext(): Flow<List<ProgresoConSiguienteRow>>

    /**
     * The screen data of the chapters [com.arkiv.player.data.PorDondeVas] already chose.
     *
     * Hangs off `episodes` and NOT `playback`, with the progress in a LEFT JOIN, because the
     * chosen chapter can be one you never touched (the one after what you finished): there's no
     * `playback` row there and the card goes with the bar at zero.
     *
     * `lastPlayedAt` comes out as 0 in that case; the repository overwrites it with the anchor's,
     * which is what orders the row (see `observeContinueWatching`).
     */
    @Query(
        """
        SELECT e.id AS episodeId, e.itemId AS itemId, COALESCE(NULLIF(TRIM(i.tituloCanonico), ''), i.title) AS itemTitle,
               e.displayName AS displayName, e.thumbPath AS thumbPath,
               i.thumbnailUrl AS itemThumbnailUrl, i.description AS itemDescription,
               COALESCE(p.positionMs, 0) AS positionMs, COALESCE(p.durationMs, 0) AS durationMs,
               COALESCE(p.lastPlayedAt, 0) AS lastPlayedAt,
               s.stillUrl AS stillUrl, s.title AS episodeTitle,
               e.season AS season, e.episode AS episode, e.orderIndex AS orderIndex,
               e.section AS section,
               (SELECT COUNT(*) FROM episodes e2 WHERE e2.itemId = e.itemId AND e2.deleted = 0) AS episodeCount,
               i.categoryOverride AS categoryOverride
        FROM episodes e
        JOIN items i ON i.identifier = e.itemId
        LEFT JOIN playback p ON p.episodeId = e.id AND p.deleted = 0
        LEFT JOIN episode_still s ON s.episodeId = e.id
        WHERE e.id IN (:episodeIds) AND e.deleted = 0 AND i.deleted = 0
        """
    )
    suspend fun continueWatchingRows(episodeIds: List<String>): List<ContinueRow>

    /**
     * Items with already-watched chapters, with how many and when the last one was.
     *
     * Twin of [observeContinueWatching] but backwards (`watched = 1`): what comes out of
     * "Continue watching" on finishing it has to land somewhere, and until now it landed nowhere.
     *
     * Doesn't do a `JOIN items`: the live-item filter is applied by `LibraryWatched.cross`, which
     * already receives the groups (and the groups already exclude the deleted ones). Adding the
     * join here would duplicate that rule in two places.
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
    fun observeWatched(): Flow<List<VistoRow>>

    /**
     * When ANY chapter of each item was last played, for the library's order (see
     * [com.arkiv.player.data.biblioteca.LibraryOrder]).
     *
     * Twin of [observeWatched] but WITHOUT the `watched = 1` filter: here a finished chapter
     * counts the same as one left halfway. If it only counted the finished ones, a series you're
     * watching right now wouldn't move up until you finish the chapter; if it only counted the
     * halfway ones, it would fall off the top right as you finish it.
     *
     * Doesn't do a `JOIN items`: the live-item filter is applied by whoever crosses this map
     * against the library, which already excludes the deleted ones. Same criterion as [observeWatched].
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
    fun observeLastPlayed(): Flow<List<UltimaReproduccionRow>>

    @Query("SELECT * FROM playback WHERE episodeId IN (SELECT id FROM episodes WHERE itemId = :itemId)")
    fun observePlaybackForItem(itemId: String): Flow<List<PlaybackEntity>>

    /** The last things played, with their item, from most recent to oldest. For "Para ti". */
    @Query(
        """
        SELECT p.episodeId AS episodeId, p.positionMs AS positionMs, p.durationMs AS durationMs,
               p.watched AS watched, p.lastPlayedAt AS lastPlayedAt, e.episode AS episodio,
               i.identifier AS itemId, i.title AS titulo, i.tituloCanonico AS tituloCanonico,
               i.tipo AS tipo, i.categoryOverride AS categoryOverride, i.tmdbId AS tmdbId
        FROM playback p
        JOIN episodes e ON e.id = p.episodeId
        JOIN items i ON i.identifier = e.itemId
        WHERE p.deleted = 0 AND e.deleted = 0 AND i.deleted = 0
        ORDER BY p.lastPlayedAt DESC
        LIMIT :limit
        """
    )
    suspend fun recentHistory(limit: Int): List<FilaDeHistorial>
}

/** Descarga combinada con datos del episodio para mostrar en pantalla. */
data class DownloadRow(
    val episodeId: String,
    val itemId: String,
    val itemTitle: String,
    val displayName: String,
    val thumbPath: String?,
    /** The item's poster (`items.thumbnailUrl`), used as a fallback when [thumbPath] is null. */
    val itemThumbnailUrl: String,
    val state: String,
    val progress: Float,
    val localUri: String?,
    val bytes: Long,
    val source: String,
    val error: String?,
    val bytesDone: Long,
    /**
     * Where the chapter came from (`episodes.torrentData`): the page URL for web, the torrent data
     * for torrent, null for archive.org. Those sources were all deleted in this branch's pruning.
     * It has no reader today; it stays until the Phase 3 column audit because it projects the
     * `torrentData` column.
     */
    val sourceRef: String? = null,
)

@Dao
interface SkipMarkerDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(marker: SkipMarkerEntity)

    /** The WHOLE series' marker (the one set by hand in the dialog): empty `episodeId`. */
    @Query("SELECT * FROM skip_markers WHERE itemId = :itemId AND episodeId = ''")
    suspend fun get(itemId: String): SkipMarkerEntity?

    @Query("SELECT * FROM skip_markers WHERE itemId = :itemId AND episodeId = ''")
    fun observe(itemId: String): Flow<SkipMarkerEntity?>

    /** The chapter's and the series', in a single query. `ChapterMarker.choose` decides which wins. */
    @Query("SELECT * FROM skip_markers WHERE itemId = :itemId AND episodeId IN (:episodeId, '') AND deleted = 0")
    fun observeForChapter(itemId: String, episodeId: String): Flow<List<SkipMarkerEntity>>

    /** One row by its own key (PK). Used by [com.arkiv.player.data.ArkivRepository.getSkipMarker] to read the marker for an exact scope (chapter or whole series). */
    @Query("SELECT * FROM skip_markers WHERE id = :id")
    suspend fun getById(id: String): SkipMarkerEntity?

    /** Deletes the SERIES' marker set by hand (empty `episodeId`); chapter ones aren't touched. */
    @Query("DELETE FROM skip_markers WHERE itemId = :itemId AND episodeId = ''")
    suspend fun delete(itemId: String)

    @Query("SELECT * FROM skip_markers")
    suspend fun getAll(): List<SkipMarkerEntity>
}

@Dao
interface LiveFavoriteDao {
    @Query("SELECT * FROM live_favorites WHERE deleted = 0 ORDER BY numero")
    fun flowAll(): Flow<List<LiveFavoriteEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(f: LiveFavoriteEntity)

    // Doesn't touch `updatedAt` here: leaving it alone is what lets the SyncTriggers UPDATE
    // trigger's guard (`WHEN NEW.updatedAt = OLD.updatedAt`) fire and reseal it with a fresh clock
    // -- same pattern as `softDeleteItem`. Nothing reads that clock anymore (see SyncTriggers),
    // but the trigger still runs on every local write.
    @Query("UPDATE live_favorites SET deleted = 1 WHERE code = :code")
    suspend fun delete(code: String)

    @Query("SELECT EXISTS(SELECT 1 FROM live_favorites WHERE code = :code AND deleted = 0)")
    suspend fun isFavorite(code: String): Boolean

    // --- Sync (same pattern as skip_markers) ---
    @Query("SELECT * FROM live_favorites")
    suspend fun getAll(): List<LiveFavoriteEntity>
}

@Dao
interface LiveRecentDao {
    @Query("SELECT * FROM live_recents ORDER BY vistoAt DESC LIMIT :limit")
    fun flowRecent(limit: Int = 20): Flow<List<LiveRecentEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun record(r: LiveRecentEntity)

    // --- Sync (same pattern as skip_markers) ---
    @Query("SELECT * FROM live_recents")
    suspend fun getAll(): List<LiveRecentEntity>

    /**
     * One-time 2026-08-14 purge: adult channels that stayed recorded from BEFORE
     * `abrirCanalActual` stopped recording them. They showed up in the home's "Canales en vivo"
     * row, in plain sight of anyone.
     *
     * Deletes EVERYTHING and not just the adult ones because the device has no way to know which
     * ones were: the recents store code and name, not the category. And it costs nothing — the
     * cloud was already left clean, so the next sync repopulates the list with the legitimate ones.
     */
    @Query("DELETE FROM live_recents")
    suspend fun deleteAll()
}

@Dao
interface LiveChannelCacheDao {
    @Query("SELECT * FROM live_channels_cache WHERE categoria = :category ORDER BY numero")
    suspend fun byCategory(category: Int): List<LiveChannelCacheEntity>

    /**
     * Cached rows of a specific list of channels (by `code`), with no category filter -- to
     * enrich with logo/number data that arrives from another source that carries no category of
     * its own (the home row's "recents", see `canalesRecientesParaHome` in
     * `ui/live/RecentLiveChannels.kt`). Can return more than one row per `code` (a channel can be
     * cached in several of the portal's categories): logo/number don't change between categories,
     * so the caller doesn't care which one it gets.
     */
    @Query("SELECT * FROM live_channels_cache WHERE code IN (:codes)")
    suspend fun byCodes(codes: List<String>): List<LiveChannelCacheEntity>

    @Query("DELETE FROM live_channels_cache WHERE categoria = :category")
    suspend fun clear(category: Int)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(rows: List<LiveChannelCacheEntity>)

    @Transaction
    suspend fun replace(category: Int, rows: List<LiveChannelCacheEntity>) {
        clear(category)
        save(rows)
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
     * Corrige la `source` (o sea la estrategia) de una fila ya guardada. Hace falta para las filas
     * que se encolaron con la estrategia equivocada: "Reintentar" conserva la fila tal cual, así que
     * sin esto volverían a fallar igual para siempre. Ver `FuenteDeDescarga`.
     */
    @Query("UPDATE downloads SET source = :source WHERE episodeId = :episodeId")
    suspend fun updateSource(episodeId: String, source: String)

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
        SELECT d.episodeId AS episodeId, e.itemId AS itemId, COALESCE(NULLIF(TRIM(i.tituloCanonico), ''), i.title) AS itemTitle,
               e.displayName AS displayName, e.thumbPath AS thumbPath,
               i.thumbnailUrl AS itemThumbnailUrl,
               d.state AS state, d.progress AS progress, d.localUri AS localUri, d.bytes AS bytes,
               d.source AS source, d.error AS error, d.bytesDone AS bytesDone,
               e.torrentData AS sourceRef
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

    /**
     * Same as [get] but WITHOUT the `deleted = 0` filter: needed by `FrameDestroyer.destroy`
     * to be idempotent -- it needs to know whether the row is ALREADY a tombstone (and skip
     * rewriting it) or is only now going from live to deleted.
     *
     * Don't use it for anything else: every other caller DOES want a deleted row to count as
     * "there's no frame".
     */
    @Query("SELECT * FROM episode_frame WHERE episodeId = :episodeId")
    suspend fun getIncluyendoBorradas(episodeId: String): EpisodeFrameEntity?

    /**
     * Filas (sin borrar) de los capítulos de un ítem, para el detalle de una serie. Misma forma
     * que [EpisodeStillDao.observeForItem]: el repositorio la usa solo como DISPARADOR del Flow
     * (ver `ArkivRepository.observeEpisodeFrames`), no como fuente de la ruta.
     */
    @Query("SELECT * FROM episode_frame WHERE deleted = 0 AND episodeId IN (SELECT id FROM episodes WHERE itemId = :itemId)")
    fun observeForItem(itemId: String): Flow<List<EpisodeFrameEntity>>

    /**
     * ALL live rows, as the TRIGGER for the "Continue watching" Flow.
     *
     * Exists because of a hole in the home screen: `PlaybackDao.observeContinueWatching` touches
     * `playback`, `episodes`, `items` and `episode_still`, but not `episode_frame`. Since Room
     * invalidates by table, the frame that [com.arkiv.player.thumbnails.FrameCapturer] saves (file
     * + `episode_frame` row) never notified that query: the card stayed on the TMDB still until
     * something else changed.
     *
     * Returns the whole list and not a `COUNT`: the content doesn't matter -the repository only
     * uses it as a signal that "something changed in `episode_frame`"- but a row query has the
     * same shape as [observeForItem] and doesn't hide the real cost.
     */
    @Query("SELECT * FROM episode_frame WHERE deleted = 0")
    fun observeTodos(): Flow<List<EpisodeFrameEntity>>

    /**
     * Se lleva TODAS las filas de una sola vez, para el wipe de logout: ahí no hay una lista de
     * capítulos que recorrer (los `items`/`episodes` se borran en el mismo barrido) y borrar de a
     * uno exigiría leer antes lo que se va a borrar.
     */
    @Query("DELETE FROM episode_frame")
    suspend fun borrarTodo()
}

/**
 * Única fuente de verdad de la consulta "vigentes" de [RecomendacionDao.observeVigentes]: la usa el
 * `@Query` real de abajo Y `RecomendacionQueryTest` (que la corre contra SQLite de verdad por JDBC,
 * ver su KDoc). Un `@Query` de Room solo acepta constantes de compilación, así que un `const val`
 * es lo mínimo que permite que las dos partes lean el MISMO string en vez de mantener dos copias a
 * mano que se puedan desincronizar en silencio -- que es exactamente lo que pasaba antes: el test
 * tenía su propia copia del SQL, y quitar el `WHERE deleted = 0` de acá no lo hacía fallar.
 */
internal const val QUERY_RECOMENDACIONES_VIGENTES =
    "SELECT * FROM recomendaciones WHERE deleted = 0 ORDER BY orden ASC"

@Dao
interface RecomendacionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(r: RecomendacionEntity)

    /**
     * Por `id` (la clave local, ver [RecomendacionEntity]). Sin filtro de `deleted`: la consulta
     * también devuelve lo que [reemplazar] ya retiró con tombstone.
     */
    @Query("SELECT * FROM recomendaciones WHERE id = :id")
    suspend fun get(id: String): RecomendacionEntity?

    /**
     * Las recomendaciones vigentes, en el orden que armó
     * [com.arkiv.player.data.recomendaciones.ForYouGenerator], sin lo que ya se marcó como
     * tombstone. Es la fuente de la fila "Para ti" del inicio.
     */
    @Query(QUERY_RECOMENDACIONES_VIGENTES)
    fun observeVigentes(): Flow<List<RecomendacionEntity>>

    @Query("UPDATE recomendaciones SET deleted = 1, updatedAt = :ahora WHERE deleted = 0")
    suspend fun retirarVigentes(ahora: Long)

    /**
     * Cambia la fila entera de una vez: nunca queda a medias entre la tanda vieja y la nueva. Se
     * retiran con tombstone y no se borran, igual que el resto de las tablas con `deleted`.
     */
    @Transaction
    suspend fun reemplazar(nuevas: List<RecomendacionEntity>, ahora: Long) {
        retirarVigentes(ahora)
        nuevas.forEach { upsert(it) }
    }
}
