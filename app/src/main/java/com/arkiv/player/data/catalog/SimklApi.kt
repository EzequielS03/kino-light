package com.arkiv.player.data.catalog

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Info autoritativa de un anime en Simkl (episodios + cross-ids + títulos alternativos). */
data class SimklAnimeInfo(
    val simklId: Long,
    val totalEpisodes: Int,
    val imdbId: String?,
    val tmdbId: Int?,
    val tvdbId: Long?,
    val altTitles: List<String>,
)

/** Parsers puros de las respuestas de Simkl (testeables sin red). */
object SimklParser {
    fun parseSearch(json: String): Long? = runCatching {
        val arr = JSONArray(json)
        arr.optJSONObject(0)?.optJSONObject("ids")?.optLong("simkl", 0L)?.takeIf { it > 0 }
    }.getOrNull()

    fun parseDetail(json: String): SimklAnimeInfo? = runCatching {
        val o = JSONObject(json)
        val ids = o.optJSONObject("ids") ?: return@runCatching null
        val simkl = ids.optLong("simkl", 0L).takeIf { it > 0 } ?: return@runCatching null
        val alts = o.optJSONArray("alt_titles")?.let { a ->
            (0 until a.length()).mapNotNull { a.optJSONObject(it)?.optString("name")?.ifBlank { null } }
        }.orEmpty()
        SimklAnimeInfo(
            simklId = simkl,
            totalEpisodes = o.optInt("total_episodes", 0),
            imdbId = ids.optString("imdb").ifBlank { null },
            tmdbId = ids.optString("tmdb").toIntOrNull(),
            tvdbId = ids.optString("tvdb").toLongOrNull(),
            altTitles = alts,
        )
    }.getOrNull()
}

/**
 * Cliente Simkl (público, sin OAuth). Autentica con el header `simkl-api-key = client_id`.
 * `search/id?anilist=` da el id de Simkl; `anime/{id}?extended=full` da episodios + cross-ids.
 */
class SimklApi(
    private val clientId: String,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build(),
) {
    val configured: Boolean get() = clientId.isNotBlank()

    suspend fun infoByAniList(anilistId: Long): SimklAnimeInfo? = withContext(Dispatchers.IO) {
        if (!configured) return@withContext null
        val sid = SimklParser.parseSearch(
            get("https://api.simkl.com/search/id?anilist=$anilistId") ?: return@withContext null,
        ) ?: return@withContext null
        SimklParser.parseDetail(
            get("https://api.simkl.com/anime/$sid?extended=full") ?: return@withContext null,
        )
    }

    private fun get(url: String): String? = runCatching {
        client.newCall(
            Request.Builder().url(url).header("simkl-api-key", clientId).build(),
        ).execute().use { if (it.isSuccessful) it.body?.string() else null }
    }.getOrNull()
}
