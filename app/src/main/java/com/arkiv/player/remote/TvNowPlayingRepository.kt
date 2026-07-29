package com.arkiv.player.remote

import com.arkiv.player.pocketbase.DeviceAuthManager
import com.arkiv.player.pocketbase.PocketBaseClient
import com.arkiv.player.pocketbase.PocketBaseConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * (Solo celu) Estado de reproducción del TV, para el miniplayer.
 *
 * Poll deliberado en vez de SSE: con el reloj extrapolado un refresco cada 3s es invisible en la
 * barra de progreso, y los streams largos por Cloudflare ya demostraron ser el punto frágil del
 * sistema. La latencia que sí se percibe —la de los botones— se resuelve con UI optimista.
 *
 * Solo hace poll con [setActive] en true (app en primer plano) y con un TV pareado; si no, duerme.
 */
class TvNowPlayingRepository(
    private val client: PocketBaseClient,
    private val deviceAuth: DeviceAuthManager,
    private val tvPaired: () -> Boolean,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow<TvSnapshot?>(null)
    val state: StateFlow<TvSnapshot?> = _state.asStateFlow()

    private val active = MutableStateFlow(false)

    fun setActive(value: Boolean) {
        active.value = value
        if (value) scope.launch { refreshNow() }
    }

    fun start() {
        scope.launch {
            while (true) {
                if (active.value) refreshNow()
                delay(POLL_MS)
            }
        }
    }

    suspend fun refreshNow() {
        // Sin TV pareado no hay nada que mirar: ni una petición cada 3s.
        if (!tvPaired()) {
            _state.value = null
            return
        }
        val s = deviceAuth.session.value ?: return
        val record = try {
            TvDeviceSelector.pick(
                client.listRecords(PocketBaseConfig.COLLECTION_DEVICES, "kind='tv'", s.token),
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.w("ArkivRemote", "nowPlaying: no se pudo refrescar el estado del TV: ${e.message}")
            null
        } ?: return
        // Una foto con el `at` del TV claramente viejo NO es "algo reproduciéndose": es la lápida que
        // dejó un TV que se apagó a mitad de capítulo. Ver isDeadByTvClock.
        val foto = NowPlayingCodec.decode(record.optString("nowPlaying"))
            ?.takeUnless { isDeadByTvClock(it.at, System.currentTimeMillis()) }
        // Re-chequear el pareo: unlinkTv() pudo correr mientras esta petición de red estaba en vuelo.
        // Sin este chequeo, una respuesta tardía pintaría el miniplayer con un TV que el usuario ya
        // desvinculó.
        if (!tvPaired()) {
            _state.value = null
            return
        }
        // Solo re-sellar receivedAtMs si la foto decodificada cambió de verdad. Si es la MISMA que ya
        // teníamos, el TV dejó de publicar (crasheó, se apagó, perdió red) y NO hay que refrescar el
        // timestamp: así receivedAtMs sigue envejeciendo con el snapshot viejo y el umbral de stale
        // del consumidor termina apagando la barra, en vez de mostrarla congelada como si el TV
        // siguiera vivo.
        if (foto != null && foto == _state.value?.nowPlaying) return
        _state.value = foto?.let { TvSnapshot(it, System.currentTimeMillis()) }
    }

    internal companion object {
        const val POLL_MS = 3_000L

        /** Antigüedad máxima del `at` del TV para seguir creyendo que hay algo reproduciéndose. */
        const val MAX_AGE_MS = 2 * 60_000L

        /**
         * ¿La foto la escribió un TV que ya no está vivo?
         *
         * `devices.nowPlaying` solo se limpia con una transición que el publisher alcance a ver
         * corriendo: un Fire Stick apagado a mitad de capítulo deja el campo poblado para siempre. Y
         * `receivedAtMs` no sirve para detectarlo, porque se sella en el primer poll tras arrancar el
         * proceso: al abrir la app con el TV apagado, una foto de hace un mes parece recién nacida y
         * la barra sale entera —reloj extrapolando, controles activos— hasta el umbral de ocultado.
         * Android mata apps en background todo el tiempo, así que ese primer poll es la mayoría de
         * los arranques.
         *
         * Sí, acá SE comparan los relojes de los dos dispositivos, justo lo que el diseño prohíbe
         * para la extrapolación. No es lo mismo: allá la deriva se vería segundo a segundo en la
         * barra de progreso; acá es una cota de vida de 2 minutos entre dos Android con hora de red,
         * donde el desfase es de segundos. No "arreglar" esto volviendo a `receivedAtMs`.
         *
         * Defensivo a propósito: un `at` vacío o no parseable NO descarta la foto (si el formato
         * cambiara alguna vez, el miniplayer entero dejaría de funcionar). Solo un timestamp
         * parseado y claramente viejo cuenta como TV muerto.
         */
        internal fun isDeadByTvClock(at: String, nowMs: Long): Boolean {
            val t = runCatching { java.time.Instant.parse(at) }.getOrNull() ?: return false
            return nowMs - t.toEpochMilli() > MAX_AGE_MS
        }
    }
}
