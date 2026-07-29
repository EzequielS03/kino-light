package com.arkiv.player.data.catalog

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Un título del catálogo (película o serie), con su id de IMDb. */
data class CineItem(
    val imdbId: String,
    val type: String, // "movie" | "series"
    val title: String,
    val posterUrl: String,
    val year: String,
) {
    val isSeries: Boolean get() = type == "series"
}

/** Un capítulo de una serie (temporada + número). */
data class CineEpisode(
    val season: Int,
    val episode: Int,
    val name: String,
    val released: String,
    val thumb: String,
)

/** Detalle de un título: metadata + capítulos (vacío si es película). */
data class CineDetail(
    val imdbId: String,
    val type: String,
    val title: String,
    val posterUrl: String,
    val backgroundUrl: String,
    val description: String,
    val year: String,
    val episodes: List<CineEpisode>,
) {
    val isSeries: Boolean get() = type == "series"

    /** Temporadas presentes, ordenadas (0 = especiales/OVAs). */
    val seasons: List<Int> get() = episodes.map { it.season }.distinct().sorted()

    fun episodesOf(season: Int): List<CineEpisode> =
        episodes.filter { it.season == season }.sortedBy { it.episode }
}

/**
 * Metadata de películas, series y anime vía Cinemeta (el catálogo público de Stremio, sin API
 * key). Da búsqueda, listados populares, y para series la lista de temporadas/capítulos con el
 * id de IMDb — que luego usamos para buscar torrents específicos por capítulo e idioma.
 */
class CinemetaApi(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build(),
) {
    private val base = "https://v3-cinemeta.strem.io"

    /** Busca títulos por texto. type: "movie" | "series". */
    suspend fun search(type: String, query: String): List<CineItem> {
        val q = query.trim()
        if (q.isBlank()) return emptyList()
        return catalog("$base/catalog/$type/top/search=${enc(q)}.json", type)
    }

    /** Lista popular (paginada por múltiplos de 100). type: "movie" | "series". */
    suspend fun browse(type: String, skip: Int): List<CineItem> {
        val url = if (skip <= 0) "$base/catalog/$type/top.json" else "$base/catalog/$type/top/skip=$skip.json"
        return catalog(url, type)
    }

    private suspend fun catalog(url: String, type: String): List<CineItem> = withContext(Dispatchers.IO) {
        val json = get(url) ?: return@withContext emptyList()
        runCatching {
            val metas = JSONObject(json).optJSONArray("metas") ?: JSONArray()
            (0 until metas.length()).mapNotNull { i -> metas.optJSONObject(i)?.let { parseItem(it, type) } }
        }.getOrDefault(emptyList())
    }

    /** Detalle con capítulos (para series). type: "movie" | "series". */
    suspend fun detail(type: String, imdbId: String): CineDetail? = withContext(Dispatchers.IO) {
        val json = get("$base/meta/$type/$imdbId.json") ?: return@withContext null
        runCatching {
            val m = JSONObject(json).optJSONObject("meta") ?: return@runCatching null
            val videos = m.optJSONArray("videos") ?: JSONArray()
            val episodes = (0 until videos.length()).mapNotNull { i ->
                val v = videos.optJSONObject(i) ?: return@mapNotNull null
                // Solo capítulos con temporada/episodio válidos.
                if (!v.has("season") || !v.has("episode")) return@mapNotNull null
                CineEpisode(
                    season = v.optInt("season"),
                    episode = v.optInt("episode"),
                    name = v.optString("name").ifBlank { "Episodio ${v.optInt("episode")}" },
                    released = v.optString("released").take(10),
                    thumb = v.optString("thumbnail"),
                )
            }
            CineDetail(
                imdbId = m.optString("imdb_id").ifBlank { imdbId },
                type = type,
                title = m.optString("name"),
                posterUrl = m.optString("poster"),
                backgroundUrl = m.optString("background"),
                description = m.optString("description"),
                year = m.optString("year").ifBlank { m.optString("releaseInfo") },
                episodes = episodes,
            )
        }.getOrNull()
    }

    private fun parseItem(o: JSONObject, fallbackType: String): CineItem? {
        val id = o.optString("imdb_id").ifBlank { o.optString("id") }
        if (!id.startsWith("tt")) return null
        val name = o.optString("name")
        if (name.isBlank()) return null
        return CineItem(
            imdbId = id,
            type = o.optString("type").ifBlank { fallbackType },
            title = name,
            posterUrl = o.optString("poster"),
            year = o.optString("releaseInfo").ifBlank { o.optString("year") },
        )
    }

    private fun get(url: String): String? = runCatching {
        client.newCall(Request.Builder().url(url).build()).execute().use {
            if (it.isSuccessful) it.body?.string() else null
        }
    }.getOrNull()

    private fun enc(s: String): String = java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20")
}
