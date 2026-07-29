package com.arkiv.player.data.catalog

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Arma el query GraphQL de `browse` (top-level, pura, testeable). */
fun buildAnimeBrowseQuery(sort: String, hasSearch: Boolean, hasGenre: Boolean): String {
    val searchParam = if (hasSearch) ",${'$'}search:String" else ""
    val genreParam = if (hasGenre) ",${'$'}genre:String" else ""
    val searchArg = if (hasSearch) ",search:${'$'}search" else ""
    val genreArg = if (hasGenre) ",genre_in:[${'$'}genre]" else ""
    return """
        query(${'$'}page:Int$searchParam$genreParam){
          Page(page:${'$'}page,perPage:30){
            media(type:ANIME,sort:[$sort],isAdult:false$searchArg$genreArg){
              id title{romaji english} coverImage{large} bannerImage
              averageScore episodes seasonYear genres description(asHtml:false) format
            }
          }
        }
    """.trimIndent()
}

/** Una serie de anime (metadata de AniList). */
data class AnimeShow(
    val id: Long,
    val title: String,
    val searchTitle: String,
    val posterUrl: String,
    val bannerUrl: String,
    val scorePct: Int,
    val episodes: Int,
    val year: Int,
    val genres: List<String>,
    val description: String,
    /** Formato AniList: "TV","MOVIE","OVA","ONA","SPECIAL","MUSIC","TV_SHORT". */
    val format: String = "",
)

/**
 * Metadata de anime vía AniList (GraphQL público, sin API key). Da pósters, populares,
 * búsqueda y episodios. Los magnets salen aparte de AnimeTosho, buscando por título.
 */
