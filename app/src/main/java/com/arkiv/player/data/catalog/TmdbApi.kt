package com.arkiv.player.data.catalog

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.arkiv.player.BuildConfig
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

enum class TmdbCategory { POPULAR, TRENDING, TOP_RATED, NOW_PLAYING, UPCOMING }
data class TmdbGenre(val id: Int, val name: String)

/** Path relativo (sin auth) del endpoint de una categoría. Puro/testeable. */
fun tmdbCategoryPath(type: String, category: TmdbCategory): String = when (category) {
    TmdbCategory.TRENDING -> "/trending/$type/week"
    TmdbCategory.POPULAR -> "/$type/popular"
    TmdbCategory.TOP_RATED -> "/$type/top_rated"
    TmdbCategory.NOW_PLAYING -> if (type == "tv") "/tv/on_the_air" else "/movie/now_playing"
    TmdbCategory.UPCOMING -> if (type == "tv") "/tv/airing_today" else "/movie/upcoming"
}

/** Un título del catálogo (TMDB) — película o serie — con su título en español y el original. */
data class TmdbItem(
    val id: Int,
    val type: String,        // "movie" | "tv"
    val title: String,       // título en español (es-MX)
    val originalTitle: String,
    val posterUrl: String,
    val year: String,
    /** Imagen apaisada (16:9). En TV las filas se pintan con esta, no con el póster. */
    val backdropUrl: String = "",
    /** Sinopsis en español; TMDB la manda en la misma respuesta de la lista. Vacía si falta. */
    val overview: String = "",
) {
    val isSeries: Boolean get() = type == "tv"
}

/** Una temporada (metadata; los capítulos se cargan aparte por [TmdbApi.seasonEpisodes]). */
data class TmdbSeason(val seasonNumber: Int, val episodeCount: Int, val name: String)

/** Un capítulo de una temporada. */
data class TmdbEpisode(
    val season: Int,
    val episode: Int,
    val name: String,
    val overview: String,
    val air: String,
    val stillUrl: String,
)

/** Base de las imágenes de TMDB. Fuera de la clase para que [parseSeasonEpisodes] siga siendo puro. */
private const val TMDB_IMG = "https://image.tmdb.org/t/p"

// "null" se descarta igual que el vacío: `optString` de Android devuelve ese string cuando el JSON
// trae un null de verdad, y TMDB manda `poster_path: null` seguido. Sin esto se armaba la URL
// ".../w500null", que es una imagen rota en vez de la card sin póster.
private fun tmdbImgUrl(path: String?, size: String): String =
    if (path.isNullOrBlank() || path == "null") "" else "$TMDB_IMG/$size$path"

/**
 * Parsea la respuesta de `/tv/{id}/season/{n}`. Puro/testeable (sin red).
 *
 * **`null` no es lo mismo que la lista vacía**, y esa es toda la razón de que esta función exista
 * separada: `null` = la consulta no se pudo hacer (timeout, 429, 5xx, respuesta ilegible), lista
 * vacía = TMDB contestó y esa temporada no traía capítulos. Antes las dos cosas salían como
 * `emptyList()` y quedaban indistinguibles, así que `ArkivRepository.ensureEpisodeStills` marcaba
 * como "ya preguntado" lo que nunca se llegó a preguntar: una sola apertura del detalle sin red
 * escribía todas las filas en null y esa serie se quedaba sin imágenes ni nombres para siempre.
 *
 * [seasonNumber] es el que se pidió, y se usa como respaldo cuando el JSON no trae `season_number`.
 */
internal fun parseSeasonEpisodes(json: String?, seasonNumber: Int): List<TmdbEpisode>? {
    if (json == null) return null
    return runCatching {
        // `optJSONArray ?: JSONArray()` (lista vacía, no null) a propósito: un JSON válido SIN
        // capítulos sí es una respuesta, y tiene que escribirse como "preguntado y no había".
        val eps = JSONObject(json).optJSONArray("episodes") ?: JSONArray()
        (0 until eps.length()).mapNotNull { i ->
            val e = eps.optJSONObject(i) ?: return@mapNotNull null
            TmdbEpisode(
                season = e.optInt("season_number", seasonNumber),
                episode = e.optInt("episode_number"),
                name = e.optString("name").ifBlank { "Episodio ${e.optInt("episode_number")}" },
                overview = e.optString("overview"),
                air = e.optString("air_date").take(10),
                stillUrl = tmdbImgUrl(e.optString("still_path"), "w300"),
            )
        }
        // Un cuerpo que ni siquiera es JSON (error del gateway, portal cautivo de un wifi) cuenta
        // como "no se pudo consultar": preferimos reintentar en la próxima apertura antes que
        // sellar la serie entera en null por una respuesta rota.
    }.getOrNull()
}

