package com.arkiv.player.data.catalog

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/** Un torrent (calidad) de un ítem del catálogo. */
data class CatalogTorrent(
    val magnet: String,
    val quality: String,
    val seeds: Int,
    val peers: Int,
    val sizeLabel: String,
)

/** Resumen para la grilla (película o serie). */
data class CatalogItem(
    val imdbId: String,
    val title: String,
    val year: String,
    val ratingPct: Int,
    val posterUrl: String,
    val isShow: Boolean,
)

/** Detalle de una película. */
data class CatalogMovie(
    val imdbId: String,
    val title: String,
    val year: String,
    val ratingPct: Int,
    val posterUrl: String,
    val backdropUrl: String,
    val genres: List<String>,
    val synopsis: String,
    val runtime: String,
    val torrents: List<CatalogTorrent>,
)

/** Un episodio de una serie con sus torrents. */
data class CatalogEpisode(
    val season: Int,
    val episode: Int,
    val title: String,
    val overview: String,
    val torrents: List<CatalogTorrent>,
)

/** Detalle de una serie. */
data class CatalogShow(
    val imdbId: String,
    val title: String,
    val year: String,
    val ratingPct: Int,
    val posterUrl: String,
    val backdropUrl: String,
    val genres: List<String>,
    val synopsis: String,
    val numSeasons: Int,
    val episodes: List<CatalogEpisode>,
)

/**
 * Cliente del catálogo estilo Popcorn Time (API Butter). Sirve películas y series con
 * magnets incluidos. Usa mirrors con rotación (yts.mx suele estar bloqueado por DNS).
 */