class AniListApi(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build(),
) {
    private val endpoint = "https://graphql.anilist.co"

    /** Explora/busca animes. sort: TRENDING_DESC | POPULARITY_DESC | SCORE_DESC | START_DATE_DESC. */
    suspend fun browse(page: Int, sort: String, search: String?, genre: String? = null): List<AnimeShow> = withContext(Dispatchers.IO) {
        val effectiveSort = if (!search.isNullOrBlank()) "SEARCH_MATCH" else sort
        val hasSearch = !search.isNullOrBlank()
        val hasGenre = genre != null
        val query = buildAnimeBrowseQuery(effectiveSort, hasSearch = hasSearch, hasGenre = hasGenre)
        val vars = JSONObject().put("page", page)
        if (hasSearch) vars.put("search", search)
        if (genre != null) vars.put("genre", genre)
        val body = JSONObject().put("query", query).put("variables", vars).toString()
        val json = runCatching {
            client.newCall(
                Request.Builder().url(endpoint)
                    .post(body.toRequestBody("application/json".toMediaType()))
                    .build(),
            ).execute().use { if (it.isSuccessful) it.body?.string() else null }
        }.getOrNull() ?: return@withContext emptyList()
        runCatching { parse(json) }.getOrDefault(emptyList())
    }

    /** Lista de géneros de AniList (GenreCollection). */
    suspend fun genres(): List<String> = withContext(Dispatchers.IO) {
        val body = JSONObject().put("query", "query{GenreCollection}").toString()
        val json = runCatching {
            client.newCall(
                Request.Builder().url(endpoint)
                    .post(body.toRequestBody("application/json".toMediaType()))
                    .build(),
            ).execute().use { if (it.isSuccessful) it.body?.string() else null }
        }.getOrNull() ?: return@withContext emptyList()
        runCatching {
            val arr = JSONObject(json).optJSONObject("data")?.optJSONArray("GenreCollection") ?: JSONArray()
            (0 until arr.length()).map { arr.getString(it) }
        }.getOrDefault(emptyList())
    }

    /** Detalle de un anime por su id de AniList. */
    suspend fun details(id: Long): AnimeShow? = withContext(Dispatchers.IO) {
        val query = """
            query(${'$'}id:Int){
              Media(id:${'$'}id,type:ANIME){
                id title{romaji english} coverImage{large} bannerImage
                averageScore episodes seasonYear genres description(asHtml:false) format
              }
            }
        """.trimIndent()
        val body = JSONObject().put("query", query).put("variables", JSONObject().put("id", id)).toString()
        val json = runCatching {
            client.newCall(
                Request.Builder().url(endpoint)
                    .post(body.toRequestBody("application/json".toMediaType())).build(),
            ).execute().use { if (it.isSuccessful) it.body?.string() else null }
        }.getOrNull() ?: return@withContext null
        runCatching {
            JSONObject(json).optJSONObject("data")?.optJSONObject("Media")?.let { parseMedia(it) }
        }.getOrNull()
    }

    /**
     * Nº de episodios ACUMULADOS antes de este anime en su cadena de precuelas TV. Sumado al nº de
     * episodio de la entrada da el nº ABSOLUTO de serie (ej. Shingeki Final Season ep 1 → abs 60).
     * Camina una sola cadena PREQUEL de formato TV; corta en ciclos o profundidad 12. 0 si falla.
     */
    suspend fun absoluteOffset(anilistId: Long): Int = withContext(Dispatchers.IO) {
        val seen = HashSet<Long>()
        var current = anilistId
        var total = 0
        var depth = 0
        while (depth++ < 12 && seen.add(current)) {
            val prequel = prequelOf(current) ?: break
            total += prequel.second
            current = prequel.first
        }
        total
    }

    // (idPrecuela, episodiosDePrecuela) del PREQUEL TV directo, o null.
    private fun prequelOf(id: Long): Pair<Long, Int>? {
        val query = """
            query(${'$'}id:Int){
              Media(id:${'$'}id,type:ANIME){
                relations{ edges{ relationType node{ id episodes format } } }
              }
            }
        """.trimIndent()
        val body = JSONObject().put("query", query).put("variables", JSONObject().put("id", id)).toString()
        val json = runCatching {
            client.newCall(
                Request.Builder().url(endpoint)
                    .post(body.toRequestBody("application/json".toMediaType())).build(),
            ).execute().use { if (it.isSuccessful) it.body?.string() else null }
        }.getOrNull() ?: return null
        return runCatching {
            val edges = JSONObject(json).optJSONObject("data")?.optJSONObject("Media")
                ?.optJSONObject("relations")?.optJSONArray("edges") ?: return null
            for (i in 0 until edges.length()) {
                val e = edges.optJSONObject(i) ?: continue
                if (e.optString("relationType") != "PREQUEL") continue
                val node = e.optJSONObject("node") ?: continue
                if (node.optString("format") != "TV") continue
                return node.optLong("id").takeIf { it > 0 }?.let { it to node.optInt("episodes", 0) }
            }
            null
        }.getOrNull()
    }

    private fun parse(json: String): List<AnimeShow> {
        val media = JSONObject(json).optJSONObject("data")?.optJSONObject("Page")?.optJSONArray("media")
            ?: return emptyList()
        return (0 until media.length()).mapNotNull { i ->
            media.optJSONObject(i)?.let { runCatching { parseMedia(it) }.getOrNull() }
        }
    }

    private fun parseMedia(o: JSONObject): AnimeShow? {
        val t = o.optJSONObject("title")
        val english = t?.optString("english").orEmpty()
        val romaji = t?.optString("romaji").orEmpty()
        val display = english.ifBlank { romaji }
        if (display.isBlank()) return null
        return AnimeShow(
            id = o.optLong("id"),
            title = display,
            // Para buscar en AnimeTosho conviene el romaji (así nombran los releases).
            searchTitle = romaji.ifBlank { english },
            posterUrl = o.optJSONObject("coverImage")?.optString("large").orEmpty(),
            bannerUrl = o.optString("bannerImage"),
            scorePct = o.optInt("averageScore"),
            episodes = o.optInt("episodes"),
            year = o.optInt("seasonYear"),
            genres = o.optJSONArray("genres")?.let { g -> (0 until g.length()).map { g.getString(it) } } ?: emptyList(),
            description = stripHtml(o.optString("description")),
            format = o.optString("format"),
        )
    }

    private fun stripHtml(s: String): String =
        s.replace(Regex("<[^>]*>"), "").replace("&nbsp;", " ").replace("&quot;", "\"").trim()
}
