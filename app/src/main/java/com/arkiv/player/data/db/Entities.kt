package com.arkiv.player.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.arkiv.player.data.MarcadorDeCapitulo

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
     * How many episodes this series had the last time its detail was opened. It's the base of
     * the "new chapters" badge: the difference against the current count is what appeared since
     * then. See [com.arkiv.player.data.nuevos.ContadorDeNuevos] for why it's counted this way and
     * not by date.
     *
     * `null` = never opened since this counter exists, and does NOT paint a badge.
     */
    val episodiosVistosEnLista: Int? = null,
    /**
     * Serie de TMDB a la que corresponde este ítem, cuando se sabe. Se guarda al agregarlo desde
     * la búsqueda; sin esto el vínculo se pierde y la pantalla de detalle no tiene a quién pedirle
     * los títulos de los capítulos. Null para ítems agregados a mano por identificador/URL.
     */
    val tmdbId: Int? = null,
    /**
     * El nombre con el que TMDB conoce esta obra, cuando se pudo identificar. Es lo que la
     * biblioteca MUESTRA; [title] queda con lo que dijo la fuente.
     *
     * Al lado y no encima: [title] guarda el nombre del portal ("Shin seiki evangerion Temp.1") o
     * el renombre manual de la persona, y los dos se perderían si el canónico los pisara — el día
     * que TMDB se equivoque no habría con qué volver atrás, y un renombre manual no podría ganarle
     * a la identificación automática. Renombrar a mano lo pone en null, para que lo que escribió
     * la persona sea lo que se vea.
     *
     * Null = no se identificó (o no se preguntó todavía). Ver `MagisEntities.buildSeason`.
     */
    val tituloCanonico: String? = null,
    /**
     * "movie" | "tv" (mismo vocabulario que [com.arkiv.player.data.catalog.TmdbItem.type]), cuando
     * se sabe con certeza al agregar. Distinto de [categoryOverride] -que es un override MANUAL y
     * usa "series", no "tv"-: esto es el tipo que trajo la fuente, no una corrección de la persona.
     *
     * Lets the library tell with certainty whether it already has something (see
     * [com.arkiv.player.data.model.WorkKind]) instead of comparing by title, which is fuzzy.
     * Null when the source doesn't know it: better a gap than a made-up type.
     */
    val tipo: String? = null,
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
     * Season and chapter, set by the source when it builds the episode (see
     * `MagisEntities`/`DituEntities`). With this and the item's `tmdbId`, TMDB can be asked for
     * the chapter's real title: neither Magis nor Caracol return the episode name, only its
     * number. Null when the source doesn't provide them, and in rows saved before v16.
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

/**
 * Tiempos de intro/outro. La llave es derivada (`"<itemId>|<episodeId>"`, ver
 * [com.arkiv.player.data.MarcadorDeCapitulo.idDe]) porque el sync empuja cada colección por UN
 * campo natural y una clave compuesta rompería ese mecanismo.
 *
 * [episodeId] vacío = vale para toda la serie: es el marcador que se pone a mano en el diálogo.
 *
 * [origen] distingue lo puesto A MANO de lo que trajo AniSkip solo: ver
 * [com.arkiv.player.data.MarcadorDeCapitulo.elegir]. El default es MANUAL a propósito -- lo que ya
 * existe y lo que escriba una persona vale como manual sin tener que acordarse de ponerlo.
 */