class CatalogApi(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build(),
) {
    private val mirrors = listOf(
        "https://fusme.link",
        "https://jfper.link",
        "https://uxert.link",
        "https://yrkde.link",
    )

    /** kind: "movies" | "shows". Devuelve resúmenes para la grilla. */
    suspend fun list(
        kind: String,
        page: Int,
        sort: String = "trending",
        query: String? = null,
    ): List<CatalogItem> = withContext(Dispatchers.IO) {
        val path = buildString {
            append("/").append(kind).append("/").append(page)
            append("?sort=").append(URLEncoder.encode(sort, "UTF-8"))
            append("&order=-1")
            if (!query.isNullOrBlank()) append("&keywords=").append(URLEncoder.encode(query, "UTF-8"))
        }
        val json = fetch(path) ?: return@withContext emptyList()
        val isShow = kind == "shows"
        runCatching {
            val arr = JSONArray(json)
            (0 until arr.length()).mapNotNull { i ->
                runCatching { parseItem(arr.getJSONObject(i), isShow) }.getOrNull()
            }
        }.getOrDefault(emptyList())
    }

    suspend fun movie(imdbId: String): CatalogMovie? = withContext(Dispatchers.IO) {
        val json = fetch("/movie/$imdbId") ?: return@withContext null
        runCatching { parseMovie(JSONObject(json)) }.getOrNull()
    }

    suspend fun show(imdbId: String): CatalogShow? = withContext(Dispatchers.IO) {
        val json = fetch("/show/$imdbId") ?: return@withContext null
        runCatching { parseShow(JSONObject(json)) }.getOrNull()
    }

    private fun fetch(path: String): String? {
        for (base in mirrors) {
            val body = runCatching {
                client.newCall(
                    Request.Builder().url("$base$path").header("User-Agent", "Mozilla/5.0").build(),
                ).execute().use { if (it.isSuccessful) it.body?.string() else null }
            }.getOrNull()
            if (!body.isNullOrBlank() && body.trimStart().let { it.startsWith("[") || it.startsWith("{") }) {
                return body
            }
        }
        return null
    }

    private fun parseItem(o: JSONObject, isShow: Boolean): CatalogItem = CatalogItem(
        imdbId = o.optString("imdb_id").ifBlank { o.optString("_id") },
        title = o.optString("title"),
        year = o.optString("year"),
        ratingPct = o.optJSONObject("rating")?.optInt("percentage") ?: 0,
        posterUrl = o.optJSONObject("images")?.optString("poster").orEmpty(),
        isShow = isShow,
    )

    private fun parseMovie(o: JSONObject): CatalogMovie {
        val images = o.optJSONObject("images")
        return CatalogMovie(
            imdbId = o.optString("imdb_id").ifBlank { o.optString("_id") },
            title = o.optString("title"),
            year = o.optString("year"),
            ratingPct = o.optJSONObject("rating")?.optInt("percentage") ?: 0,
            posterUrl = images?.optString("poster").orEmpty(),
            backdropUrl = images?.optString("fanart").orEmpty(),
            genres = parseGenres(o),
            synopsis = o.optString("synopsis"),
            runtime = o.optString("runtime"),
            torrents = parseTorrentsByQuality(o.optJSONObject("torrents")),
        )
    }

    private fun parseShow(o: JSONObject): CatalogShow {
        val images = o.optJSONObject("images")
        val epsArr = o.optJSONArray("episodes")
        val episodes = if (epsArr == null) emptyList() else (0 until epsArr.length()).mapNotNull { i ->
            val e = epsArr.optJSONObject(i) ?: return@mapNotNull null
            val torrents = parseTorrentsByQuality(e.optJSONObject("torrents"))
            if (torrents.isEmpty()) return@mapNotNull null
            CatalogEpisode(
                season = e.optInt("season"),
                episode = e.optInt("episode"),
                title = e.optString("title"),
                overview = e.optString("overview"),
                torrents = torrents,
            )
        }.sortedWith(compareBy({ it.season }, { it.episode }))
        return CatalogShow(
            imdbId = o.optString("imdb_id").ifBlank { o.optString("_id") },
            title = o.optString("title"),
            year = o.optString("year"),
            ratingPct = o.optJSONObject("rating")?.optInt("percentage") ?: 0,
            posterUrl = images?.optString("poster").orEmpty(),
            backdropUrl = images?.optString("fanart").orEmpty(),
            genres = parseGenres(o),
            synopsis = o.optString("synopsis"),
            numSeasons = o.optInt("num_seasons"),
            episodes = episodes,
        )
    }

    private fun parseGenres(o: JSONObject): List<String> =
        o.optJSONArray("genres")?.let { g -> (0 until g.length()).map { g.getString(it) } } ?: emptyList()

    /** Torrents de un objeto {calidad: {...}} o {lang: {calidad: {...}}} (películas). */
    private fun parseTorrentsByQuality(t: JSONObject?): List<CatalogTorrent> {
        t ?: return emptyList()
        // Películas anidan por idioma (en); series van directo por calidad.
        val quals = if (t.has("en") && t.optJSONObject("en") != null) t.getJSONObject("en") else t
        return quals.keys().asSequence().mapNotNull { q ->
            if (q == "0") return@mapNotNull null
            val to = quals.optJSONObject(q) ?: return@mapNotNull null
            val magnet = to.optString("url")
            if (!magnet.startsWith("magnet:")) return@mapNotNull null
            CatalogTorrent(
                magnet = withTrackers(magnet),
                quality = q,
                seeds = to.optString("seed").toIntOrNull() ?: to.optString("seeds").toIntOrNull() ?: 0,
                peers = to.optString("peer").toIntOrNull() ?: to.optString("peers").toIntOrNull() ?: 0,
                sizeLabel = to.optString("filesize"),
            )
        }.sortedByDescending { qualityRank(it.quality) }.toList()
    }

    private fun qualityRank(q: String): Int = when {
        q.contains("2160") || q.contains("4k", true) -> 4
        q.contains("1080") -> 3
        q.contains("720") -> 2
        else -> 1
    }

    private fun withTrackers(magnet: String): String =
        magnet + TRACKERS.joinToString("") { "&tr=" + URLEncoder.encode(it, "UTF-8") }

    companion object {
        private val TRACKERS = listOf(
            "udp://tracker.opentrackr.org:1337/announce",
            "udp://open.demonii.com:1337/announce",
            "udp://tracker.openbittorrent.com:6969/announce",
            "udp://open.stealth.si:80/announce",
            "udp://exodus.desync.com:6969/announce",
            "udp://tracker.torrent.eu.org:451/announce",
            "udp://tracker.moeking.me:6969/announce",
            "udp://p4p.arenabg.com:1337/announce",
        )
    }
}
