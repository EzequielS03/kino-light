package com.arkiv.player.data.gateway

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class GatewayException(mensaje: String, causa: Throwable? = null) : RuntimeException(mensaje, causa)

data class GatewaySearchQuery(
    val q: String,
    val type: String = "movie",
    val season: Int = 0,
    val episode: Int = 0,
    val year: String = "",
    val tmdbId: Int = 0,
    val anilistId: Long = 0,
    val lang: String = "",
    val sources: String = "",
    /** Tope de tamaño por torrent en bytes (0 = sin tope). */
    val maxBytes: Long = 0,
    val budgetMs: Int = 0,
)

data class GatewaySource(
    val name: String,
    val capabilities: List<String>,
    val state: String,
)

/**
 * Cliente del gateway unificado.
 *
 * [search] emite eventos **a medida que llegan**: el gateway responde NDJSON en
 * streaming y la pantalla ya está construida para pintar resultados de forma
 * incremental. Bufferizar la respuesta entera sería una regresión de UX.
 */
class ArkivApiClient(
    private val baseUrl: () -> String,
    private val apiKey: () -> String,
    http: OkHttpClient,
) {
    // Sin timeout de lectura: la respuesta es un stream largo, no un cuerpo corto.
    private val http = http.newBuilder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .connectTimeout(15, TimeUnit.SECONDS)
        .build()

    private fun pedido(url: String): Request.Builder =
        Request.Builder().url(url).header("X-Arkiv-Key", apiKey())

    fun search(ctx: GatewaySearchQuery): Flow<SearchEvent> = flow {
        val url = "${baseUrl()}/v1/search".toHttpUrl().newBuilder().apply {
            addQueryParameter("q", ctx.q)
            addQueryParameter("type", ctx.type)
            if (ctx.season > 0) addQueryParameter("season", ctx.season.toString())
            if (ctx.episode > 0) addQueryParameter("episode", ctx.episode.toString())
            if (ctx.year.isNotBlank()) addQueryParameter("year", ctx.year)
            if (ctx.tmdbId > 0) addQueryParameter("tmdb_id", ctx.tmdbId.toString())
            if (ctx.anilistId > 0) addQueryParameter("anilist_id", ctx.anilistId.toString())
            if (ctx.lang.isNotBlank()) addQueryParameter("lang", ctx.lang)
            if (ctx.sources.isNotBlank()) addQueryParameter("sources", ctx.sources)
            if (ctx.maxBytes > 0) addQueryParameter("max_bytes", ctx.maxBytes.toString())
            if (ctx.budgetMs > 0) addQueryParameter("budget_ms", ctx.budgetMs.toString())
        }.build().toString()

        val respuesta = runCatching { http.newCall(pedido(url).get().build()).execute() }
            .getOrElse { throw GatewayException("no se pudo llamar al gateway", it) }

        respuesta.use { r ->
            if (!r.isSuccessful) throw GatewayException("gateway respondio ${r.code}")
            val cuerpo = r.body ?: throw GatewayException("gateway respondio sin cuerpo")
            val fuente = cuerpo.source()
            while (true) {
                val linea = fuente.readUtf8Line() ?: break
                if (linea.isBlank()) continue
                emit(parseSearchEvent(linea))
            }
        }
    }.flowOn(Dispatchers.IO)

    suspend fun resolve(ref: String): GatewayPlayable = withContext(Dispatchers.IO) {
        val cuerpo = JSONObject().put("ref", ref).toString()
            .toRequestBody("application/json".toMediaType())
        val o = JSONObject(ejecutar(pedido("${baseUrl()}/v1/resolve").post(cuerpo).build()))
        GatewayPlayable(
            kind = o.optString("kind"),
            url = o.optString("url"),
            headers = o.optJSONObject("headers")?.let { h ->
                h.keys().asSequence().associateWith { h.optString(it) }
            } ?: emptyMap(),
            mime = o.optString("mime"),
            expiresAt = o.optString("expires_at"),
            durationMs = o.optLong("duration_ms", 0L).coerceAtLeast(0L),
            videoCodec = o.optString("video_codec"),
            fallbackUrl = o.optJSONObject("fallback")?.optString("url"),
            subtitles = o.optJSONArray("subtitles")?.let { arr ->
                (0 until arr.length()).mapNotNull { i ->
                    arr.optJSONObject(i)?.let { sub ->
                        val u = sub.optString("url")
                        if (u.isBlank()) null
                        else GatewaySubtitle(sub.optString("lang"), u, sub.optString("format"))
                    }
                }
            } ?: emptyList(),
        )
    }

    /** Capítulos de una temporada. Solo Magis los expone; el resto responde 422. */
    suspend fun episodes(ref: String): List<GatewayEpisode> = withContext(Dispatchers.IO) {
        val cuerpo = JSONObject().put("ref", ref).toString()
            .toRequestBody("application/json".toMediaType())
        val arr = JSONObject(ejecutar(pedido("${baseUrl()}/v1/episodes").post(cuerpo).build()))
            .optJSONArray("episodes") ?: return@withContext emptyList()
        (0 until arr.length()).mapNotNull { i ->
            arr.optJSONObject(i)?.let { e ->
                val r = e.optString("ref")
                if (r.isBlank()) null
                else GatewayEpisode(e.optInt("number"), e.optString("title"), r)
            }
        }
    }

    /**
     * Metadata de un anime (títulos, temporada TVDB, offset absoluto y tmdb_id).
     *
     * La búsqueda ya no la necesita —la resuelve el gateway por dentro—, pero la biblioteca propia
     * indexa por `tmdb_id`. Pedirla acá le evita al dispositivo bajar los ~30 MB del dataset de
     * Fribb que antes descargaba cada teléfono por su cuenta.
     */
    suspend fun animeMeta(anilistId: Long): GatewayAnimeMeta? = withContext(Dispatchers.IO) {
        runCatching {
            val o = JSONObject(ejecutar(pedido("${baseUrl()}/v1/anime/$anilistId").get().build()))
            val t = o.optJSONArray("titles")
            GatewayAnimeMeta(
                titles = (0 until (t?.length() ?: 0)).map { t!!.getString(it) },
                tvdbSeason = o.optInt("tvdb_season", -1).takeIf { it >= 0 },
                offset = o.optInt("offset"),
                tmdbId = o.optInt("tmdb_id").takeIf { it > 0 },
            )
        }.getOrNull()
    }

    suspend fun sources(): List<GatewaySource> = withContext(Dispatchers.IO) {
        val arr = JSONObject(ejecutar(pedido("${baseUrl()}/v1/sources").get().build()))
            .optJSONArray("sources") ?: return@withContext emptyList()
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val caps = o.optJSONArray("capabilities")
            GatewaySource(
                name = o.optString("name"),
                capabilities = (0 until (caps?.length() ?: 0)).map { caps!!.getString(it) },
                state = o.optString("state"),
            )
        }
    }

    private fun ejecutar(request: Request): String {
        val r = runCatching { http.newCall(request).execute() }
            .getOrElse { throw GatewayException("no se pudo llamar al gateway", it) }
        r.use {
            if (!it.isSuccessful) throw GatewayException("gateway respondio ${it.code}")
            return it.body?.string().orEmpty()
        }
    }
}
