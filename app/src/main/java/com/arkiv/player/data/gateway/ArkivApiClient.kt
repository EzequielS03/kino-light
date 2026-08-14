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
    http: OkHttpClient,
    /**
     * Token de sesión de la PERSONA (`SesionDePersona.token()`, misma fuente que ya usa
     * `CuentaApi` para `Authorization`). Task 8 (Paso 3): `X-Arkiv-Key` salió del todo -- junto
     * con [deviceToken], esta es ahora la ÚNICA credencial que manda este cliente. El gateway
     * acepta sesión desde el Paso 1, así que sacar la llave acá no deja a nadie sin poder pedir
     * nada.
     */
    private val personToken: () -> String? = { null },
    /** Token del APARATO que llama (`DeviceAuthManager.session.value?.token`, misma fuente que ya
     *  usa `CuentaApi` para `X-Arkiv-Device`): `require_sesion` (Task 5b) exige las dos cabeceras
     *  juntas -- la sesión está atada al aparato, así que el `Authorization` solo no alcanza. */
    private val deviceToken: () -> String? = { null },
) {
    // Sin timeout de lectura: la respuesta es un stream largo, no un cuerpo corto.
    private val http = http.newBuilder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .connectTimeout(15, TimeUnit.SECONDS)
        .build()

    private fun pedido(url: String): Request.Builder {
        val b = Request.Builder().url(url)
        // Si todavía no hay sesión/aparato (null o vacío), se OMITEN las cabeceras en vez de
        // mandarlas vacías: un `Authorization: ` en blanco es peor que ausente (el gateway podría
        // tratarlo como un intento de credencial mal formado, en vez de "no mandó nada").
        personToken()?.takeIf { it.isNotBlank() }?.let { b.header("Authorization", it) }
        deviceToken()?.takeIf { it.isNotBlank() }?.let { b.header("X-Arkiv-Device", it) }
        return b
    }

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

    /**
     * Capítulos de una temporada, junto con la serie que el gateway pudo identificar contra TMDB
     * cruzando el imdb_id del portal ([GatewaySerie] es null si no la pudo resolver, o si el
     * gateway todavía no manda el bloque `series`).
     */
    suspend fun episodesConSerie(ref: String): Pair<List<GatewayEpisode>, GatewaySerie?> =
        withContext(Dispatchers.IO) {
            val cuerpo = JSONObject().put("ref", ref).toString()
                .toRequestBody("application/json".toMediaType())
            val body = ejecutar(pedido("${baseUrl()}/v1/episodes").post(cuerpo).build())
            val crudos = JSONObject(body).optJSONArray("episodes")
            if (crudos == null) {
                // Respondió 200 pero sin `episodes`. La UI lo mostraría como una lista vacía, que se
                // ve igual que "esta temporada no tiene capítulos" — y no es lo mismo.
                android.util.Log.w("ArkivGw", "/v1/episodes 200 SIN campo `episodes` ref=${ref.take(24)}…")
                return@withContext emptyList<GatewayEpisode>() to null
            }
            val (caps, serie) = parseEpisodesResponse(body)
            // Los dos números, no solo el final: `parseEpisodesResponse` descarta los capítulos que
            // vienen con `ref` vacío (sin ref no hay nada que reproducir). Con un solo número, una
            // temporada de 16 que llega con 4 refs rotos se ve igual que una de 12 — y son problemas
            // distintos, uno del portal y otro nuestro.
            // También se loguea la IDENTIFICACIÓN de la serie, porque de ella cuelga TODO lo que la
            // biblioteca muestra de los capítulos: nombre, miniatura y sinopsis salen de TMDB, no del
            // portal. Sin esto, "los capítulos salen en negro y numerados" es indistinguible de sus
            // tres causas posibles —el portal no dio imdb_id, TMDB no lo encontró, o el guard de
            // numeración del gateway apagó el enriquecimiento— y no hay forma de saber cuál fue.
            val identidad = serie
                ?.let { "imdb=${it.imdbId.ifBlank { "(vacio)" }} tmdb=${it.tmdbId} temporada=${it.seasonNumber}" }
                ?: "(el gateway no mandó bloque `series`)"
            android.util.Log.w(
                "ArkivGw",
                "/v1/episodes → ${caps.size} capitulos (de ${crudos.length()} crudos) " +
                    "serie: $identidad · con miniatura=${caps.count { it.still != null }}",
            )
            caps to serie
        }

    /** Capítulos de una temporada. Solo Magis los expone; el resto responde 422. */
    suspend fun episodes(ref: String): List<GatewayEpisode> = episodesConSerie(ref).first

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
        // Se loguea el CAMINO (no la URL entera: lleva la llave) y el código. Sin esto, todo fallo
        // del gateway llega a la UI como un texto genérico y no hay forma de separar "no hubo red"
        // de "el gateway dijo que no" ni de ver QUÉ dijo. El cuerpo del error se recorta: los
        // mensajes útiles del gateway vienen al principio.
        val camino = request.url.encodedPath
        val r = runCatching { http.newCall(request).execute() }
            .getOrElse {
                android.util.Log.w("ArkivGw", "$camino → sin respuesta: ${it.javaClass.simpleName}: ${it.message}")
                throw GatewayException("no se pudo llamar al gateway", it)
            }
        r.use {
            if (!it.isSuccessful) {
                val detalle = runCatching { it.body?.string().orEmpty() }.getOrDefault("").take(300)
                android.util.Log.w("ArkivGw", "$camino → HTTP ${it.code}: $detalle")
                throw GatewayException("gateway respondio ${it.code}")
            }
            return it.body?.string().orEmpty()
        }
    }
}
