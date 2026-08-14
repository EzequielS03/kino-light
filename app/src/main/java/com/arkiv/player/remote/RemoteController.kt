package com.arkiv.player.remote

import com.arkiv.player.data.SettingsStore
import com.arkiv.player.pocketbase.DeviceAuthManager
import com.arkiv.player.pocketbase.PocketBaseClient
import com.arkiv.player.pocketbase.PocketBaseConfig
import com.arkiv.player.pocketbase.PocketBaseRealtime
import com.arkiv.player.sync.SyncManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.launch

/**
 * API de alto nivel para el control remoto. El celu llama sendPlay/sendKey (enrutados LAN/nube);
 * el TV consume incomingPlay/incomingKeys (merge de ambos transportes, con idempotencia por seq).
 */
class RemoteController(
    private val sync: SyncManager,
    private val client: PocketBaseClient,
    realtime: PocketBaseRealtime,
    private val deviceAuth: DeviceAuthManager,
    private val settings: SettingsStore,
    private val scope: CoroutineScope,
) {
    @Volatile private var cachedTvId: String? = null
    private val seq = SeqTracker()
    private val inSeq = SeqTracker()

    private val lan = LanTransport(sync)
    private val cloud = CloudTransport(client, realtime, deviceAuth, { cachedTvId })
    private val router = TransportRouter(lan, cloud)

    // Suscripción única a router.incoming() (evita abrir dos SSE si se colectan
    // incomingPlay e incomingKeys por separado).
    private val incoming: Flow<RemoteCommand> =
        router.incoming().shareIn(scope, SharingStarted.Eagerly, 0)

    // ¿Hay un TV pareado en mi cuenta? Reactivo, para que la UII (popup, botón) sepa que se puede
    // "enviar al TV" incluso estando en otra red (no solo por descubrimiento LAN).
    private val _tvPaired = MutableStateFlow(false)
    val tvPaired: StateFlow<Boolean> = _tvPaired.asStateFlow()

    init {
        // Resolver proactivamente el device tv una vez haya sesión, para exponer tvPaired.
        // El reintento se ESPACIA (ver backoffDeResolucionDeTv): sin TV pareado este bucle no
        // termina nunca, y a intervalo fijo pedía la lista de devices cada 5s las 24 horas.
        scope.launch {
            val backoff = backoffDeResolucionDeTv()
            var intento = 0
            while (cachedTvId == null) {
                if (deviceAuth.session.value != null) resolveTvId()
                if (cachedTvId != null) break
                delay(backoff.nextDelayMs(intento++))
            }
        }
    }

    /** Busca (y cachea) el device kind=tv de mi cuenta. */
    private suspend fun resolveTvId(): String? {
        cachedTvId?.let { return it }
        val s = deviceAuth.session.value ?: return null
        val id = runCatching {
            // Preferir el TV online y visto más recientemente (evita apuntar a un TV viejo/fantasma
            // cuando hay varios kind=tv en la cuenta).
            TvDeviceSelector.pick(
                client.listRecords(PocketBaseConfig.COLLECTION_DEVICES, "kind='tv'", s.token),
            )?.getString("id")
        }.getOrNull()
        cachedTvId = id
        _tvPaired.value = id != null
        // Reinstalar la app borra el flag local pero no el device de la cuenta: si acá aparece una
        // TV, es que este usuario sí pareó → recuperar el flag en vez de dejarlo apagado.
        if (id != null) settings.setTvLinked(true)
        return id
    }

    suspend fun tvAvailable(): Boolean {
        resolveTvId()
        return lan.reachable() || (cachedTvId != null && deviceAuth.session.value != null)
    }

    /**
     * Desvincular: borra el device kind=tv de la cuenta y limpia el estado local de pareo.
     * El borrado remoto es best-effort (puede no haber sesión o red), pero el estado local se
     * limpia siempre: si el usuario pulsa "Desvincular" sin internet, la app debe quedar como
     * no pareada igual, no ignorar el gesto.
     */
    suspend fun unlinkTv() {
        val s = deviceAuth.session.value
        val id = if (s != null) resolveTvId() else null
        if (s != null && id != null) {
            runCatching { client.deleteRecord(PocketBaseConfig.COLLECTION_DEVICES, id, s.token) }
        }
        cachedTvId = null
        _tvPaired.value = false
        settings.setTvLinked(false)
    }

    suspend fun sendPlay(p: PlayPayload): Boolean { resolveTvId(); return router.sendPlay(p, seq.next()) }

    suspend fun sendKey(key: String): Boolean { resolveTvId(); return router.sendKey(key, seq.next()) }

    /** Sincroniza las preferencias de subtítulos (idioma + estilo) al otro dispositivo. */
    suspend fun sendSubtitlePrefs(json: String): Boolean { resolveTvId(); return router.sendSubPrefs(json, seq.next()) }

    /** (Receptor) Preferencias de subtítulos entrantes (JSON) para aplicar localmente. */
    val incomingSubPrefs: Flow<String> = incoming.mapNotNull { cmd ->
        if (cmd.type == "subprefs") cmd.key else null
    }

    /** Sincroniza la calidad de fuentes web (AUTO/SD/HD/MAX) al otro dispositivo. */
    suspend fun sendWebQuality(value: String): Boolean { resolveTvId(); return router.sendWebQuality(value, seq.next()) }

    /** (Receptor) Calidad web entrante (nombre del enum) para aplicar localmente. */
    val incomingWebQuality: Flow<String> = incoming.mapNotNull { cmd ->
        if (cmd.type == "webquality") cmd.key else null
    }

    /** Envía un comando de transporte del miniplayer al TV. */
    suspend fun sendTransport(cmd: TransportCommand): Boolean {
        resolveTvId()
        return router.sendTransport(
            TransportCommandCodec.typeOf(cmd),
            TransportCommandCodec.payloadOf(cmd),
            seq.next(),
        )
    }

    /**
     * (TV) Comandos de transporte entrantes.
     *
     * A propósito SIN el filtro de `inSeq`, a diferencia de play/key: `inSeq` es una marca de agua
     * monótona compartida por los tres flujos, así que cualquier comando que llegue con un `seq`
     * menor o igual al máximo ya visto se descarta **para siempre**. Un lote del poll de respaldo
     * (pausa + seek en la misma ventana de 3s) puede desordenarse en el camino y ahí perder una
     * pausa de verdad. Acá no compra nada: `CloudTransport.processed` ya garantiza exactamente-una
     * ejecución por id de record para la nube, y por LAN estos comandos no existen (los de LAN
     * viajan con `seq == 0`, que el filtro ni mira).
     */
    val incomingTransport: Flow<TransportCommand> = incoming.mapNotNull { cmd ->
        if (cmd.type !in TransportCommandCodec.TYPES) return@mapNotNull null
        TransportCommandCodec.parse(cmd.type, cmd.key)
    }

    /** (TV) Plays entrantes, deduplicados por seq. */
    val incomingPlay: Flow<PlayPayload> = incoming.mapNotNull { cmd ->
        if (cmd.type != "play" || cmd.play == null) return@mapNotNull null
        if (cmd.seq != 0L && !inSeq.isFresh(cmd.seq)) return@mapNotNull null
        cmd.play
    }

    /** (TV) Teclas entrantes como keycodes de Android. Acepta nombres (LAN) o keycodes (nube). */
    val incomingKeys: Flow<Int> = incoming.mapNotNull { cmd ->
        if (cmd.type != "key" || cmd.key == null) return@mapNotNull null
        if (cmd.seq != 0L && !inSeq.isFresh(cmd.seq)) return@mapNotNull null
        cmd.key.toIntOrNull() ?: SyncManager.keyCodeFor(cmd.key)
    }

    internal companion object {
        /**
         * Cada cuánto reintentar la resolución del TV de la cuenta mientras no aparezca ninguno.
         *
         * Era un intervalo FIJO de 5s, y como el bucle solo termina al encontrar un TV, un aparato
         * sin TV pareado pedía `devices?filter=kind='tv'` cada 5s mientras viviera el proceso. En
         * los logs de PocketBase eso se veía como 694 peticiones por hora sostenidas de madrugada,
         * sin nadie usando nada, contra un server que ya está en swap.
         *
         * Arranca rápido (el caso frecuente es "la sesión todavía no estaba lista") y se estira
         * hasta 10 min. Que tarde en notar una TV nueva no importa: el pareo actualiza el estado por
         * su cuenta, y `tvAvailable()` fuerza una resolución cuando la UI de verdad la necesita.
         */
        fun backoffDeResolucionDeTv(random: java.util.Random = java.util.Random()) =
            com.arkiv.player.pocketbase.Backoff(baseMs = 5_000, maxMs = 600_000, random = random)
    }
}
