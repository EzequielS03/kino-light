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

data class LiveChannel(
    val code: String,
    val nombre: String,
    val numero: Int,
    val logo: String?,
    /**
     * Si el canal viene de una categoría de adultos.
     *
     * Va en el CANAL y no solo en la categoría porque el canal viaja solo hasta el reproductor
     * —zapping, deep link, la propia lista de recientes— y ahí ya no hay categoría a mano. Con la
     * marca encima, la regla de "esto no se anota en el historial" se aplica en el punto de
     * escritura y no depende de por dónde llegó.
     *
     * Por defecto `false`: el que no sabe, no marca. Un gateway viejo que no mande el campo se
     * comporta como antes.
     */
    val adulto: Boolean = false,
)

/** Tiempos en epoch **segundos**, como los manda el portal. */
data class LiveProgram(val titulo: String, val inicio: Long, val fin: Long, val sinopsis: String)

/**
 * Lo que el proxy local necesita para hablarle al CDN. Es la excepción consciente al
 * patrón de `ref` opaco del gateway: acá la app sí necesita los datos en claro para
 * armar las cabeceras de cada segmento.
 */
/**
 * Un CDN donde se puede pedir la señal, con SU propio `authBase`.
 *
 * El token de firma viaja dentro de esa url, así que van juntos: firmar con el token de un CDN
 * contra el host de otro es exactamente el par que el CDN rechaza con 401.
 */
data class CdnDeCanal(val cflHost: String, val authBase: String) {
    /** El `token=<32 hex>` que va dentro de `authBase`; es lo único que la firma necesita. */
    val token: String get() = Regex("token=([0-9A-Fa-f]{32})").find(authBase)?.groupValues?.get(1).orEmpty()
}

data class LiveSession(
    val cflHost: String,
    val authBase: String,
    val license: String,
    /** El código con el que se PIDIÓ el canal: la clave con la que se cachea e invalida su
     *  sesión, y el nombre con el que aparece en los logs. NO sirve para hablarle al CDN. */
    val channel: String,
    val expiresAt: Long,
    /**
     * Cómo se llama la señal EN EL CDN — lo que va en `/live/{...}.m3u8`.
     *
     * No siempre coincide con [channel]. Medido el 2026-08-14: `cyx-RCNHD` se sirve como
     * `cyx-2EF7E10E40C1ac19D6A9F3ED4CD2`, mientras que `cyx_9881490555304164628541864337` es
     * igual en los dos. Usar [channel] acá le pedía al CDN una señal distinta de la que
     * autoriza la licencia que se le manda, y contestaba 401 — el canal quedaba cargando para
     * siempre. Los canales que andaban eran justo aquellos donde los dos coinciden.
     *
     * Por defecto ES [channel], que es el comportamiento de siempre: es lo correcto para los
     * canales donde coinciden y para un gateway que todavía no mande el campo.
     */
    val playCode: String = channel,
    /**
     * TODOS los CDN donde se puede pedir esta señal, en el orden que los dio el portal.
     *
     * Medido el 2026-08-14: el portal devuelve tres entradas de vivo y se usaba solo la primera.
     * Ese día el CDN contestó 401 dos veces y el canal se terminó (`EndReached`) teniendo otro
     * host disponible en la misma respuesta. Con la lista, un rechazo pasa a ser "probá el
     * siguiente" en vez de un canal muerto.
     *
     * Por defecto es el primero solo — lo que había antes — para un gateway que todavía no manda
     * la lista.
     */
    val cdns: List<CdnDeCanal> = listOf(CdnDeCanal(cflHost, authBase)),
) {
    /** El `token=<32 hex>` que va dentro de `authBase`; es lo único que la firma necesita. */
    val token: String get() = Regex("token=([0-9A-Fa-f]{32})").find(authBase)?.groupValues?.get(1).orEmpty()
}

/** Un ítem del catálogo de Magis (una película/video de una sección). */
data class ItemDeCatalogo(
    val id: String,
    val titulo: String,
    val poster: String?,
    val duracionS: Int,
    /**
     * Si vino de una sección de adultos. Va en el ÍTEM y no solo en la sección porque el ítem
     * viaja solo hasta el reproductor, y ahí la regla de "esto no se anota en el historial" tiene
     * que poder aplicarse sin saber de dónde vino. Es lo mismo que se hizo con [LiveChannel].
     */
    val adulto: Boolean = false,
    /**
     * El token con el que se le pide el stream al gateway. Es lo ÚNICO reproducible que trae el
     * ítem: `/v1/resolve` NO toma [id] (que es el contentId del portal), toma este token firmado,
     * que solo el gateway puede acuñar. La app lo trata como opaco y nunca lo interpreta.
     *
     * Vacío = el gateway no lo pudo firmar. Ese ítem se lista igual —se puede ver— pero no se
     * reproduce; ver [reproducible].
     */
    val ref: String = "",
    /** Lo que el portal dice que es: "movie", "teleplay"… Ver [esSerie]. */
    val tipo: String = "movie",
) {
    /**
     * Si hay que pedirle los capítulos antes de reproducir, en vez de reproducirlo derecho.
     *
     * Se pregunta por [tipo] y no abriendo el [ref] a propósito: el ref es opaco para la app, y
     * que siga siéndolo es lo que deja al gateway cambiarle la forma sin publicar un APK.
     */
    val esSerie: Boolean get() = tipo == "teleplay"

    val reproducible: Boolean get() = ref.isNotBlank()
}

