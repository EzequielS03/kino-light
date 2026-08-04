package com.arkiv.player.data.offline

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.transformWhile
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import org.json.JSONObject

private const val POLL_MS = 4000L

/** OkHttpClient para SSE de larga vida: misma config que pocketbase/PocketBaseRealtime.kt --
 * HTTP/1.1 forzado porque Cloudflare resetea SSE por HTTP/2 con RST_STREAM en conexiones largas. */
private fun sseClient(): OkHttpClient = OkHttpClient.Builder()
    .readTimeout(0, TimeUnit.MILLISECONDS)
    .pingInterval(20, TimeUnit.SECONDS)
    .protocols(listOf(Protocol.HTTP_1_1))
    .build()

/** Fuente SSE real: GET /jobs/<id>/events. Requiere X-Api-Key igual que GET /jobs/<id> (ver Task
 * 1) -- a diferencia de un EventSource nativo de navegador, OkHttp SI puede mandar headers custom
 * en una conexión SSE, así que no hace falta ningún camino alternativo. */
private fun defaultSseSource(
    jobId: Long,
    api: ArkivOfflineApi,
    apiKey: () -> String,
    client: OkHttpClient,
): Flow<NucJob> = callbackFlow {
    val base = api.baseUrlResolved()
    val req = Request.Builder().url("$base/jobs/$jobId/events")
        .header("X-Api-Key", apiKey()).build()
    val listener = object : EventSourceListener() {
        override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
            runCatching { api.parseJob(JSONObject(data)) }.getOrNull()?.let { trySend(it) }
        }
    }
    val source = EventSources.createFactory(client).newEventSource(req, listener)
    awaitClose { source.cancel() }
}.flowOn(Dispatchers.IO)

/**
 * Progreso de un job de arkiv-offline casi en tiempo real: mezcla push por SSE (rápido) con un
 * poll de respaldo (sobrevive a que Cloudflare mate la conexión SSE larga -- mismo motivo que
 * documenta pocketbase/PocketBaseRealtime.kt), igual que `CloudTransport.incoming()` mezcla SSE y
 * poll para los comandos remotos.
 *
 * El Flow termina apenas cualquiera de las dos fuentes (SSE o poll) reporta un estado terminal
 * (`done`/`failed`) -- sin esperar a la otra -- y sin repetir esa emisión terminal.
 */
class NucJobEvents(
    private val api: ArkivOfflineApi,
    private val apiKey: () -> String,
    private val client: OkHttpClient = sseClient(),
    private val pollIntervalMs: Long = POLL_MS,
    // Inyectable para tests: la fuente SSE real habla HTTP; el fake del test emite valores
    // controlados directamente. Mismo principio de DI usado en CloudTransport/PocketBaseRealtime.
    private val sseSource: (Long) -> Flow<NucJob> = { jobId -> defaultSseSource(jobId, api, apiKey, client) },
    private val pollSource: suspend (Long) -> NucJob? = { jobId -> api.getJob(jobId) },
) {
    fun observeJob(jobId: Long): Flow<NucJob> {
        val sse = sseSource(jobId)
        val poll = flow {
            while (true) {
                pollSource(jobId)?.let { emit(it) }
                delay(pollIntervalMs)
            }
        }.flowOn(Dispatchers.IO)
        return merge(sse, poll)
            .distinctUntilChanged()
            // takeWhile pero incluyendo el elemento que hace fallar la condición: el estado
            // terminal (done/failed) debe emitirse una vez antes de cerrar el Flow, si no el
            // consumidor nunca lo ve. `transformWhile` es API pública desde kotlinx-coroutines
            // 1.6 (acá 1.8.1) -- evita depender de AbortFlowException, que es interna.
            .transformWhile { job ->
                emit(job)
                job.status != "done" && job.status != "failed"
            }
    }
}
