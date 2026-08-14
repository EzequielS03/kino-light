package com.arkiv.player.remote

import com.arkiv.player.pocketbase.DeviceAuthManager
import com.arkiv.player.pocketbase.PocketBaseClient
import com.arkiv.player.pocketbase.PocketBaseRealtime
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.merge
import org.json.JSONObject

class CloudTransport(
    private val client: PocketBaseClient,
    private val realtime: PocketBaseRealtime,
    private val deviceAuth: DeviceAuthManager,
    private val targetDeviceId: () -> String?,
) : RemoteTransport {

    private val col = "commands"

    // Coalescing de teclas del pad por la nube: no inundar `commands` con teclas muy rápidas.
    @Volatile private var lastKeyMs = 0L

    // Dedup idempotente por id de record: evita doble-ejecución entre el SSE y el poll de respaldo.
    // NO se usa `seq` acá (se resetea si el celu reinicia); esto complementa (no reemplaza) al
    // SeqTracker de RemoteController.
    private val processed = java.util.Collections.newSetFromMap(
        java.util.concurrent.ConcurrentHashMap<String, Boolean>()
    )

    override suspend fun reachable(): Boolean =
        targetDeviceId() != null && deviceAuth.session.value != null

    override suspend fun sendPlay(p: PlayPayload, seq: Long): Boolean =
        send("play", PlayPayloadCodec.encode(p), seq)

    override suspend fun sendSubPrefs(json: String, seq: Long): Boolean = send("subprefs", json, seq)
    override suspend fun sendWebQuality(value: String, seq: Long): Boolean = send("webquality", value, seq)

    override suspend fun sendTransport(type: String, payload: String, seq: Long): Boolean =
        send(type, payload, seq)

    override suspend fun sendKey(key: String, seq: Long): Boolean {
        // Por la nube (LAN no pasa por acá → sigue instantáneo): si dos teclas llegan a menos de
        // COALESCE_MS, se agrupa (se descarta la intermedia) para no inundar dado el ~150-500ms de
        // latencia remota. Devolvemos true para que el router no intente failover por una "falla".
        val now = System.currentTimeMillis()
        if (now - lastKeyMs < COALESCE_MS) return true
        lastKeyMs = now
        return send("key", key, seq)
    }

    private companion object {
        const val COALESCE_MS = 120L

        /**
         * Cada cuánto sondear `commands` como RESPALDO del SSE.
         *
         * Estaba en 3s porque el SSE se caía cada ~125s: Cloudflare corta todo stream proxeado que
         * pase ~100s sin datos y PocketBase no emitía ningún keepalive, así que el realtime no era
         * confiable y este poll cargaba con casi todo. Eso costaba 45.948 peticiones al día — el 74%
         * de todo lo que recibía el servidor.
         *
         * Con el keepalive del lado del servidor (`pb_hooks/sse_keepalive.pb.js`) el SSE ya sobrevive
         * indefinidamente, y este poll vuelve a ser lo que debía ser: la red que cubre el hueco de
         * una reconexión. 15s baja el tráfico 5x; el costo es que si el SSE está caído JUSTO en ese
         * momento, un comando puede tardar hasta 15s en vez de 3s.
         *
         * NO bajarlo de nuevo sin mirar antes si el SSE está sano: el síntoma de "el remoto va
         * lento" casi siempre es el stream cayéndose, no este intervalo.
         */
        const val POLL_MS = 15_000L
    }

    private suspend fun send(type: String, payload: String, seq: Long): Boolean {
        val s = deviceAuth.session.value ?: return false
        val target = targetDeviceId() ?: return false
        return try {
            client.createRecord(
                collection = col,
                fields = mapOf(
                    "accountId" to s.accountId,
                    "targetDeviceId" to target,
                    "fromDeviceId" to s.recordId,
                    "type" to type,
                    "payload" to payload,
                    "seq" to seq,
                    "ack" to false,
                ),
                token = s.token,
            )
            true
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            // Sin este log, un tipo de comando que el select de `commands` no acepta falla en
            // silencio: exactamente así pasaron meses rotos `subprefs` y `webquality` (ver el aviso
            // en docs/pocketbase/collections.md). El `type` en el mensaje es lo que permite
            // diagnosticarlo de un vistazo.
            android.util.Log.w("ArkivRemote", "no se pudo enviar el comando '$type' por la nube: ${e.message}")
            false
        }
    }

    override fun incoming(): Flow<RemoteCommand> {
        val sse = realtime.subscribe(listOf(col)).mapNotNull { ev -> processRecord(ev.record) }
        val poll = flow {
            while (true) {
                val myId = deviceAuth.session.value?.recordId
                val token = deviceAuth.session.value?.token
                if (myId != null && token != null) {
                    val pending = try {
                        client.listRecords(col, "targetDeviceId='$myId' && ack=false", token)
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        emptyList()
                    }
                    // Ordenar el lote por `seq` antes de ejecutarlo. PocketBase lista ordenando por
                    // id y los ids son aleatorios, así que dos comandos de la misma ventana de 3s
                    // (pausa y +10, con el SSE caído) llegaban en orden arbitrario y se aplicaban al
                    // revés. Se ordena acá, en el celu, y no con un `sort` en la consulta: así no
                    // dependemos de qué orden decida el server ni de la paginación del cliente.
                    for (rec in pending.sortedBy { it.optLong("seq", 0) }) {
                        processRecord(rec)?.let { emit(it) }
                    }
                }
                delay(POLL_MS)
            }
        }.flowOn(kotlinx.coroutines.Dispatchers.IO)
        return merge(sse, poll)
    }

    // Común a SSE y poll: filtra por destinatario, deduplica por id de record (idempotencia),
    // marca ack=true best-effort y mapea el record al RemoteCommand según su type.
    private suspend fun processRecord(rec: JSONObject): RemoteCommand? {
        val myId = deviceAuth.session.value?.recordId ?: return null
        if (rec.optString("targetDeviceId") != myId) return null
        // Ya consumido: salta el eco del evento UPDATE (que el SSE recibe al marcar ack=true).
        if (rec.optBoolean("ack", false)) return null
        val recId = rec.optString("id")
        if (recId.isNotBlank() && !processed.add(recId)) return null
        val type = rec.optString("type")
        val seq = rec.optLong("seq", 0)
        val payload = rec.optString("payload")
        // Best-effort: marcar consumido para no re-ejecutar.
        val token = deviceAuth.session.value?.token
        if (recId.isNotBlank() && token != null) {
            // El ack es best-effort y sirve sobre todo para limpieza: evita que el poll re-entregue
            // el mismo record; el dedup real ante SSE+poll lo hace `processed` (por id de record).
            try {
                client.updateRecord(col, recId, mapOf("ack" to true), token)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // best-effort: ignorar fallos del ack
            }
        }
        if (type == "play") android.util.Log.i("ArkivRemote", "<- recibido PLAY por NUBE (seq=$seq)")
        return when (type) {
            "play" -> RemoteCommand("play", PlayPayloadCodec.decode(payload), null, seq)
            "key" -> RemoteCommand("key", null, payload, seq)
            "subprefs" -> RemoteCommand("subprefs", null, payload, seq)
            "webquality" -> RemoteCommand("webquality", null, payload, seq)
            in TransportCommandCodec.TYPES -> RemoteCommand(type, null, payload, seq)
            else -> null
        }
    }
}
