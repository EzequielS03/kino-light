package com.arkiv.player.pocketbase

import android.util.Log
import java.io.IOException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import org.json.JSONArray
import org.json.JSONObject

private const val TAG = "ArkivPB"

/** Evento realtime de PocketBase: topic suscrito, acción (`create`/`update`/`delete`) y registro afectado. */
data class RealtimeEvent(val topic: String, val action: String, val record: JSONObject)

/**
 * OkHttpClient apto para SSE de larga vida: readTimeout 0 (el stream no se cierra por inactividad,
 * causa del bucle "SSE caído: timeout") y ping de keepalive para detectar conexiones muertas.
 */
private fun sseClient(): OkHttpClient = OkHttpClient.Builder()
    .readTimeout(0, java.util.concurrent.TimeUnit.MILLISECONDS)
    .pingInterval(20, java.util.concurrent.TimeUnit.SECONDS)
    // Forzar HTTP/1.1: detrás de Cloudflare, el SSE por HTTP/2 se resetea con RST_STREAM
    // (INTERNAL_ERROR) en conexiones de larga vida. HTTP/1.1 (chunked) es estable a través de CF.
    .protocols(listOf(okhttp3.Protocol.HTTP_1_1))
    .build()

/**
 * Cliente realtime de PocketBase vía SSE (`/api/realtime`).
 *
 * Protocolo: se abre una conexión SSE; el primer evento (`PB_CONNECT`) trae `{clientId}`,
 * con el cual se hace un POST a `/api/realtime` para fijar las suscripciones a `topics`.
 * Los eventos siguientes llegan con el topic como nombre de evento y `{action, record}` como data.
 * Ante una caída de la conexión, reconecta automáticamente con backoff exponencial.
 */
class PocketBaseRealtime(
    private val baseUrl: String = PocketBaseConfig.BASE_URL,
    private val client: OkHttpClient = sseClient(),
    private val token: () -> String? = { null },
) {
    private val backoff = Backoff()

    fun subscribe(topics: List<String>): Flow<RealtimeEvent> = callbackFlow {
        var attempt = 0
        var source: EventSource? = null
        lateinit var connect: () -> Unit

        val listener = object : EventSourceListener() {
            override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                if (type == "PB_CONNECT") {
                    val clientId = runCatching { JSONObject(data).getString("clientId") }.getOrNull()
                    if (clientId != null) {
                        setSubscriptions(clientId, topics)
                        attempt = 0
                    }
                    return
                }
                val obj = runCatching { JSONObject(data) }.getOrNull() ?: return
                val record = obj.optJSONObject("record") ?: return
                trySend(RealtimeEvent(topic = type.orEmpty(), action = obj.optString("action"), record = record))
            }

            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                Log.w(TAG, "SSE caído: ${t?.message}; reintentando")
                launch {
                    source?.cancel()
                    delay(backoff.nextDelayMs(attempt++))
                    connect()
                }
            }
        }

        connect = {
            launch {
                // La sesión se bootstrapea de forma asíncrona al arrancar; si conectamos el SSE
                // SIN token queda sin autenticar y las reglas por-cuenta (p. ej. `commands`) no
                // entregan eventos. Esperar a que haya token (hasta ~10s) antes de abrir el stream.
                var waited = 0
                while (token() == null && waited < 100) {
                    delay(100); waited++
                }
                val reqBuilder = Request.Builder().url("$baseUrl/api/realtime")
                token()?.let { reqBuilder.header("Authorization", it) }
                Log.i(TAG, "SSE conectando (autenticado=${token() != null})")
                source = EventSources.createFactory(client).newEventSource(reqBuilder.build(), listener)
            }
        }
        connect()

        awaitClose { source?.cancel() }
    }

    private fun setSubscriptions(clientId: String, topics: List<String>) {
        val body = JSONObject()
            .put("clientId", clientId)
            .put("subscriptions", JSONArray(topics))
            .toString()
            .toRequestBody("application/json".toMediaType())
        val reqBuilder = Request.Builder().url("$baseUrl/api/realtime").post(body)
        token()?.let { reqBuilder.header("Authorization", it) }
        client.newCall(reqBuilder.build()).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.w(TAG, "no se pudieron fijar suscripciones: ${e.message}")
            }

            override fun onResponse(call: Call, response: Response) {
                response.close()
            }
        })
    }
}
