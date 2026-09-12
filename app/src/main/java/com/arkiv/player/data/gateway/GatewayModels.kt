package com.arkiv.player.data.gateway

class GatewayException(mensaje: String, causa: Throwable? = null) : RuntimeException(mensaje, causa)

/**
 * Qué se está buscando. Quedaron solo los campos que la fuente de verdad usa: el `year`, el
 * `anilistId`, el `lang`, el `sources`, el `maxBytes` y el `budgetMs` eran parámetros del gateway
 * -filtrar por idioma, elegir fuentes, acotar torrents, cortar por tiempo-, y el portal de Magis no
 * recibe nada de eso. Dejarlos era prometer un filtro que nadie aplica.
 *
 * [tmdbId] sí se usa, y no para filtrar: de ahí sale el título ORIGINAL con el que se rankea lo que
 * devuelve el portal (ver `MagisFuente.formasDelTitulo`).
 */
data class GatewaySearchQuery(
    val q: String,
    val type: String = "movie",
    val season: Int = 0,
    val episode: Int = 0,
    val tmdbId: Int = 0,
)

/** Un resultado de búsqueda, armado por la fuente (hoy `MagisFuente`) contra lo que devuelve el portal. */
data class GatewayResult(
    val source: String,
    val title: String,
    val ref: String,
    val kind: String = "movie",
    val lang: String = "",
    val quality: String = "",
    val sizeBytes: Long = 0,
    val seeders: Int = 0,
    val year: String = "",
    val season: Int = 0,
    val episode: Int = 0,
    val extra: Map<String, String> = emptyMap(),
)

/**
 * Lo reproducible que devuelve la resolución de Magis (`MagisResolve`/`MagisLive`).
 *
 * [headers] es genérico a propósito: cubre el Referer/User-Agent de las fuentes web
 * y el Content-Auth/Content-License de magis sin necesitar un campo por fuente.
 */
data class GatewayPlayable(
    val kind: String,
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val mime: String = "",
    val expiresAt: String = "",
    val fallbackUrl: String? = null,
    /** Pistas que trae el stream. Hoy la única fuente que puebla este campo es Magis, que las
     *  manda junto con la resolución del play (`MagisResolve.subtitulos`, ver `MagisFuente`). */
    val subtitles: List<GatewaySubtitle> = emptyList(),
    /**
     * Real duration in ms when the source knows it (0 = it doesn't).
     *
     * Exists because of magis's raw MPEG-TS: it doesn't carry it in any header and libVLC
     * couldn't deduce it over HTTP either, so without this value both ends of the file have to be
     * downloaded to read its PCR ([com.arkiv.player.playback.TsDurationProbe]) against a CDN that
     * takes between 0.2s and 20s to answer a range. When that probe loses, the movie ends up with
     * a full progress bar, 00:00 on the right, and no way to seek forward. The portal already
     * knows how long it runs: this carries that value.
     */
    val durationMs: Long = 0L,
    /** Códec de video que reporta la fuente ("h264", "h265"…); "" si no se sabe. */
    val videoCodec: String = "",
    /**
     * Contenedor tal como lo nombra la FUENTE ("ts", "mp4"…); "" si no se sabe.
     *
     * Es el dato con el que la app le declara el contenedor al demuxer antes de abrir. Antes se
     * deducía de la extensión de [url], que para magis no es un dato de la fuente sino algo que
     * arma `MagisResolve` colapsando a `.mp4` todo lo que el portal no llame `ts`. "" = sondear,
     * nunca suponer.
     */
    val container: String = "",
    /** URL del servidor de licencias Widevine; "" = sin DRM (reproducir directo).
     *  Existe por Ditu (Caracol Streaming): su stream es MPEG-DASH con Widevine y ExoPlayer
     *  la negocia automáticamente vía MediaItem.DrmConfiguration. */
    val drmLicenseUrl: String = "",
    /** Headers adicionales para la petición de licencia DRM (p.ej. Cookie: playback_token=…). */
    val drmLicenseHeaders: Map<String, String> = emptyMap(),
)

data class GatewaySubtitle(val lang: String, val url: String, val format: String = "")

/**
 * Un capítulo de una temporada (de Magis o de Caracol).
 *
 * [still], [tmdbTitle] y [overview] los agrega `MagisFuente`, en el propio cliente, cruzando el
 * id de IMDb que publica el portal contra TMDB: el portal NO tiene imagen ni nombre real por
 * capítulo (su `posterList` por capítulo llega siempre vacío). Son opcionales a propósito — si
 * TMDB no resolvió, el capítulo se muestra con [title], que es el del portal.
 */
data class GatewayEpisode(
    val number: Int,
    val title: String,
    val ref: String,
    val still: String? = null,
    val tmdbTitle: String? = null,
    val overview: String? = null,
    /**
     * Temporada de ESTE capítulo, cuando la fuente la sabe por capítulo; null = no la dice. Magis
     * no la manda: cada temporada suya es un resultado aparte, y su número viaja en
     * [GatewaySerie.seasonNumber].
     *
     * Existe por Caracol: un `GROUP_OF_BUNDLES` llega como UNA lista con todas sus temporadas
     * aplanadas (`DituEpisodios`), y cada temporada puede traer su propio capítulo 1. Sin la
     * temporada al lado, [number] no alcanza para saber cuál es cuál.
     */
    val season: Int? = null,
)

/**
 * La serie a la que pertenece una temporada, cuando `MagisFuente` la pudo identificar (ver su
 * KDoc: viaja siempre que el portal haya dado un imdb, así el enriquecimiento no haya salido).
 *
 * [titulo] es el nombre con el que TMDB la conoce ("Neon Genesis Evangelion"), no el del portal
 * ("Shin seiki evangerion Temp.1"): es lo que la biblioteca adopta como `tituloCanonico`. Viene
 * **vacío** cuando TMDB no resolvió — no null, para que "no hay nombre" sea una sola pregunta y
 * no dos.
 */
data class GatewaySerie(
    val imdbId: String,
    val tmdbId: Int,
    val seasonNumber: Int,
    val titulo: String = "",
    val posterUrl: String = "",
    val backdropUrl: String = "",
)

/**
 * Lo que la fuente va emitiendo mientras busca. Se conserva el formato de eventos -en vez de
 * devolver una lista- porque la pantalla pinta resultados a medida que llegan, y porque así el
 * arranque/fin/error de la fuente son estados explícitos y no ausencias.
 *
 * Antes estos eventos venían como NDJSON del gateway y los armaba `parseSearchEvent`; ahora los
 * emite `MagisFuente` directo, así que ese parser se fue (y con él el evento `Unknown`, que existía
 * para poder ignorar líneas de un servidor más nuevo).
 */
sealed interface SearchEvent {
    data class SourceStart(val source: String) : SearchEvent
    data class ResultEvent(val source: String, val item: GatewayResult) : SearchEvent
    data class SourceDone(val source: String, val count: Int, val ms: Long) : SearchEvent
    /**
     * [causa] es la excepción, cuando la fuente la tiene a mano: `FalloDeCaracol` la necesita para
     * decirle a la persona qué pasó. La manda `DituFuente`; `MagisFuente` y `FuenteCompuesta` no.
     */
    data class SourceError(
        val source: String,
        val error: String,
        val ms: Long,
        val count: Int,
        val causa: Throwable? = null,
    ) : SearchEvent
    data class Done(val ms: Long) : SearchEvent
}

/** Tipos de `program_type` cuyo resultado es una temporada entera, no algo reproducible. */
val MAGIS_SERIES = setOf("teleplay", "series", "variety")