/** Detalle de un título: metadata en español + lista de temporadas (capítulos aparte). */
data class TmdbDetail(
    val id: Int,
    val type: String,
    val title: String,
    val originalTitle: String,
    val posterUrl: String,
    val backdropUrl: String,
    val overview: String,
    val year: String,
    val imdbId: String,
    val seasons: List<TmdbSeason>,
    /** Títulos para buscar torrents: latino, castellano y original (deduplicados). */
    val searchTitles: List<String>,
) {
    val isSeries: Boolean get() = type == "tv"
}

/**
 * Metadata de películas y series vía TMDB, en **español latino (es-MX)**: da títulos, sinopsis,
 * temporadas y capítulos en español, además del título original.
 *
 * Habla DIRECTO con `api.themoviedb.org` (sub-proyecto 2A): antes era un passthrough por
 * `/v1/catalog/tmdb` del gateway, que era quien ponía la llave.
 */
class TmdbApi(
    /** Llave v3 de TMDB, embebida en el build de esta rama (`API_KEY` del `.env`). Se usa la v3 y
     *  no el bearer v4 para no depender de un segundo secreto. */
    private val apiKey: String = BuildConfig.TMDB_API_KEY,
    private val language: String = "es-MX",
    /** Parametrizable solo para los tests: producción habla con TMDB. */
    private val baseUrl: String = BASE_TMDB,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build(),
) {
    private val base: String get() = baseUrl

    /** Busca títulos. type: "movie" | "tv". */
    suspend fun search(type: String, query: String, page: Int = 1): List<TmdbItem> {
        val q = query.trim()
        if (q.isBlank()) return emptyList()
        return list("$base/search/$type?$auth&query=${enc(q)}&page=$page&include_adult=false", type)
    }

    /** Búsqueda mixta (pelis + series) para el wizard de búsqueda. Ignora 'person' y colecciones. */
    suspend fun searchMulti(query: String, page: Int = 1): List<TmdbItem> = withContext(Dispatchers.IO) {
        val q = query.trim()
        if (q.isBlank()) return@withContext emptyList()
        val json = get("$base/search/multi?$auth&query=${enc(q)}&page=$page&include_adult=false")
            ?: return@withContext emptyList()
        runCatching {
            val results = JSONObject(json).optJSONArray("results") ?: JSONArray()
            (0 until results.length()).mapNotNull { i -> results.optJSONObject(i)?.let { parseMultiItem(it) } }
        }.getOrDefault(emptyList())
    }

    /** Mapea un ítem de /search/multi según su media_type. Devuelve null para person/otros. */
    internal fun parseMultiItem(o: JSONObject): TmdbItem? {
        val type = when (o.optString("media_type")) {
            "movie" -> "movie"
            "tv" -> "tv"
            else -> return null
        }
        return parseItem(o, type)
    }

    /** Populares (paginado). type: "movie" | "tv". */
    suspend fun browse(type: String, page: Int): List<TmdbItem> =
        list("$base/$type/popular?$auth&page=${page.coerceAtLeast(1)}", type)

    /** Lista curada por categoría (populares/tendencias/top rated/en cartelera/próximamente). */
    suspend fun curated(type: String, category: TmdbCategory, page: Int): List<TmdbItem> =
        list("$base${tmdbCategoryPath(type, category)}?$auth&page=${page.coerceAtLeast(1)}", type)

    private val genreCache = java.util.concurrent.ConcurrentHashMap<String, List<TmdbGenre>>()

    /** Géneros disponibles para el tipo (cacheado en memoria). */
    suspend fun genres(type: String): List<TmdbGenre> {
        genreCache[type]?.let { return it }
        return withContext(Dispatchers.IO) {
            val json = get("$base/genre/$type/list?$auth") ?: return@withContext emptyList()
            runCatching {
                val arr = JSONObject(json).optJSONArray("genres") ?: JSONArray()
                (0 until arr.length()).mapNotNull { i ->
                    val o = arr.optJSONObject(i) ?: return@mapNotNull null
                    val id = o.optInt("id", 0); val name = o.optString("name")
                    if (id == 0 || name.isBlank()) null else TmdbGenre(id, name)
                }
            }.getOrDefault(emptyList()).also { if (it.isNotEmpty()) genreCache[type] = it }
        }
    }

    /** Descubrir por género. */
    suspend fun discover(type: String, genreId: Int, page: Int): List<TmdbItem> =
        list("$base/discover/$type?$auth&with_genres=$genreId&sort_by=popularity.desc&page=${page.coerceAtLeast(1)}", type)

    private suspend fun list(url: String, type: String): List<TmdbItem> = withContext(Dispatchers.IO) {
        val json = get(url) ?: return@withContext emptyList()
        runCatching {
            val results = JSONObject(json).optJSONArray("results") ?: JSONArray()
            (0 until results.length()).mapNotNull { i -> results.optJSONObject(i)?.let { parseItem(it, type) } }
        }.getOrDefault(emptyList())
    }

    /** Detalle con temporadas. type: "movie" | "tv". */
    suspend fun detail(type: String, id: Int): TmdbDetail? = withContext(Dispatchers.IO) {
        val json = get("$base/$type/$id?$auth&append_to_response=external_ids,translations") ?: return@withContext null
        runCatching {
            val o = JSONObject(json)
            val isTv = type == "tv"
            val seasons = o.optJSONArray("seasons")?.let { arr ->
                (0 until arr.length()).mapNotNull { i ->
                    val s = arr.optJSONObject(i) ?: return@mapNotNull null
                    TmdbSeason(
                        seasonNumber = s.optInt("season_number"),
                        episodeCount = s.optInt("episode_count"),
                        name = s.optString("name"),
                    )
                }
            } ?: emptyList()
            val imdb = o.optString("imdb_id").ifBlank {
                o.optJSONObject("external_ids")?.optString("imdb_id").orEmpty()
            }
            val localized = if (isTv) o.optString("name") else o.optString("title")
            val original = if (isTv) o.optString("original_name") else o.optString("original_title")
            TmdbDetail(
                id = id,
                type = type,
                title = localized,
                originalTitle = original,
                posterUrl = imgUrl(o.optString("poster_path"), "w500"),
                backdropUrl = imgUrl(o.optString("backdrop_path"), "w1280"),
                overview = o.optString("overview"),
                year = (if (isTv) o.optString("first_air_date") else o.optString("release_date")).take(4),
                imdbId = imdb,
                seasons = seasons,
                searchTitles = buildSearchTitles(localized, original, o.optJSONObject("translations"), isTv),
            )
        }.getOrNull()
    }

    /**
     * Junta títulos para buscar torrents: latino (es-MX) + inglés + original + castellano/otras
     * variantes en español, deduplicados. El **inglés** importa mucho para anime y cine extranjero:
     * el original de TMDB suele ser japonés en romaji (inútil para trackers), mientras los releases
     * usan el título en inglés (ej. "Curse of the Blood Rubies").
     */
    private fun buildSearchTitles(localized: String, original: String, translations: JSONObject?, isTv: Boolean): List<String> {
        val out = mutableListOf<String>()
        fun add(s: String?) { if (!s.isNullOrBlank() && out.none { it.equals(s.trim(), ignoreCase = true) }) out.add(s.trim()) }
        val trs = translations?.optJSONArray("translations")
        fun titleOf(t: JSONObject): String {
            val data = t.optJSONObject("data") ?: return ""
            return (if (isTv) data.optString("name") else data.optString("title"))
        }
        fun titlesFor(lang: String): List<JSONObject> =
            (0 until (trs?.length() ?: 0)).mapNotNull { trs?.optJSONObject(it) }
                .filter { it.optString("iso_639_1") == lang }

        add(localized) // es-MX (latino)
        // Título en inglés (preferir EE.UU.).
        val en = titlesFor("en")
        (en.firstOrNull { it.optString("iso_3166_1") == "US" } ?: en.firstOrNull())?.let { add(titleOf(it)) }
        // Original solo si está en alfabeto latino (el japonés/chino no matchea trackers y puede
        // hacer que algunos backends devuelvan basura al no encontrar nada).
        if (isLatinScript(original)) add(original)
        // Resto de variantes en español (España, Argentina, etc.).
        titlesFor("es").forEach { add(titleOf(it)) }
        return out.filter { it.isNotBlank() }.take(5)
    }

    /** true si el texto no tiene caracteres CJK/japoneses/coreanos (sirve para buscar en trackers). */
    private fun isLatinScript(s: String): Boolean =
        s.isNotBlank() && s.none { it.code in 0x2E80..0x9FFF || it.code in 0xAC00..0xD7AF || it.code in 0xFF00..0xFFEF }

    /**
     * Capítulos de una temporada de una serie.
     *
     * Devuelve **`null` si la consulta no se pudo hacer** y lista vacía si TMDB contestó sin
     * capítulos: ver [parseSeasonEpisodes] para por qué la diferencia importa. Los llamadores que
     * solo pintan una lista pueden tratarlas igual (`.orEmpty()`); el que cachea en base, no.
     */
    suspend fun seasonEpisodes(tvId: Int, seasonNumber: Int): List<TmdbEpisode>? = withContext(Dispatchers.IO) {
        parseSeasonEpisodes(get("$base/tv/$tvId/season/$seasonNumber?$auth"), seasonNumber)
    }

    /**
     * Todos los backdrops (apaisados) de un título, en URLs w1280, del más votado al menos.
     * Sin filtro de idioma (`include_image_language`) para traer la mayor variedad de fondos.
     * type: "movie" | "tv".
     */
    suspend fun images(type: String, id: Int, limit: Int = 8): List<String> = withContext(Dispatchers.IO) {
        val json = get("$base/$type/$id/images?include_image_language=es,en,null")
            ?: return@withContext emptyList()
        runCatching {
            val arr = JSONObject(json).optJSONArray("backdrops") ?: JSONArray()
            (0 until arr.length())
                .mapNotNull { i -> arr.optJSONObject(i)?.optString("file_path")?.takeIf { it.isNotBlank() } }
                .map { imgUrl(it, "w1280") }
                .take(limit)
        }.getOrDefault(emptyList())
    }

    /** Texto del JSON con el null de verdad convertido en vacío: `optString` de Android devuelve el
     *  STRING "null" en ese caso (el org.json del JVM devuelve "", así que un test unitario NO lo
     *  reproduce). Sin esto la sinopsis de lo que TMDB no tiene traducido era la palabra "null" —
     *  y así se guardaba en la biblioteca. */
    private fun JSONObject.texto(name: String): String = if (isNull(name)) "" else optString(name)

    private fun parseItem(o: JSONObject, type: String): TmdbItem? {
        val id = o.optInt("id", 0)
        if (id == 0) return null
        val isTv = type == "tv"
        val title = if (isTv) o.texto("name") else o.texto("title")
        if (title.isBlank()) return null
        return TmdbItem(
            id = id,
            type = type,
            title = title,
            originalTitle = if (isTv) o.texto("original_name") else o.texto("original_title"),
            posterUrl = imgUrl(o.texto("poster_path"), "w500"),
            year = (if (isTv) o.texto("first_air_date") else o.texto("release_date")).take(4),
            backdropUrl = imgUrl(o.texto("backdrop_path"), "w780"),
            overview = o.texto("overview"),
        )
    }

    /** La llave va como parámetro de query, que es como autentica la API v3 de TMDB. Antes acá
     *  iba solo el idioma porque la llave la ponía el gateway; en esta rama no hay gateway. */
    private val auth get() = "api_key=$apiKey&language=$language"
    // Delega en el helper de arriba: una sola definición de la base de imágenes para la clase y
    // para el parseo puro.
    private fun imgUrl(path: String?, size: String): String = tmdbImgUrl(path, size)
    private fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    companion object {
        /** No es configurable desde Ajustes: la base se fija en el build, como la llave. */
        const val BASE_TMDB = "https://api.themoviedb.org/3"
    }

    private fun get(url: String): String? = runCatching {
        client.newCall(Request.Builder().url(url).build()).execute().use {
            if (it.isSuccessful) it.body?.string() else null
        }
    }.getOrNull()
}
