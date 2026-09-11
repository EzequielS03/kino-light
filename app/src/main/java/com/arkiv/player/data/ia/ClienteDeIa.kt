package com.arkiv.player.data.ia

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Lo que devuelve [ClienteDeIa]: el texto del modelo, o que no se pudo. Nunca una excepción. */
internal sealed interface RespuestaDeIa {
    data class Texto(val texto: String, val modelo: String) : RespuestaDeIa
    data object NoPude : RespuestaDeIa
}

/**
 * La app hablándole a los modelos gratis de Kilo, sin servidor propio y sin llave.
 *
 * **Nunca manda `Authorization`**: el tier anónimo de Kilo depende de que no viaje (así lo usa
 * `llm-libre`, que omite la cabecera cuando la llave está vacía). Por eso no hay ningún secreto que
 * embeber.
 *
 * Descubre los modelos en `/models` ([CatalogoDeKilo]), los prueba en el orden de lo que funcionó en
 * este aparato ([MemoriaDeModelos]) y salta al siguiente si uno falla. Como mucho [MAX_INTENTOS]
 * por pedido: medido en vivo, un modelo gratis tarda ~20 s en contestar. Es solo transporte: no
 * sabe nada de películas.
 */
internal class ClienteDeIa(
    private val baseUrl: String = BASE,
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(TIMEOUT_S, TimeUnit.SECONDS)
        .callTimeout(TIMEOUT_S, TimeUnit.SECONDS)
        .build(),
    private val memoria: MemoriaDeModelos,
    private val ahoraMs: () -> Long = { System.currentTimeMillis() },
) {
    /** Protege [memoria] y el catálogo en memoria: la trivia y "Para ti" pueden preguntar a la vez. */
    private val candado = Mutex()
    private var catalogo: List<ModeloDeKilo> = emptyList()
    private var catalogoTraidoEnMs: Long? = null

    suspend fun preguntar(instruccion: String): RespuestaDeIa = withContext(Dispatchers.IO) {
        val modelos = catalogoVigente()
        for (modelo in candado.withLock { memoria.ordenar(modelos) }.take(MAX_INTENTOS)) {
            // Antes de cada modelo, no a mitad de uno: si se cancela mientras el anterior todavía no
            // contestaba, esto corta el bucle en vez de seguir gastando la cuota anónima probando
            // hasta 3 modelos (~135 s) por un pedido que ya nadie espera (saltar de capítulo, salir
            // del reproductor).
            currentCoroutineContext().ensureActive()
            val texto = intentar(modelo, instruccion) ?: continue
            candado.withLock { memoria.exito(modelo.id) }
            return@withContext RespuestaDeIa.Texto(texto, modelo.id)
        }
        RespuestaDeIa.NoPude
    }

    /** Un intento contra un modelo: su texto, o null tras anotar la falla en [memoria]. */
    private suspend fun intentar(modelo: ModeloDeKilo, instruccion: String): String? {
        val cuerpo = JSONObject()
            .put("model", modelo.id)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", instruccion)))
            .toString()
        val pedido = Request.Builder()
            .url("$baseUrl/chat/completions")
            .post(cuerpo.toRequestBody(JSON))
            .build()
        val falla: Falla = try {
            ejecutar(pedido).use { resp ->
                when {
                    resp.code == 429 -> Falla.Limite(
                        // Se persiste (`MemoriaDeModelos.fallo`): un `Retry-After` de días dejaría el
                        // modelo aparcado días. Una hora es un tope generoso frente a los 10 minutos
                        // por defecto y sigue dejando el modelo disponible el mismo día.
                        resp.header("Retry-After")?.trim()?.toLongOrNull()?.times(1000)?.coerceAtMost(TOPE_ESPERA_LIMITE_MS),
                    )
                    !resp.isSuccessful -> Falla.Servidor
                    else -> {
                        // La lectura del cuerpo queda FUERA del runCatching: si la conexión se cae a
                        // mitad de un 200 (después de que `execute()` ya entregó la respuesta), es un
                        // `IOException` real y tiene que caer en el catch de afuera como Falla.Servidor
                        // (error de red), no como Ilegible (que no castiga).
                        val cuerpoResp = resp.body?.string().orEmpty()
                        val texto = runCatching {
                            JSONObject(cuerpoResp)
                                .getJSONArray("choices").getJSONObject(0)
                                .getJSONObject("message").getString("content")
                        }.getOrNull()?.trim()
                        if (!texto.isNullOrEmpty()) return texto
                        Falla.Ilegible
                    }
                }
            }
        } catch (e: IOException) {
            // Incluye el timeout de 45 s (`InterruptedIOException` es un `IOException`).
            Falla.Servidor
        }
        // Una CancellationException (lanzada por `ejecutar` si la corrutina se cancela mientras
        // espera) no cae acá: no es un IOException, así que sigue de largo hacia arriba sin pasar
        // por esta anotación. Un modelo que nadie esperó no puede quedar castigado por eso.
        Log.w(TAG, "${modelo.id}: $falla")
        candado.withLock { memoria.fallo(modelo.id, falla) }
        return null
    }

    /**
     * Ejecuta [pedido] de forma cancelable: si la corrutina se cancela mientras espera la
     * respuesta, aborta la llamada de OkHttp (`call.cancel()`) en vez de dejarla corriendo sola en
     * un hilo del pool hasta que el servidor conteste o el timeout de 45 s la corte.
     */
    private suspend fun ejecutar(pedido: Request): Response = suspendCancellableCoroutine { cont ->
        val call = http.newCall(pedido)
        cont.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (cont.isActive) cont.resumeWith(Result.failure(e))
            }

            override fun onResponse(call: Call, response: Response) {
                // The handler closes the response when the coroutine was cancelled before or right
                // after this resume, so a late answer never leaks its connection.
                cont.resume(response) { response.close() }
            }
        })
    }

    /** El catálogo de las últimas [VIGENCIA_CATALOGO_MS]; si renovarlo falla, el último que había. */
    private suspend fun catalogoVigente(): List<ModeloDeKilo> {
        candado.withLock {
            val traido = catalogoTraidoEnMs
            if (traido != null && ahoraMs() - traido < VIGENCIA_CATALOGO_MS) return catalogo
        }
        currentCoroutineContext().ensureActive()
        val nuevo = try {
            http.newCall(Request.Builder().url("$baseUrl/models").get().build()).execute().use { resp ->
                if (!resp.isSuccessful) null
                else CatalogoDeKilo.candidatos(JSONObject(resp.body?.string().orEmpty()))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Red caída o un JSON roto (`JSONException`): lo mismo que un 5xx, sigue el último.
            Log.w(TAG, "catálogo: ${e.javaClass.simpleName}: ${e.message}")
            null
        }
        return candado.withLock {
            if (!nuevo.isNullOrEmpty()) {
                catalogo = nuevo
                catalogoTraidoEnMs = ahoraMs()
            }
            catalogo
        }
    }

    internal companion object {
        const val BASE = "https://api.kilo.ai/api/gateway"
        const val MAX_INTENTOS = 3
        const val TIMEOUT_S = 45L
        const val VIGENCIA_CATALOGO_MS = 6 * 60 * 60 * 1000L
        const val TOPE_ESPERA_LIMITE_MS = 60 * 60 * 1000L
        private val JSON = "application/json".toMediaType()
        private const val TAG = "ArkivIA"
    }
}
