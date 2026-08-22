package com.arkiv.player.data.gateway

import org.json.JSONObject

/** Un resultado de búsqueda tal como lo entrega el gateway. */
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
 * Lo reproducible que devuelve `/v1/resolve`.
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
    /** Pistas que la fuente entrega junto al stream. Magis las trae del portal y el resolver web
     *  las sniffea de la página: descartarlas obligaría a buscarlas de nuevo en OpenSubtitles. */
    val subtitles: List<GatewaySubtitle> = emptyList(),
    /**
     * Duración real en ms cuando la fuente la sabe (0 = no la sabe).
     *
     * Existe por el MPEG-TS crudo de magis: no la lleva en ninguna cabecera y libVLC tampoco la
     * deduce sobre HTTP, así que sin este dato hay que bajar las dos puntas del archivo para leer
     * sus PCR ([com.arkiv.player.playback.TsDurationProbe]) contra un CDN que tarda entre 0,2 s y
     * 20 s en contestar un rango. Cuando esa sonda pierde, la película queda con la barra llena,
     * 00:00 a la derecha y sin poder adelantar. El portal ya sabe cuánto dura: esto lo trae.
     */
    val durationMs: Long = 0L,
    /** Códec de video que reporta la fuente ("h264", "h265"…); "" si no se sabe. */
    val videoCodec: String = "",
    /**
     * Contenedor tal como lo nombra la FUENTE ("ts", "mp4"…); "" si no se sabe.
     *
     * Es el dato con el que la app le declara el contenedor al demuxer antes de abrir. Antes se
     * deducía de la extensión de [url], que para magis no es un dato de la fuente sino algo que
     * arma el gateway colapsando a `.mp4` todo lo que el portal no llame `ts`. Ver
     * [com.arkiv.player.playback.formatoAvformatDe]. "" = sondear, nunca suponer.
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
 * Un capítulo de una temporada de Magis.
 *
 * [still], [tmdbTitle] y [overview] los agrega el gateway cruzando el id de IMDb que publica el
 * portal contra TMDB: el portal NO tiene imagen ni nombre real por capítulo (su `posterList` por
 * capítulo llega siempre vacío). Son opcionales a propósito — si TMDB no resolvió, el capítulo se
 * muestra con [title], que es el del portal.
 */
data class GatewayEpisode(
    val number: Int,
    val title: String,
    val ref: String,
    val still: String? = null,
    val tmdbTitle: String? = null,
    val overview: String? = null,
)

/**
 * La serie a la que pertenece una temporada, cuando el gateway la pudo identificar.
 *
 * [titulo] es el nombre con el que TMDB la conoce ("Neon Genesis Evangelion"), no el del portal
 * ("Shin seiki evangerion Temp.1"): es lo que la biblioteca adopta como `tituloCanonico`. Viene
 * **vacío** cuando el gateway es viejo o TMDB no resolvió — no null, para que "no hay nombre" sea
 * una sola pregunta y no dos.
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
 * Parsea el JSON crudo de `/v1/episodes`: la lista de capítulos y, si el gateway pudo cruzar el
 * imdb_id del portal contra TMDB, el bloque [GatewaySerie]. Los campos nuevos de [GatewayEpisode]
 * ([GatewayEpisode.still], [GatewayEpisode.tmdbTitle] y [GatewayEpisode.overview]) son opcionales:
 * ausentes o vacíos quedan en null, nunca rompen el parseo — así un gateway viejo, o uno al que
 * TMDB le falló para esa temporada, sigue funcionando igual que antes.
 */
fun parseEpisodesResponse(json: String): Pair<List<GatewayEpisode>, GatewaySerie?> {
    val o = JSONObject(json)
    val arr = o.optJSONArray("episodes")
    val episodios = (0 until (arr?.length() ?: 0)).mapNotNull { i ->
        arr!!.optJSONObject(i)?.let { e ->
            val ref = e.optString("ref")
            if (ref.isBlank()) null
            else GatewayEpisode(
                number = e.optInt("number"),
                title = e.optString("title"),
                ref = ref,
                still = e.optString("still").takeIf { it.isNotBlank() },
                tmdbTitle = e.optString("tmdb_title").takeIf { it.isNotBlank() },
                overview = e.optString("overview").takeIf { it.isNotBlank() },
            )
        }
    }
    val serie = o.optJSONObject("series")?.let { s ->
        GatewaySerie(
            imdbId = s.optString("imdb_id"),
            tmdbId = s.optInt("tmdb_id"),
            seasonNumber = s.optInt("season_number"),
            titulo = s.optString("title"),
            posterUrl = s.optString("poster_url"),
            backdropUrl = s.optString("backdrop_url"),
        )
    }
    return episodios to serie
}

/** Respuesta completa de `/v1/ditu/catalog`. */
data class DituCatalogResponse(
    val series: List<DituSerieItem>,
    /** true cuando `isAllVodPremiumActive` está activo en el portal Ditu. */
    val premiumRequired: Boolean,
)

