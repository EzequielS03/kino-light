package com.arkiv.player.data.ditu

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Un fallo al hablarle a Caracol.
 *
 * [codigoHttp] y [bloqueo] existen para [FalloDeCaracol], que le traduce el error a la persona: el
 * mensaje es para el log, y adivinar qué pasó leyéndolo es frágil.
 */
internal class DituException(
    mensaje: String,
    causa: Throwable? = null,
    /** El status con que respondió Caracol, si la falla fue un status de error. */
    val codigoHttp: Int? = null,
    /** El motivo de [DituEntitlement.bloqueo], si Caracol no deja ver algo: ya viene escrito para la persona. */
    val bloqueo: String? = null,
) : RuntimeException(mensaje, causa)

/**
 * Una respuesta de Caracol junto con el `playback_token` que traía, o `""` si no traía.
 *
 * El token existe como campo aparte porque NO viene en el cuerpo: viaja en el `Set-Cookie` de la
 * respuesta de `CONTENT/VIDEOURL`, y es lo que después autoriza la petición de licencia Widevine.
 */
internal data class DituRespuesta(val json: JSONObject, val playbackToken: String)

internal interface DituClienteLike {
    suspend fun get(path: String, params: Map<String, String> = emptyMap()): JSONObject
    suspend fun getConToken(path: String): DituRespuesta
}

/**
 * La app hablándole a la API AVS de Caracol Streaming.
 *
 * No hay autenticación de ningún tipo: el contenido gratuito se pide y se sirve. Lo que sí es
 * obligatorio son los tres headers de [CABECERAS] — sin ellos el CDN responde 403 tanto en el
 * manifiesto como en los segmentos, que es un fallo que se lee como "el video no existe".
 */
internal class DituCliente(
    private val baseUrl: String = BASE,
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        // TOPE A LA LLAMADA ENTERA. Los dos de arriba no la acotan: `connectTimeout` vale por cada
        // intento de conexión y `readTimeout` por cada lectura, así que una respuesta que llega a
        // cuentagotas no dispara ninguno. Importa desde que `AppGraph.fuenteDeContenido` es una
        // `FuenteCompuesta`: esa búsqueda emite un solo `Done` cuando terminaron TODAS las fuentes,
        // o sea que cada búsqueda de Magis espera también a Caracol.
        //
        // 15 s y no menos: `DituCatalogo.catalogo` trae el catálogo entero en un solo GET, y en una
        // red lenta como la del televisor podría no alcanzar a llegar en 10.
        .callTimeout(15, TimeUnit.SECONDS)
        .build(),
) : DituClienteLike {

    override suspend fun get(path: String, params: Map<String, String>): JSONObject =
        pedir(path, params).json

    override suspend fun getConToken(path: String): DituRespuesta = pedir(path, emptyMap())

    private suspend fun pedir(path: String, params: Map<String, String>): DituRespuesta =
        withContext(Dispatchers.IO) {
            val url = ("$baseUrl/$path").toHttpUrlOrNull()?.newBuilder()
                ?.apply { params.forEach { (k, v) -> addQueryParameter(k, v) } }
                ?.build()
                ?: throw DituException("URL inválida: $baseUrl/$path")

            val pedido = Request.Builder().url(url).apply {
                CABECERAS.forEach { (k, v) -> header(k, v) }
            }.build()

            val respuesta = runCatching { http.newCall(pedido).execute() }
                .getOrElse { throw DituException("Caracol no responde: ${it.message}", it) }

            respuesta.use {
                if (!it.isSuccessful) throw DituException("Caracol respondió ${it.code} en $path", codigoHttp = it.code)
                val cuerpo = it.body?.string().orEmpty()
                val json = runCatching { JSONObject(cuerpo) }
                    .getOrElse { e -> throw DituException("Caracol devolvió algo que no es JSON en $path", e) }
                // `headers("Set-Cookie")` y no el body: el token viaja como cookie.
                val token = it.headers("Set-Cookie")
                    .firstOrNull { c -> c.startsWith("$COOKIE_TOKEN=") }
                    ?.substringAfter("$COOKIE_TOKEN=")
                    ?.substringBefore(';')
                    .orEmpty()
                DituRespuesta(json, token)
            }
        }

    internal companion object {
        const val BASE = "https://middleware.ditu.caracoltv.com/AGL/1.6/A/ENG/ANDROID/ALL"

        /** La misma base con la ruta de licencia. Existe una variante de ANDROIDTV
         *  (`.../ANDROIDTV/ALL/CONTENT/LICENSE`) que el gateway define pero nunca usa; acá se porta
         *  la que está probada. */
        const val LICENCIA = "$BASE/CONTENT/LICENSE"

        const val COOKIE_TOKEN = "playback_token"

        val CABECERAS = mapOf(
            "restful" to "yes",
            "Accept" to "application/json, text/plain, */*",
            "User-Agent" to "okhttp/4.12.0",
        )
    }
}
