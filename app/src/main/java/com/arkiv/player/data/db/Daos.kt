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
     * nombre del archivo no declaraba numeración; ahí manda `orderIndex`, que NO significa lo mismo
     * en todas las fuentes — de eso se encarga [com.arkiv.player.data.NumeracionCodificada], que
     * para decidirlo necesita también `itemId` (ya está arriba) y `section`.
     */
    val season: Int? = null,
    val episode: Int? = null,
    val orderIndex: Int = 0,
    /** La sección del episodio ("Temporada 1" en las fuentes que numeran, "" o la carpeta si no). */
    val section: String = "",
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

/**
 * Progreso de un capítulo con el ítem al que pertenece y el capítulo que le SIGUE en la lista.
 *
 * El "siguiente" viene resuelto desde SQL porque [com.arkiv.player.data.PorDondeVas] lo necesita
 * para ofrecer el capítulo que va después del último que terminaste, y traerse la lista completa de
 * capítulos de cada serie a memoria para averiguarlo sería traer miles de filas para usar una.
 */
data class ProgresoConSiguienteRow(
    val episodeId: String,
    val itemId: String,
    val positionMs: Long,
    val watched: Boolean,
    val lastPlayedAt: Long,
    /** El capítulo siguiente del mismo ítem, o null si este es el último. */
    val siguienteEpisodeId: String?,
)

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
    /**
     * La obra que este ítem ES, según TMDB. Lo llena el gateway con la canonización de títulos y
     * baja por el sync (ver `SyncMappers.recordToItem`, que ya trata el 0 como ausente).
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
    val isTorrent: Boolean get() = source == "torrent"

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

    /**
     * Renombrar a mano. Limpia `tituloCanonico` a propósito: lo que escribió la persona es lo que
     * se muestra, y si quedara el canónico puesto la consulta de la biblioteca (que lo prefiere)
     * seguiría mostrando el nombre de TMDB — el renombre no se vería por ningún lado.
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

    /**
     * Un solo episodio. Lo usa `ArkivRepository.addMagisSeason` para barrer el que dejó un guardado
     * con forma de película sobre una serie (ver `MagisEntities.episodioIdDePelicula`).
     */
    @Query("UPDATE episodes SET deleted = 1 WHERE id = :episodeId")
    suspend fun softDeleteEpisode(episodeId: String)

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

    /**
     * Todo el progreso vivo, con el capítulo siguiente de cada uno, para que
     * [com.arkiv.player.data.PorDondeVas] arme la fila "Continuar viendo".
     *
     * NO filtra por `watched` ni por posición, a propósito: filtrar acá fue exactamente el bug. La
     * consulta vieja pedía `watched = 0`, así que de una serie vista al día solo sobrevivían los
     * capítulos ABANDONADOS y la fila terminaba ofreciendo un capítulo de treinta atrás (Dragon Ball
     * en device, 2026-08-13: e136 terminado anoche, la tarjeta mostraba el e104). Para saber por
     * dónde vas hay que ver TAMBIÉN lo terminado, que es lo que dice dónde quedaste; el filtrado lo
     * hace la regla, que tiene el contexto de toda la serie, no la consulta fila por fila.
     *
     * El desempate por `id` en el subselect del siguiente NO es cosmético: dos capítulos con el
     * mismo `orderIndex` (pasa cuando la fuente no numera) harían que `> orderIndex` se saltara al
     * hermano.
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
    fun observeProgresoConSiguiente(): Flow<List<ProgresoConSiguienteRow>>

    /**
     * Los datos de pantalla de los capítulos que ya eligió [com.arkiv.player.data.PorDondeVas].
     *
     * Cuelga de `episodes` y NO de `playback`, con el progreso en LEFT JOIN, porque el capítulo
     * elegido puede ser uno que nunca tocaste (el siguiente al que terminaste): ahí no hay fila de
     * `playback` y la tarjeta va con la barra en cero.
     *
     * `lastPlayedAt` sale en 0 en ese caso; el repositorio lo pisa con el del ancla, que es lo que
     * ordena la fila (ver `observeContinueWatching`).
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
    suspend fun filasParaContinuar(episodeIds: List<String>): List<ContinueRow>

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

    /** Lo último que se reprodujo, con su ítem, del más reciente al más viejo. Para "Para ti". */
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
        LIMIT :tope
        """
    )
    suspend fun historialReciente(tope: Int): List<FilaDeHistorial>
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
    /**
     * De dónde salió el capítulo (`episodes.torrentData`): la URL de la página para web, los datos
     * del torrent para torrent, null para archive.org. Lo usa el buscador de fuentes, donde una fila
     * es UNA FUENTE y todavía no sabe qué `episodeId` le va a tocar. Ver `DescargasPorFuente`.
     */
    val sourceRef: String? = null,
)

@Dao
interface SkipMarkerDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(marker: SkipMarkerEntity)

    /** El marcador de TODA la serie (el que se pone a mano en el diálogo): `episodeId` vacío. */
    @Query("SELECT * FROM skip_markers WHERE itemId = :itemId AND episodeId = ''")
    suspend fun get(itemId: String): SkipMarkerEntity?

    @Query("SELECT * FROM skip_markers WHERE itemId = :itemId AND episodeId = ''")
    fun observe(itemId: String): Flow<SkipMarkerEntity?>

    /** El del capítulo y el de la serie, en una sola consulta. `MarcadorDeCapitulo.elegir` decide cuál manda. */
    @Query("SELECT * FROM skip_markers WHERE itemId = :itemId AND episodeId IN (:episodeId, '') AND deleted = 0")
    fun observeDeCapitulo(itemId: String, episodeId: String): Flow<List<SkipMarkerEntity>>

    @Query("SELECT * FROM skip_markers WHERE itemId = :itemId AND episodeId IN (:episodeId, '') AND deleted = 0")
    suspend fun getDeCapitulo(itemId: String, episodeId: String): List<SkipMarkerEntity>

    /** Una fila por su propia llave (PK). La usa el sync por nube para el LWW puntual de un registro remoto. */
    @Query("SELECT * FROM skip_markers WHERE id = :id")
    suspend fun getById(id: String): SkipMarkerEntity?

    /** Borra el marcador puesto a mano de la SERIE (`episodeId` vacío); los de capítulo no se tocan. */
    @Query("DELETE FROM skip_markers WHERE itemId = :itemId AND episodeId = ''")
    suspend fun delete(itemId: String)

    @Query("SELECT * FROM skip_markers")
    suspend fun getAll(): List<SkipMarkerEntity>

    // --- Sync en la nube (Plan 4) ---
    @Query("SELECT * FROM skip_markers WHERE updatedAt > :cursor")
    suspend fun getMarkersSince(cursor: Long): List<SkipMarkerEntity>

    /** Borra TODOS los marcadores del ítem (serie + cada capítulo): lo que hace hoy al quitar un ítem. */
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

    /**
     * Purga única del 2026-08-14: canales de adultos que quedaron anotados ANTES de que
     * `abrirCanalActual` dejara de anotarlos. Aparecían en la fila "Canales en vivo" del inicio,
     * a la vista de cualquiera.
     *
     * Se borra TODO y no solo los de adultos porque el aparato no tiene forma de saber cuáles lo
     * eran: los recientes guardan código y nombre, no la categoría. Y no cuesta nada — la nube ya
     * quedó limpia, así que el próximo sync repuebla la lista con los legítimos.
     */
    @Query("DELETE FROM live_recents")
    suspend fun borrarTodos()
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
     * Igual que [get] pero SIN el filtro `deleted = 0`: hace falta en los dos únicos lugares que
     * necesitan VER un tombstone en vez de tratarlo como fila inexistente:
     * - `CloudSyncManager.mergeFrame`, para el LWW: si usara [get], un tombstone local (creado por
     *   `DestructorDeFrames.destruir`) se vería como fila INEXISTENTE, el LWW compararía el
     *   `updatedAt` remoto contra 0, el remoto ganaría siempre, y un frame que este dispositivo
     *   borró resucitaría en el siguiente sync.
     * - `DestructorDeFrames.destruir`, para ser idempotente: necesita saber si la fila YA es
     *   tombstone (y no reescribirla) o si recién ahora pasa de viva a borrada.
     *
     * No la uses para otra cosa: el resto de los callers SÍ quiere que una fila borrada cuente
     * como "no hay frame".
     */
    @Query("SELECT * FROM episode_frame WHERE episodeId = :episodeId")
    suspend fun getIncluyendoBorradas(episodeId: String): EpisodeFrameEntity?

    /** Filas cambiadas después del cursor, para el push. Espeja a `getPlaybackSince`. */
    @Query("SELECT * FROM episode_frame WHERE updatedAt > :cursor ORDER BY updatedAt ASC")
    suspend fun getFramesSince(cursor: Long): List<EpisodeFrameEntity>

    /**
     * Filas que vinieron de otro dispositivo, cuyo JPEG todavía no está en disco y que ADEMÁS se
     * están por pintar ([episodeIds]).
     *
     * El filtro por capítulo no es una optimización cosmética: el diseño dice que los bytes se bajan
     * recién cuando hay que pintar esa tarjeta. Sin él, abrir el home en un aparato desincronizado
     * bajaba la cola ENTERA de la cuenta (~97 descargas) para pintar 6.
     */
    @Query("SELECT * FROM episode_frame WHERE deleted = 0 AND remoteUrl IS NOT NULL AND episodeId IN (:episodeIds)")
    suspend fun pendientesDeBajar(episodeIds: Collection<String>): List<EpisodeFrameEntity>

    /**
     * Saca la fila de [pendientesDeBajar] tras publicar su JPEG, pero SOLO si sigue siendo la misma
     * fila que se leyó (mismo `updatedAt`) y sigue viva.
     *
     * Es un UPDATE condicional y no un `upsert` de la copia leída porque entre la lectura de la cola
     * y esta escritura puede haber corrido `DestructorDeFrames.destruir` (el capítulo pasó el 60%, o
     * llegó el `watched` del otro aparato): reescribir la copia vieja pisaría el tombstone con
     * `deleted = 0` y un `updatedAt` MÁS VIEJO que el del borrado — una fila resucitada que además
     * no se autocorrige, porque el cursor de push ya pasó ese `updatedAt` y nunca se vuelve a
     * empujar. Devuelve cuántas filas tocó: 0 significa "la fila cambió abajo mío" y quien llama
     * tiene que deshacer lo que escribió en disco (ver `BajadorDeFrames`).
     */
    @Query("UPDATE episode_frame SET remoteUrl = NULL WHERE episodeId = :episodeId AND updatedAt = :updatedAt AND deleted = 0")
    suspend fun marcarBajado(episodeId: String, updatedAt: Long): Int

    /**
     * Filas (sin borrar) de los capítulos de un ítem, para el detalle de una serie. Misma forma
     * que [EpisodeStillDao.observeForItem]: el repositorio la usa solo como DISPARADOR del Flow
     * (ver `ArkivRepository.observeEpisodeFrames`), no como fuente de la ruta.
     */
    @Query("SELECT * FROM episode_frame WHERE deleted = 0 AND episodeId IN (SELECT id FROM episodes WHERE itemId = :itemId)")
    fun observeForItem(itemId: String): Flow<List<EpisodeFrameEntity>>

    /**
     * TODAS las filas vivas, como DISPARADOR del Flow de "Continuar viendo".
     *
     * Existe por un agujero del home: `PlaybackDao.observeContinueWatching` toca `playback`,
     * `episodes`, `items` y `episode_still`, pero NO `episode_frame`. Como Room invalida por tabla,
     * el frame que baja `BajadorDeFrames` (archivo + fila de `episode_frame`) no le notificaba nada
     * a esa consulta: la tarjeta se quedaba con el still de TMDB hasta que se tocara otra cosa. Y
     * peor, tampoco había con qué disparar la bajada en el caso real (el push manda `progress` ANTES
     * que `episode_frames`, así que cuando llega la fila del frame ya no hay más escrituras de
     * `playback` que reemitan nada).
     *
     * Devuelve la lista entera y no un `COUNT`: da igual el contenido —el repositorio la usa solo
     * como señal de "algo cambió en `episode_frame`"— pero una consulta de filas es la misma forma
     * que [observeForItem] y no esconde el costo real.
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
     * Por `id` de PocketBase (la clave local, ver [RecomendacionEntity]), para el LWW del merge en
     * [com.arkiv.player.cloudsync.CloudSyncManager]. Sin filtro de `deleted`: la app nunca la borra
     * localmente por su cuenta (colección de solo lectura), así que no hay tombstone LOCAL que este
     * `get` pueda esconder -- a diferencia de [EpisodeFrameDao.getIncluyendoBorradas].
     */
    @Query("SELECT * FROM recomendaciones WHERE id = :id")
    suspend fun get(id: String): RecomendacionEntity?

    /**
     * Las recomendaciones vigentes de la cuenta (la app solo tiene una cuenta local a la vez), en el
     * orden que decidió el gateway, sin lo que ya se marcó como tombstone. Es la fuente de la fila
     * "Para ti" del inicio.
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