/** Una serie del catálogo de Caracol Streaming (Ditu). Viene de `/v1/ditu/catalog`. */
data class DituSerieItem(
    val contentId: String,
    val title: String,
    val posterUrl: String,
    val ref: String,
    /** true para GROUP_OF_BUNDLES (franquicias con varias temporadas). */
    val isGroup: Boolean = false,
    /** true para películas VOD (se reproducen directo, sin dialog de episodios). */
    val isMovie: Boolean = false,
    /** tagValue del extendedMetadata de Ditu ("Telenovela", "Deportes", "Periodístico", …). */
    val tag: String = "",
) {
    /** Convierte el ítem del catálogo en un GatewayResult compatible con MagisSeasonDialog. */
    fun toGatewayResult() = GatewayResult(
        source = "ditu",
        title = title,
        ref = ref,
        kind = "series",
        extra = mapOf("content_id" to contentId, "poster" to posterUrl),
    )
}

sealed interface SearchEvent {
    data class SourceStart(val source: String) : SearchEvent
    data class ResultEvent(val source: String, val item: GatewayResult) : SearchEvent
    data class SourceDone(val source: String, val count: Int, val ms: Long) : SearchEvent
    data class SourceError(val source: String, val error: String, val ms: Long, val count: Int) : SearchEvent
    data class Done(val ms: Long) : SearchEvent

    /**
     * Evento que esta versión de la app no conoce, o línea corrupta. Se ignora: el
     * servidor puede sumar eventos nuevos sin romper APKs viejos.
     */
    data class Unknown(val raw: String) : SearchEvent
}

private fun JSONObject.mapaDeStrings(clave: String): Map<String, String> {
    val obj = optJSONObject(clave) ?: return emptyMap()
    return obj.keys().asSequence().associateWith { obj.opt(it)?.toString().orEmpty() }
}

private fun resultDe(obj: JSONObject) = GatewayResult(
    source = obj.optString("source"),
    title = obj.optString("title"),
    ref = obj.optString("ref"),
    kind = obj.optString("kind", "movie"),
    lang = obj.optString("lang"),
    quality = obj.optString("quality"),
    sizeBytes = obj.optLong("size_bytes"),
    seeders = obj.optInt("seeders"),
    year = obj.optString("year"),
    season = obj.optInt("season"),
    episode = obj.optInt("episode"),
    extra = obj.mapaDeStrings("extra"),
)

/** Convierte una línea del stream NDJSON en un evento. Nunca lanza. */
fun parseSearchEvent(linea: String): SearchEvent = runCatching {
    val o = JSONObject(linea)
    when (o.optString("type")) {
        "source_start" -> SearchEvent.SourceStart(o.optString("source"))
        "result" -> SearchEvent.ResultEvent(o.optString("source"), resultDe(o.getJSONObject("item")))
        "source_done" -> SearchEvent.SourceDone(
            o.optString("source"), o.optInt("count"), o.optLong("ms"),
        )
        "source_error" -> SearchEvent.SourceError(
            o.optString("source"), o.optString("error"), o.optLong("ms"), o.optInt("count"),
        )
        "done" -> SearchEvent.Done(o.optLong("ms"))
        else -> SearchEvent.Unknown(linea)
    }
}.getOrElse { SearchEvent.Unknown(linea) }

/** Tipos de `program_type` cuyo resultado es una temporada entera, no algo reproducible. */
val MAGIS_SERIES = setOf("teleplay", "series", "variety")

/** Metadata de anime que sirve el gateway (`/v1/anime/{id}`). */
data class GatewayAnimeMeta(
    val titles: List<String>,
    val tvdbSeason: Int?,
    val offset: Int,
    val tmdbId: Int?,
)

/** Tiempos de intro/outro de un capítulo, en ms. Null en los que el gateway no supo. */
data class GatewayMarcadores(
    val openingStartMs: Long?,
    val openingEndMs: Long?,
    val endingStartMs: Long?,
)

/**
 * `{}` -> null: el gateway contesta un objeto vacío cuando no sabe, y eso no es un error sino
 * "este capítulo no tiene marcadores". Un JSON roto también da null: esto cuelga de una
 * reproducción y no puede tirar el player.
 */
fun parseMarcadores(json: String): GatewayMarcadores? = runCatching {
    val o = JSONObject(json)
    val m = GatewayMarcadores(
        openingStartMs = if (o.has("openingStartMs")) o.getLong("openingStartMs") else null,
        openingEndMs = if (o.has("openingEndMs")) o.getLong("openingEndMs") else null,
        endingStartMs = if (o.has("endingStartMs")) o.getLong("endingStartMs") else null,
    )
    if (m.openingEndMs == null && m.endingStartMs == null) null else m
}.getOrNull()
