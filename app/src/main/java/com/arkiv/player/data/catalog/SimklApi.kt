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
    /** Base del gateway. El client_id de Simkl vive en el servidor. */
    private val gatewayUrl: () -> String,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build(),
    /** Token de sesión de la PERSONA, misma fuente que ya usa `CuentaApi` para `Authorization`
     *  (`SesionDePersona.token()`). Task 8 (Paso 3): `X-Arkiv-Key` salió del todo -- ver KDoc del
     *  mismo parámetro en `ArkivApiClient`. */
    private val personToken: () -> String? = { null },
    /** Token del APARATO que llama, misma fuente que ya usa `CuentaApi` para `X-Arkiv-Device`
     *  (`DeviceAuthManager.session.value?.token`): `require_sesion` exige las dos juntas. */
    private val deviceToken: () -> String? = { null },
) {
    suspend fun infoByAniList(anilistId: Long): SimklAnimeInfo? = withContext(Dispatchers.IO) {
        val sid = SimklParser.parseSearch(
            get("${gatewayUrl()}/v1/catalog/simkl/search/id?anilist=$anilistId") ?: return@withContext null,
        ) ?: return@withContext null
        SimklParser.parseDetail(
            get("${gatewayUrl()}/v1/catalog/simkl/anime/$sid?extended=full") ?: return@withContext null,
        )
    }

    private fun get(url: String): String? = runCatching {
        val b = Request.Builder().url(url)
        // Sin sesión/aparato todavía (null o vacío) se omiten las cabeceras -- mandarlas vacías
        // sería peor que no mandarlas (ver ArkivApiClient.pedido).
        personToken()?.takeIf { it.isNotBlank() }?.let { b.header("Authorization", it) }
        deviceToken()?.takeIf { it.isNotBlank() }?.let { b.header("X-Arkiv-Device", it) }
        client.newCall(b.build()).execute().use { if (it.isSuccessful) it.body?.string() else null }
    }.getOrNull()
}
