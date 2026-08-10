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
)

data class GatewaySubtitle(val lang: String, val url: String, val format: String = "")

/**
 * Un capítulo de una temporada de Magis.
 *
 * Un resultado de serie del portal es una TEMPORADA entera ("Breaking Bad T5" = 16 capítulos), así
 * que hay que pedir la lista aparte. Cada capítulo trae su propio [ref], resoluble sin volver a
 * buscar.
 */
data class GatewayEpisode(val number: Int, val title: String, val ref: String)

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
