package com.arkiv.player.data.gateway

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

data class LiveCategory(val id: Int, val nombre: String)

data class LiveChannel(val code: String, val nombre: String, val numero: Int, val logo: String?)

/** Tiempos en epoch **segundos**, como los manda el portal. */
data class LiveProgram(val titulo: String, val inicio: Long, val fin: Long, val sinopsis: String)

/**
 * Lo que el proxy local necesita para hablarle al CDN. Es la excepción consciente al
 * patrón de `ref` opaco del gateway: acá la app sí necesita los datos en claro para
 * armar las cabeceras de cada segmento.
 */
data class LiveSession(
    val cflHost: String,
    val authBase: String,
    val license: String,
    val channel: String,
    val expiresAt: Long,
) {
    /** El `token=<32 hex>` que va dentro de `authBase`; es lo único que la firma necesita. */
    val token: String get() = Regex("token=([0-9A-Fa-f]{32})").find(authBase)?.groupValues?.get(1).orEmpty()
}

data class LiveSignature(val moment: Long, val sign2: String)

/**
 * Cliente del gateway para el canal en vivo: categorías, canales, EPG, resolución de sesión
 * y firma de segmentos por lotes.
 *
 * El JSON se parsea a mano con `org.json`, igual que [ArkivApiClient]. Acá el parseo es
 * DELIBERADAMENTE defensivo (solo `opt*`, nunca `get*`): el portal en vivo ya nos sorprendió
 * más de una vez con campos ausentes o de tipo raro del lado del servidor, y una excepción de
 * parseo no debe tumbar la pantalla — a lo sumo, un canal o programa sale con datos vacíos.
 */
class LiveApi(
    private val baseUrl: () -> String,
    private val apiKey: () -> String,
    private val http: OkHttpClient,
    private val magisAccountId: () -> String? = { null },
) {
    private val json = "application/json".toMediaType()

    private fun pedido(url: String): Request.Builder {
        val b = Request.Builder().url(url).header("X-Arkiv-Key", apiKey())
        magisAccountId()?.takeIf { it.isNotBlank() }?.let { b.header("X-Arkiv-Account", it) }
        return b
    }

    private suspend fun cuerpo(req: Request): JSONObject = withContext(Dispatchers.IO) {
        http.newCall(req).execute().use { r ->
            val texto = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw GatewayException("live: el gateway respondio ${r.code}")
            // Un 200 con cuerpo vacío o no-JSON tampoco debe reventar como JSONException cruda:
            // se traduce a la misma excepción tipada que ya usa el resto del gateway.
            runCatching { JSONObject(texto) }
                .getOrElse { throw GatewayException("live: respuesta no es JSON valido", it) }
        }
    }

    /** Array top-level opcional: si la clave falta o no es un array, no revienta — queda vacío. */
    private fun JSONObject.arrayOrEmpty(clave: String): JSONArray = optJSONArray(clave) ?: JSONArray()

    /** Cada elemento se intenta como objeto; lo que no lo es (basura de tipo raro) se descarta. */
    private fun <T> JSONArray.mapear(f: (JSONObject) -> T): List<T> =
        (0 until length()).mapNotNull { i -> optJSONObject(i)?.let(f) }

    suspend fun categorias(): List<LiveCategory> =
        cuerpo(pedido("${baseUrl()}/v1/live/categories").get().build())
            .arrayOrEmpty("categorias")
            .mapear { LiveCategory(id = it.optInt("id"), nombre = it.optString("nombre")) }

    suspend fun canales(categoria: Int): List<LiveChannel> {
        val url = "${baseUrl()}/v1/live/channels".toHttpUrl().newBuilder()
            .addQueryParameter("category", categoria.toString())
            .build().toString()
        return cuerpo(pedido(url).get().build()).arrayOrEmpty("canales").mapear {
            LiveChannel(
                code = it.optString("code"),
                nombre = it.optString("nombre"),
                numero = it.optInt("numero"),
                // `logo` legítimamente viene null. `optString` de org.json ya devuelve "" para eso,
                // pero además nos cuidamos del clásico donde queda como la CADENA "null" en vez de
                // Kotlin null (típico si alguien hace `.toString()` sobre el sentinel JSONObject.NULL).
                logo = it.optString("logo").takeIf { s -> s.isNotBlank() && s != "null" },
            )
        }
            // Sin `code` el canal es inservible (no hay con qué pedir EPG ni resolver()): se descarta
            // en vez de colar una fila fantasma en la guía.
            .filter { it.code.isNotBlank() }
    }

    suspend fun epg(codes: List<String>): Pair<Map<String, List<LiveProgram>>, List<String>> {
        if (codes.isEmpty()) return emptyMap<String, List<LiveProgram>>() to emptyList()
        val url = "${baseUrl()}/v1/live/epg".toHttpUrl().newBuilder()
            .addQueryParameter("channels", codes.joinToString(","))
            .build().toString()
        val o = cuerpo(pedido(url).get().build())
        val epg = o.optJSONObject("epg") ?: JSONObject()
        val mapa = epg.keys().asSequence().associateWith { code ->
            (epg.optJSONArray(code) ?: JSONArray()).mapear {
                LiveProgram(
                    titulo = it.optString("titulo"),
                    inicio = it.optLong("inicio"),
                    fin = it.optLong("fin"),
                    sinopsis = it.optString("sinopsis"),
                )
            }
        }
        val faltan = o.arrayOrEmpty("missing").let { a -> (0 until a.length()).map { i -> a.optString(i) } }
        return mapa to faltan
    }

    suspend fun resolver(code: String): LiveSession {
        val req = pedido("${baseUrl()}/v1/live/resolve")
            .post(JSONObject(mapOf("channel" to code)).toString().toRequestBody(json)).build()
        val o = cuerpo(req)
        return LiveSession(
            cflHost = o.optString("cflHost"),
            authBase = o.optString("authBase"),
            license = o.optString("license"),
            channel = o.optString("channel"),
            expiresAt = o.optLong("expiresAt"),
        )
    }

    suspend fun firmar(token: String, count: Int, spreadMs: Long): List<LiveSignature> {
        val req = pedido("${baseUrl()}/v1/live/sign").post(
            JSONObject(mapOf("token" to token, "count" to count, "spread_ms" to spreadMs))
                .toString().toRequestBody(json)
        ).build()
        return cuerpo(req).arrayOrEmpty("firmas")
            .mapear { LiveSignature(moment = it.optLong("moment"), sign2 = it.optString("sign2")) }
    }
}