@Entity(tableName = "skip_markers")
data class SkipMarkerEntity(
    @PrimaryKey val id: String,
    val itemId: String,
    val episodeId: String = "",
    val openingStartMs: Long?,
    val openingEndMs: Long?,
    val endingStartMs: Long?,
    val updatedAt: Long = 0,
    val deleted: Boolean = false,
    val origen: String = MarcadorDeCapitulo.ORIGEN_MANUAL,
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
 * A download to the device's OWN storage. `source` records where it came from: `"magis"` for
 * today's Magis downloads, `"archive"` as the fallback this branch has always written for
 * sources without a real download strategy (see `FuenteDeDescarga.para`), and legacy `"torrent"`/
 * `"web"` values left in rows saved before this branch's pruning.
 *
 * `variant` is still NOT NULL (and today it's always `""`) because SQLite can't change a column's
 * nullability with ALTER TABLE, and rebuilding the table isn't worth it for a field that only
 * archive.org's removed "original"/"derivative" quality ever wrote to.
 */
@Entity(tableName = "downloads")
data class DownloadEntity(
    @PrimaryKey val episodeId: String,
    val variant: String,              // always "" today; only archive.org ever filled it, and that source is gone
    val state: String,                // ver LocalDownloadState
    val progress: Float,              // 0..1
    val localUri: String?,            // histórico: file:// que dejó el DownloadManager del sistema
    val bytes: Long,                  // tamaño total conocido (0 si aún no se sabe)
    val source: String = "archive",   // "magis" | "ditu" | "archive" (fallback) | legacy "torrent"/"web"
    val filePath: String? = null,     // ruta absoluta del archivo final
    val bytesDone: Long = 0,
    // Huérfana desde la poda de NUC (Task 8): nada la lee ni la escribe más (era el puente
    // web->NUC, borrado en esa misma tarea). Se deja el campo -y la columna física- tal cual,
    // sin migración que la elimine: en SQLite eso exige recrear toda la tabla `downloads` (que sí
    // tiene datos reales de usuario), a diferencia de las tres tablas 100% huérfanas que si se
    // dropearon en MIGRATION_28_29. Ver el reporte de la ronda de fix de Task 8 (follow-up
    // disclosed, no intentado por ser más riesgoso que el drop de las tres tablas).
    val stagingItemId: Long? = null,
    val error: String? = null,
    val createdAt: Long = 0,
    val sizeConfirmed: Boolean = false, // el usuario ya aceptó la compuerta de tamaño
)

/**
 * A live TV channel marked as a favorite. `deleted` is the live un-favorite mechanism: unfavoriting
 * sets it (`LiveFavoriteDao.borrar`), and every read filters on it (`flowTodos`, `esFavorito`).
 * `updatedAt` is left over from this branch's two removed cloud-sync paths (see
 * [com.arkiv.player.data.db.SyncTriggers]) and has no reader today.
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
    /**
     * Sinopsis del capítulo según TMDB. Vive acá y no en `episodes` por lo mismo que [stillUrl] y
     * [title]: es dato derivable y esa tabla tiene triggers de sync. Null = no se pudo resolver.
     */
    val overview: String? = null,
)

/**
 * El frame capturado de un capítulo. El JPEG NO está acá: vive en `filesDir/frames/` (ver
 * [com.arkiv.player.thumbnails.FrameStore]) y esta fila es el índice.
 *
 * `updatedAt` y `deleted` existen desde el día uno aunque la fase 1 no sincronice: son el reloj y
 * el tombstone que va a usar la fase 2, y agregarlos después obligaría a otra migración.
 */
@Entity(tableName = "episode_frame")
data class EpisodeFrameEntity(
    @PrimaryKey val episodeId: String,
    /** De qué punto del capítulo es el frame. */
    val positionMs: Long,
    val capturedAt: Long,
    val updatedAt: Long = 0,
    val deleted: Int = 0,
    /**
     * URL of the file that the now-removed cloud upload filled in when a row was adopted from
     * another device and the JPEG hadn't been downloaded yet. Null = the frame is local (captured
     * here) or already downloaded. Unused today: nothing writes a non-null value anymore, now that
     * the cloud sync is gone.
     */
    val remoteUrl: String? = null,
    /**
     * 1 = this row was adopted from ANOTHER device (written by `CloudSyncManager.mergeFrame`, in
     * this branch's now-removed cloud sync); 0 = it was born here (captured by
     * [com.arkiv.player.thumbnails.FrameCapturer] or sealed by the destructor).
     *
     * Exists so that whoever RECEIVES a frame doesn't upload it again. An adopted row kept the
     * `updatedAt` of the other device, which was past this device's push cursor, so the next pass
     * would re-push it; and since the JPEG was already on disk by then, the push would re-upload
     * the SAME BYTES. That made each frame cross the network twice, changed the file's name on the
     * server without changing `updatedAt` (leaving a third device with a `remoteUrl` that 404s
     * forever), and, if the echo arrived after a fresh capture of the original, made the record
     * REGRESS to the old frame.
     *
     * It's a LOCAL field that never traveled to PocketBase. Remembering it in memory wasn't
     * enough -the later push could land in a different process run- nor was comparing against the
     * last adopted `updatedAt`: with another device's clock running ahead, a legitimate local
     * capture would fall below that mark and its bytes would never be uploaded.
     *
     * This whole mechanism is dormant in this branch: there's no cloud sync to adopt a row from,
     * so `origenRemoto` is always 0 today. It stays until the Phase 3 column audit.
     */
    val origenRemoto: Int = 0,
)

/**
 * Una recomendación generada EN EL APARATO por
 * [com.arkiv.player.data.recomendaciones.GeneradorParaTi], con los modelos gratis de Kilo, a partir
 * del historial local, para la fila "Para ti" del inicio. La app SÍ escribe acá directamente
 * (`RecomendacionDao.reemplazar`, llamado desde `AppGraph.generadorParaTi`): no hay PocketBase ni
 * sync detrás -- `CloudSyncManager` no existe en esta rama.
 *
 * La clave local es [id] (el id de la fuente ya resuelta, ver
 * `com.arkiv.player.data.recomendaciones.GuardadoDeRecomendacion.itemIdDe`) y **NO** [orden]: cada
 * generación RECREA la lista entera en vez de reusar identidad entre tandas (port de
 * `arkiv-api/src/arkiv_api/recomendaciones/almacen.py::guardar`) -- `RecomendacionDao.reemplazar`
 * entierra (`deleted=true`) las vigentes con el MISMO `updatedAt` y recién después inserta las
 * nuevas. Dos generaciones distintas pueden compartir el mismo `orden` (0..9) con `id`s distintos;
 * si `orden` fuera la PK, el `upsert` (`OnConflictStrategy.REPLACE`) de la fila nueva pisaría la
 * fila vieja que tuviera ese mismo `orden` aunque fueran obras completamente distintas.
 */
@Entity(tableName = "recomendaciones")
data class RecomendacionEntity(
    @PrimaryKey val id: String,
    /** Puede venir en 0 (candidato sin `tmdbId` confirmado): es un valor legítimo, no una ausencia. */
    val tmdbId: Int,
    /** "movie" | "tv". */
    val tipo: String,
    val titulo: String,
    /** Puede venir vacío, igual que [tmdbId]. */
    val posterUrl: String,
    /** La frase que explica por qué se recomienda (p. ej. "porque terminaste Dragon Ball"). */
    val porque: String,
    /**
     * La fuente ya resuelta para reproducir, armada en el aparato por la cascada de verificación
     * (ver [com.arkiv.player.data.recomendaciones.VerificacionParaTi]).
     */
    val ref: String,
    /** Posición 0..9 para ordenar la fila. NO es identidad -- ver el KDoc de la clase. */
    val orden: Int,
    val generadoAt: Long,
    /** Sync: reloj de última modificación (LWW) y tombstone de borrado. */
    val updatedAt: Long = 0,
    val deleted: Boolean = false,
)
