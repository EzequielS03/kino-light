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
    /**
     * Cuántos episodios tenía esta serie la última vez que se abrió su detalle. Es la base del
     * badge de "hay capítulos nuevos": la diferencia contra el conteo de ahora es lo que apareció
     * desde entonces. Ver [com.arkiv.player.data.nuevos.ContadorDeNuevos] para por qué se cuenta
     * así y no con fechas (spoiler: `refreshItem` re-inserta TODOS los episodios).
     *
     * `null` = nunca se abrió desde que existe el contador, y NO pinta badge.
     */
    val episodiosVistosEnLista: Int? = null,
    /**
     * Serie de TMDB a la que corresponde este ítem, cuando se sabe. Se guarda al agregarlo desde
     * la búsqueda; sin esto el vínculo se pierde y la pantalla de detalle no tiene a quién pedirle
     * los títulos de los capítulos. Null para ítems agregados a mano por identificador/URL.
     */
    val tmdbId: Int? = null,
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
    /**
     * Temporada y capítulo deducidos del nombre del archivo (ver `MetadataParser.episodeNumberOf`).
     * Con esto y el `tmdbId` del ítem se le puede pedir a TMDB el título real del capítulo: el
     * nombre del episodio no está ni en archive.org ni en el mirror, solo su número.
     * Null cuando el nombre no declara numeración, y en las filas guardadas antes de la v16.
     */
    val season: Int? = null,
    val episode: Int? = null,
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

/**
 * Un título abierto desde el buscador, para poder volver a él sin buscarlo de nuevo.
 *
 * La PK es el id derivado que arma [com.arkiv.player.data.SearchHistoryPolicy.titleId]: encierra
 * la regla de identidad en un solo lugar y deja que REPLACE haga el dedupe.
 */
@Entity(tableName = "recent_titles")
data class RecentTitleEntity(
    @PrimaryKey val id: String,
    val kind: String,
    val tmdbId: Int?,
    val anilistId: Long?,
    val title: String,
    val posterUrl: String,
    val year: String,
    val atMs: Long,
)

/**
 * Una descarga al almacenamiento del PROPIO dispositivo. Sirve a las tres fuentes: `source`
 * distingue archive.org, torrent y web. NO confundir con [NucLibraryItemEntity], que es la caché de
 * lo que vive en la NUC.
 *
 * `variant` sigue siendo NOT NULL (y vale `""` para torrent y web) porque SQLite no puede cambiar la
 * nulabilidad de una columna con ALTER TABLE y reconstruir la tabla no se justifica por un campo que
 * solo usa archive.
 */
@Entity(tableName = "downloads")
data class DownloadEntity(
    @PrimaryKey val episodeId: String,
    val variant: String,              // archive: "original" | "derivative"; torrent/web: ""
    val state: String,                // ver LocalDownloadState
    val progress: Float,              // 0..1
    val localUri: String?,            // histórico: file:// que dejó el DownloadManager del sistema
    val bytes: Long,                  // tamaño total conocido (0 si aún no se sabe)
    val source: String = "archive",   // "archive" | "torrent" | "web"
    val filePath: String? = null,     // ruta absoluta del archivo final
    val bytesDone: Long = 0,
    val stagingItemId: Long? = null,  // web: item de la NUC mientras es paso intermedio
    val error: String? = null,
    val createdAt: Long = 0,
    val sizeConfirmed: Boolean = false, // el usuario ya aceptó la compuerta de tamaño
)

/**
 * Caché local de qué episodios ya están descargados en la NUC (arkiv-offline). Se alimenta de
 * GET /library -- ver ArkivOfflineApi -- tanto al abrir el detalle de una serie como por el canal
 * SSE+poll de la pantalla de Descargas. NO confundir con [DownloadEntity]: esa tabla es para
 * descargas al almacenamiento del propio dispositivo (archive.org vía DownloadManager); esta es
 * para contenido que vive en la NUC y se reproduce por streaming remoto.
 */
@Entity(tableName = "nuc_library_items")
data class NucLibraryItemEntity(
    @PrimaryKey val itemId: Long,       // id del item en arkiv-offline (job_items.id)
    val seriesId: String,
    val season: Int,
    val episode: Int,
    val status: String,                 // "done" (unico status que GET /library devuelve)
    val sizeBytes: Long,
    val syncedAt: Long,
    // pageUrl exacta desde la que se bajó este capítulo (job_items.source_ref en arkiv-offline).
    // Nullable: filas viejas de la caché (y de la NUC) pueden no tenerla. Ver [NucLibraryEntry].
    val sourceRef: String? = null,
)

