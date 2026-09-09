package com.arkiv.player.data.gateway

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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

/**
 * Cliente del gateway, para lo que en esta rama sigue siendo del servidor: la trivia ("dato
 * curioso", excepción permanente), los marcadores de intro, la metadata de anime y el aviso para
 * regenerar recomendaciones.
 *
 * El contenido ya NO sale de acá: búsqueda, reproducción y capítulos se los pide
 * [com.arkiv.player.data.magis.MagisFuente] al portal directo (sub-proyecto 2A).
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
    // Lo que queda son cuerpos cortos: el stream largo era `/v1/search`, que ya no pasa por acá.
    private val http = http.newBuilder()
        .readTimeout(15, TimeUnit.SECONDS)
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
    suspend fun marcadores(tmdbId: Int, temporada: Int, episodio: Int): GatewayMarcadores? =
        withContext(Dispatchers.IO) {
            runCatching {
                val url = "${baseUrl()}/v1/marcadores?tmdbId=$tmdbId&temporada=$temporada&episodio=$episodio"
                parseMarcadores(ejecutar(pedido(url).get().build()))
            }.getOrNull()
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

    /**
     * Avisa al gateway que conviene reconsiderar la fila "Para ti" (spec
     * `2026-08-16-recomendaciones-por-historial`): responde 202 al instante y decide él mismo si
     * corresponde generar -tiene su propia ventana de 24 h y su interruptor por cuenta-, así que
     * este cliente no intenta adivinar nada de esa decisión, solo avisa.
     *
     * Lanza [GatewayException] igual que el resto de los métodos de esta clase si el pedido falla:
     * acá NO se traga el error -eso es responsabilidad de quien llama (ver
     * [com.arkiv.player.data.gateway.AvisadorDeRecomendaciones]).
     */
    suspend fun refrescarRecomendaciones() {
        withContext(Dispatchers.IO) {
            ejecutar(
                pedido("${baseUrl()}/v1/recomendaciones/refrescar")
                    .post(JSONObject().toString().toRequestBody("application/json".toMediaType()))
                    .build(),
            )
        }
    }

    /**
     * Datos curiosos sobre lo que se está reproduciendo, o lista vacía.
     *
     * El gateway NUNCA responde error acá: sin llave de modelo, sin tmdbId o con el modelo caído
     * devuelve `{"textos": []}`. Sin datos no se dibuja el botón, que es el fallo bueno para una
     * función accesoria: nadie ve un error encima del video.
     */
    suspend fun trivia(
        tmdbId: Int,
        tipo: String,
        temporada: Int?,
        episodio: Int?,
    ): List<String> = withContext(Dispatchers.IO) {
        val url = "${baseUrl()}/v1/trivia?tmdbId=$tmdbId&tipo=$tipo" +
            "&temporada=${temporada ?: 0}&episodio=${episodio ?: 0}"
        val arr = JSONObject(ejecutar(pedido(url).get().build())).optJSONArray("textos")
            ?: return@withContext emptyList()
        (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { t -> t.isNotBlank() } }
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
