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
 * Lo que [com.arkiv.player.ui.live.LiveViewModel] necesita del gateway -- angosta a propósito:
 * NO los cinco métodos de [LiveApi]. `resolver`/`firmar` son de
 * [com.arkiv.player.ui.live.LiveController] (resolución de sesión y firma de segmentos), un
 * consumidor completamente distinto con su propio ciclo de vida; meterlos acá solo ataría esta
 * interfaz a un consumidor que no la usa.
 *
 * [LiveApi] la implementa en producción. En tests, un doble liviano la implementa directo (ver
 * `FakeLiveApi` en `LiveViewModelTest.kt`) sin heredar de la clase concreta ni tocar red: la
 * alternativa evaluada -abrir `LiveApi` (`open class` + `open fun`)- se descartó porque es la
 * ÚNICA clase abierta de todo `app/src/main/java` sin ningún otro motivo arquitectónico
 * (se construye en un solo lugar, `AppGraph.kt`), y el propio [LiveController] ya resuelve este
 * mismo problema para sus dependencias con funciones inyectadas en vez de herencia. Acá se
 * prefirió una interfaz angosta -no funciones sueltas como en `LiveController`- porque las tres
 * operaciones se consumen SIEMPRE juntas desde el mismo cliente concreto (no son dependencias de
 * fuentes distintas como `resolver` y `urlPara` en `LiveController`): agruparlas mantiene el
 * call site de producción sin cambios (`LiveViewModel(graph.liveApi, ...)` sigue compilando tal
 * cual, porque `LiveApi` es un subtipo) y evita esparcir tres parámetros función independientes
 * donde uno solo, cohesivo, alcanza.
 */
interface LiveCatalogGateway {
    suspend fun categorias(): List<LiveCategory>
    suspend fun canales(categoria: Int): List<LiveChannel>
    suspend fun epg(codes: List<String>): Pair<Map<String, List<LiveProgram>>, List<String>>
}

/**
 * Cliente del gateway para el canal en vivo: categorías, canales, EPG, resolución de sesión
 * y firma de segmentos por lotes.
 *
 * El JSON se parsea a mano con `org.json`, igual que [ArkivApiClient]. Acá el parseo es
 * DELIBERADAMENTE defensivo (solo `opt*`, nunca `get*`): el portal en vivo ya nos sorprendió
 * más de una vez con campos ausentes o de tipo raro del lado del servidor, y una excepción de
 * parseo no debe tumbar la pantalla — a lo sumo, un canal o programa sale con datos vacíos.
 *
 * [resolver] es la excepción consciente a esa regla: la `LiveSession` que arma termina en manos
 * del proxy local que le habla al CDN sin revalidarla, así que un dato esencial vacío se valida
 * acá y se rechaza con [GatewayException] mientras todavía tenemos la respuesta cruda del
 * gateway — fallar cerca, no como un 403 opaco del CDN varios saltos después.
 *
 * Clase FINAL a propósito (ver KDoc de [LiveCatalogGateway] sobre por qué no se abrió para
 * testear): el único camino de producción es este constructor, vía `AppGraph`.
 */
class LiveApi(
    private val baseUrl: () -> String,
    private val http: OkHttpClient,
    private val magisAccountId: () -> String? = { null },
    /** Token de sesión de la PERSONA, misma fuente que ya usa `CuentaApi` para `Authorization`
     *  (`SesionDePersona.token()`). Task 8 (Paso 3): `X-Arkiv-Key` salió del todo -- ver KDoc del
     *  mismo parámetro en `ArkivApiClient`. */
    private val personToken: () -> String? = { null },
    /** Token del APARATO que llama, misma fuente que ya usa `CuentaApi` para `X-Arkiv-Device`
     *  (`DeviceAuthManager.session.value?.token`): `require_sesion` exige las dos juntas. */
    private val deviceToken: () -> String? = { null },
) : LiveCatalogGateway {
    private val json = "application/json".toMediaType()

    private fun pedido(url: String): Request.Builder {
        val b = Request.Builder().url(url)
        magisAccountId()?.takeIf { it.isNotBlank() }?.let { b.header("X-Arkiv-Account", it) }
        // Sin sesión/aparato todavía (null o vacío) se omiten las cabeceras -- mandarlas vacías
        // sería peor que no mandarlas (ver ArkivApiClient.pedido).
        personToken()?.takeIf { it.isNotBlank() }?.let { b.header("Authorization", it) }
        deviceToken()?.takeIf { it.isNotBlank() }?.let { b.header("X-Arkiv-Device", it) }
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

    override suspend fun categorias(): List<LiveCategory> =
        cuerpo(pedido("${baseUrl()}/v1/live/categories").get().build())
            .arrayOrEmpty("categorias")
            .mapear { LiveCategory(id = it.optInt("id"), nombre = it.optString("nombre")) }

    override suspend fun canales(categoria: Int): List<LiveChannel> {
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

    override suspend fun epg(codes: List<String>): Pair<Map<String, List<LiveProgram>>, List<String>> {
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
        val sesion = LiveSession(
            cflHost = o.optString("cflHost"),
            authBase = o.optString("authBase"),
            license = o.optString("license"),
            channel = o.optString("channel"),
            expiresAt = o.optLong("expiresAt"),
        )
        // A diferencia del resto de LiveApi, ACÁ no alcanza con degradar a "" y seguir: una
        // LiveSession con cflHost/authBase/token/license vacío es la que LiveHlsProxy (Tarea 8)
        // usa tal cual contra el CDN real, sin volver a chequearla — el fallo aparecería recién
        // como un 403/400 opaco del CDN, lejos de acá y sin decir qué faltaba. Este es el único
        // punto con la respuesta cruda a mano, así que es donde hay que fallar claro. `license`
        // puede venir "" de forma legítima del lado del portal (el propio gateway lo tolera:
        // ver MagisLive.resolver en el server), pero el CDN la exige igual, así que del lado de
        // la app es tan esencial como cflHost/authBase.
        if (sesion.cflHost.isBlank()) throw GatewayException("live: resolve sin cflHost para $code")
        if (sesion.authBase.isBlank()) throw GatewayException("live: resolve sin authBase para $code")
        if (sesion.license.isBlank()) throw GatewayException("live: resolve sin license para $code")
        if (sesion.token.isBlank()) {
            throw GatewayException("live: authBase de $code sin token valido (se esperaba token=<32 hex>)")
        }
        return sesion
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