/**
 * Preferencia de reproducción por serie: NUC (streamear desde arkiv-offline cuando el episodio
 * puntual esté descargado) o LIVE (siempre en vivo). [asked] distingue "todavia no se preguntó"
 * de "el usuario eligió LIVE explícitamente" -- ambos casos empiezan sin fila, así que sin este
 * flag no se podría diferenciar "preguntar" de "ya preguntado y dijo que no".
 */
@Entity(tableName = "series_playback_prefs")
data class SeriesPlaybackPrefEntity(
    @PrimaryKey val seriesId: String,
    val preference: String,             // "NUC" | "LIVE"
    val asked: Boolean,
)

/**
 * Registro local de qué `job_id` de arkiv-offline disparó ESTE dispositivo (Task 9). arkiv-offline
 * no tiene un endpoint "listame todos los jobs" -- solo `GET /jobs/<id>` por id puntual -- así que
 * la app necesita su propio índice de qué ids consultar/observar en la pantalla de Descargas. Se
 * llena cuando `downloadPack`/`downloadEpisode` (Task 8, en AnimeShowDetailScreen/CineDetailScreen)
 * crean un job con éxito, y se limpia cuando ese job llega a un estado terminal (done/failed).
 */
@Entity(tableName = "local_active_jobs")
data class LocalActiveJobEntity(
    @PrimaryKey val jobId: Long,
    val createdAt: Long,
    /**
     * Serie del job (mismo id que en `nuc_library_items`). Se guarda acá porque `GET /jobs/<id>` no
     * lo devuelve: cuando un job llega a "done", la pantalla de Descargas lo necesita para pedirle a
     * la NUC la biblioteca de ESA serie y mostrar el capítulo recién terminado. Vacío en filas
     * creadas antes de la v14 (esos jobs no refrescan la biblioteca al terminar).
     */
    val seriesId: String = "",
)

/**
 * Canal de TV en vivo marcado como favorito. Sincroniza igual que `skip_markers`: LWW por
 * `updatedAt` + tombstone (`deleted`) -- ver [com.arkiv.player.data.db.SyncTriggers] y
 * [com.arkiv.player.sync.SyncMerge].
 */
@Entity(tableName = "live_favorites")
data class LiveFavoriteEntity(
    @PrimaryKey val code: String,
    val nombre: String,
    val numero: Int,
    val logo: String?,
    val updatedAt: Long = 0,
    val deleted: Boolean = false,
)

/** Últimos canales vistos. No lleva tombstone: se poda por antigüedad, no se borra a mano. */
@Entity(tableName = "live_recents")
data class LiveRecentEntity(
    @PrimaryKey val code: String,
    val nombre: String,
    val vistoAt: Long,
    val updatedAt: Long = 0,
)

/**
 * Caché local del catálogo de canales, para que la sección abra al instante y siga
 * mostrando la grilla aunque el gateway esté lento o caído. **No viaja por el sync**:
 * es caché reconstruible, no datos del usuario, y meterla al snapshot sería mandar
 * 1.000 filas entre dispositivos para nada.
 *
 * PK compuesta `(code, categoria)`, NO solo `code`: un mismo canal puede estar en varias
 * categorías del portal (p.ej. "Deportes" y "Todos"). Con PK por `code` solo, cachear la
 * categoría B reescribía (`REPLACE`) las filas de los canales que también están en A, dejándolas
 * con `categoria = B` -- y al volver a A desde caché (gateway caído), esos canales desaparecían
 * de la grilla (hallazgo F5 de la revisión final). Se autocuraba en cuanto el gateway volvía a
 * responder, pero la caché existe justo para cuando NO responde.
 */
@Entity(tableName = "live_channels_cache", primaryKeys = ["code", "categoria"])
data class LiveChannelCacheEntity(
    val code: String,
    val categoria: Int,
    val nombre: String,
    val numero: Int,
    val logo: String?,
    val guardadoAt: Long,
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
    /**
     * Título del capítulo según TMDB. Se cachea acá y no en `episodes` por lo mismo que
     * [stillUrl]: es dato derivable, y esa tabla tiene triggers de sync.
     * Null = ya se consultó y no había título (o la fila es anterior a la v16).
     */
    val title: String? = null,
)