/** Una sección del catálogo, con sus primeros ítems (el portal los manda en la misma respuesta). */
data class SeccionDeCatalogo(
    val id: Int,
    val nombre: String,
    val adulto: Boolean,
    val items: List<ItemDeCatalogo>,
)

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
    /**
     * @param incluirAdultos pide también la categoría 18+. El gateway la filtra por DEFECTO, así
     *   que sin esto no viene — ver `CandadoDeAdultos`. Es un candado de control remoto, no una
     *   frontera de seguridad: quien arme el pedido a mano puede ponerlo igual.
     */
    suspend fun categorias(incluirAdultos: Boolean = false): List<LiveCategory>
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
        // Sin sesión/aparato todavía (null o vacío) se omiten las cabeceras -- mandarlas vacías
        // sería peor que no mandarlas (ver ArkivApiClient.pedido).
        personToken()?.takeIf { it.isNotBlank() }?.let { b.header("Authorization", it) }
        deviceToken()?.takeIf { it.isNotBlank() }?.let { b.header("X-Arkiv-Device", it) }
        return b
    }

    private suspend fun cuerpo(req: Request): JSONObject = withContext(Dispatchers.IO) {
        http.newCall(req).execute().use { r ->
            val texto = r.body?.string().orEmpty()
            if (!r.isSuccessful) {
                // El cuerpo YA está leído en `texto` y se descartaba. Ahí viaja el motivo real -- el
                // 2026-08-14 ningún canal abría y el log decía solo "el gateway respondio 502",
                // mientras la respuesta traía que el portal pedía cuenta vinculada. Ver
                // [motivoDelGateway].
                val motivo = motivoDelGateway(texto)
                throw GatewayException(
                    "live: el gateway respondio ${r.code}" + if (motivo.isNotEmpty()) " — $motivo" else "",
                )
            }
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

    override suspend fun categorias(incluirAdultos: Boolean): List<LiveCategory> {
        val url = "${baseUrl()}/v1/live/categories".toHttpUrl().newBuilder()
            .apply { if (incluirAdultos) addQueryParameter("adultos", "1") }
            .build().toString()
        return cuerpo(pedido(url).get().build())
            .arrayOrEmpty("categorias")
            .mapear { LiveCategory(id = it.optInt("id"), nombre = it.optString("nombre")) }
    }

    /**
     * Las secciones de una raíz del catálogo de Magis (`series`, `adultos`), con sus ítems.
     *
     * `adultos` exige [incluirAdultos]; sin eso el gateway responde 409. El candado real es el
     * código por aparato — esto es el default seguro, no una frontera de seguridad.
     */
    suspend fun arbol(raiz: String, incluirAdultos: Boolean = false): List<SeccionDeCatalogo> {
        val url = "${baseUrl()}/v1/live/arbol".toHttpUrl().newBuilder()
            .addQueryParameter("raiz", raiz)
            .apply { if (incluirAdultos) addQueryParameter("adultos", "1") }
            .build().toString()
        return cuerpo(pedido(url).get().build()).arrayOrEmpty("secciones").mapear { s ->
            SeccionDeCatalogo(
                id = s.optInt("id"),
                nombre = s.optString("nombre"),
                adulto = s.optBoolean("adulto", false),
                items = (s.optJSONArray("items") ?: JSONArray()).mapear { i ->
                    ItemDeCatalogo(
                        id = i.optString("id"),
                        titulo = i.optString("titulo"),
                        poster = i.optString("poster").takeIf { p -> p.isNotBlank() && p != "null" },
                        duracionS = i.optInt("duracionS"),
                        adulto = s.optBoolean("adulto", false),
                        ref = i.optString("ref"),
                        // Mismo default que la búsqueda: sin el campo, película.
                        tipo = i.optString("tipo").ifBlank { "movie" },
                    )
                }.filter { it.id.isNotBlank() },
            )
        }.filter { it.nombre.isNotBlank() }
    }

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
                adulto = it.optBoolean("adulto", false),
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
            // Se cae al código del canal si el gateway todavía no lo manda: es lo que se usaba
            // antes, así que un gateway viejo se comporta exactamente como se comportaba.
            playCode = o.optString("playCode").ifBlank { o.optString("channel") },
            cdns = o.optJSONArray("cdns")?.let { a ->
                (0 until a.length()).mapNotNull { i ->
                    a.optJSONObject(i)?.let { c ->
                        val h = c.optString("cflHost")
                        if (h.isBlank()) null else CdnDeCanal(h, c.optString("authBase"))
                    }
                }
            }?.takeIf { it.isNotEmpty() }
                ?: listOf(CdnDeCanal(o.optString("cflHost"), o.optString("authBase"))),
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
