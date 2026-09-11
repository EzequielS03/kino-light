package com.arkiv.player.cast

import androidx.media3.cast.CastPlayer
import androidx.media3.cast.SessionAvailabilityListener
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.arkiv.player.data.ArkivRepository
import com.google.android.gms.cast.framework.CastContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Dueño del [CastPlayer] y de la sesión de Chromecast, con vida de aplicación.
 *
 * Vive acá y no en el composable del reproductor porque `CastPlayer.release()` llama a
 * `SessionManager.endCurrentSession(false)` (verificado en el bytecode de media3-cast 1.5.1): al
 * liberarlo se corta el casteo. Mientras estuvo dentro de la pantalla, salir del reproductor
 * mataba la sesión.
 *
 * Como el CastPlayer se construye UNA sola vez, el listener recibe todas las sesiones y desaparece
 * de paso un problema viejo: media3 no re-dispara `onCastSessionAvailable` para una sesión que ya
 * estaba abierta al construirlo.
 */
class CastSessionManager(
    private val castContext: CastContext,
    private val repository: ArkivRepository,
    private val scope: CoroutineScope,
    /** Se llama al terminar la sesión. Sirve para apagar el transcodificador: si queda vivo, sigue
     *  gastando CPU y reteniendo el puerto aunque ya no haya nadie del otro lado. */
    private val onSessionEnded: () -> Unit = {},
    /**
     * Espera a que el origen esté servible ANTES de dárselo al receptor. Devuelve false si no llegó
     * a estarlo. Existe porque un receptor al que se le da una URL que todavía no responde se va a
     * idle y no reintenta: hay que no adelantarse, no hay forma de corregirlo después.
     */
    private val awaitSourceReady: suspend (CastRequest) -> Boolean = { true },
) {
    // Falla rápido y con causa explícita si algo construye esto fuera del hilo principal, en vez de
    // un crash oscuro dentro del SDK de Cast (CastPlayer/CastContext lo exigen, ver clase doc).
    init {
        check(android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            "CastSessionManager debe construirse en el hilo principal: CastPlayer y CastContext lo exigen"
        }
    }

    val player: CastPlayer = CastPlayer(castContext)

    private val _casting = MutableStateFlow(false)
    val casting: StateFlow<Boolean> = _casting.asStateFlow()

    /** Lo último que se pidió castear; se (re)carga en cuanto haya sesión. */
    @Volatile private var pending: CastRequest? = null

    /**
     * Generación de [pending]: sube con cada [setMedia] genuinamente NUEVO, nunca con una
     * reconexión que recarga el mismo pedido (el listener de `onCastSessionAvailable`, más abajo,
     * llama a `load()` directo sin pasar por acá). Sin esto, re-castear el MISMO episodio no se
     * distingue de "sigue sonando lo de antes" y el Chromecast no podría recuperar la barra en ese
     * caso (ver MarcaFuente/sellarMarca en NowPlayingCoordinator).
     */
    @Volatile private var generacion = 0

    /**
     * Desfase y duración real de lo que se está casteando.
     *
     * La UI los necesita porque con el stream transcodificado el receptor cuenta desde cero y manda
     * `TIME_UNSET` como duración: sin traducir esos números, la barra queda vacía y al desconectar el
     * reproductor local reanuda en el lugar equivocado.
     */
    val baseOffsetMs: Long get() = pending?.baseOffsetMs ?: 0L
    val knownDurationMs: Long get() = pending?.knownDurationMs ?: 0L

    /** Lo que se le pidió al receptor: de acá salen título, carátula y episodio para la barra. */
    val currentRequest: CastRequest? get() = pending

    /** Generación de [currentRequest] — ver el comentario junto a `generacion`. */
    val mediaGeneracion: Int get() = generacion

    /**
     * Diagnóstico del receptor. Sin esto, una TV que RECHAZA el medio (contenedor o códec que no
     * soporta) falla en silencio absoluto: en el celu no pasa nada y en la TV no se ve ni se oye,
     * sin una sola pista de por qué. Es el punto ciego que ya nos hizo diagnosticar mal una vez.
     *
     * Vive acá y no en la pantalla porque el CastPlayer es de la app: así los logs siguen saliendo
     * cuando se castea con el reproductor cerrado, que es justo lo que este feature habilitó.
     *
     * Se declara ANTES del `init` a propósito: las propiedades se inicializan en orden de
     * declaración, así que un `val` puesto después quedaría en null al engancharlo.
     */
    private val diagnostics = object : androidx.media3.common.Player.Listener {
        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
            android.util.Log.e(
                TAG,
                "el receptor falló: code=${error.errorCode} (${error.errorCodeName}) · ${error.message}",
                error,
            )
        }

        override fun onPlaybackStateChanged(state: Int) {
            android.util.Log.i(TAG, "estado del receptor: $state (1=idle 2=buffering 3=listo 4=terminado)")
        }

        override fun onIsPlayingChanged(playing: Boolean) {
            android.util.Log.i(
                TAG,
                "receptor reproduciendo=$playing · pos=${player.currentPosition}ms · dur=${player.duration}ms",
            )
        }

        /**
         * Lo más informativo para "se ve pero no suena": qué pistas ACEPTÓ el receptor. Si la TV
         * descarta el audio por códec (DTS suele no estar), acá se ve la de video seleccionada y la
         * de audio ausente o no soportada, sin que salte ningún error.
         */
        override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
            if (tracks.groups.isEmpty()) {
                android.util.Log.w(TAG, "el receptor no reporta NINGUNA pista")
                return
            }
            tracks.groups.forEach { g ->
                for (i in 0 until g.length) {
                    val f = g.getTrackFormat(i)
                    android.util.Log.i(
                        TAG,
                        "pista tipo=${g.type} codec=${f.codecs} mime=${f.sampleMimeType} " +
                            "idioma=${f.language} soportada=${g.isTrackSupported(i)} elegida=${g.isTrackSelected(i)}",
                    )
                }
            }
        }
    }

    init {
        player.addListener(diagnostics)
        player.setSessionAvailabilityListener(object : SessionAvailabilityListener {
            override fun onCastSessionAvailable() {
                _casting.value = true
                pending?.let { scope.launch { load(it) } }
            }

            override fun onCastSessionUnavailable() {
                _casting.value = false
                runCatching { onSessionEnded() }
                    .onFailure { android.util.Log.w(TAG, "fallo al cerrar la sesión: ${it.message}") }
            }
        })
        // El único caso que el listener NO cubre: arrancar la app con una sesión ya viva (se mató y
        // se reabrió casteando). Ocurrió antes de que existiéramos, así que se adopta a mano.
        if (runCatching { castContext.sessionManager.currentCastSession?.isConnected }.getOrNull() == true) {
            _casting.value = true
        }
        startProgressLoop()
    }

    /** Pide castear esto. Si ya hay sesión se carga ya; si no, queda pendiente para cuando la haya. */
    fun setMedia(request: CastRequest) {
        generacion++
        pending = request
        if (_casting.value) scope.launch { load(request) }
    }

    /**
     * ¿La última sesión terminó porque el usuario pulsó "parar"?
     *
     * Existe porque el camino de desconexión reanuda la reproducción local donde llegó el receptor
     * —lo correcto al desconectar desde el botón de cast— pero sería absurdo tras pulsar parar: el
     * usuario pidió silencio y el teléfono se pondría a reproducir. Se consume una sola vez.
     */
    @Volatile private var paradaIntencionalAtMs = 0L

    fun stopIntentionally() {
        paradaIntencionalAtMs = System.currentTimeMillis()
        pending = null
        // El estado se apaga acá y no se espera al listener: si por lo que sea no llegara
        // onCastSessionUnavailable, la barra del celu se quedaría colgada mostrando un casteo muerto.
        _casting.value = false
        scope.launch {
            withContext(Dispatchers.Main) {
                // NO se llama a player.stop() acá: endCurrentSession(true) —abajo— ya apaga la app
                // receptora (para eso es el `true`), así que el stop() era redundante. Y peor que
                // redundante: el CastPlayer se deja adrede sin parar NUNCA, porque PlayerScreen.kt
                // depende de que `currentPosition` siga devolviendo para siempre la última posición
                // reportada para poder reanudar el local en el punto correcto (ver el comentario
                // ahí). Pararlo acá además corría una carrera con startProgressLoop: si el stop caía
                // justo entre que ese loop lee `_casting`/`pending` en IO y salta a Main a leer el
                // player, podía terminar persistiendo una posición en cero o retrocedida encima del
                // punto real de reanudación del usuario.
                // Termina la sesión SIN destruir el CastPlayer: release() lo dejaría inservible y no
                // se podría volver a castear hasta reiniciar la app. Además `true` apaga la app
                // receptora, que es lo que hace que la TV vuelva a lo suyo — release() usa `false`.
                runCatching { castContext.sessionManager.endCurrentSession(true) }
                // Best-effort: si la sesión ya estaba muerta, endCurrentSession no dispara ningún
                // callback del SDK (onCastSessionUnavailable no llega solo) y onSessionEnded —que
                // apaga el transcodificador— nunca se llamaría. Sin esto el transcodificador seguiría
                // vivo (CPU + puerto retenidos) con la barra ya oculta.
                runCatching { onSessionEnded() }
            }
        }
    }

    /** Consume la marca: la siguiente desconexión vuelve a reanudar normalmente. */
    fun consumirParadaIntencional(): Boolean {
        val at = paradaIntencionalAtMs
        paradaIntencionalAtMs = 0L
        // Con ventana, porque quien la pone y quien la consume no siempre coinciden: el botón de
        // parar vive en la barra y en "Reproduciendo ahora", y en ninguna de las dos está compuesto
        // el reproductor, así que lo normal es que NADIE la consuma. Sin cota quedaría encendida
        // para siempre y se la comería la próxima desconexión legítima desde el botón de cast,
        // salteando la reanudación local que re-hornea el :start-time.
        return at != 0L && System.currentTimeMillis() - at < VENTANA_PARADA_MS
    }

    private suspend fun load(r: CastRequest) {
        // Antes de tocar al receptor: que el origen ya esté entregando datos. Adelantarse acá es
        // exactamente lo que lo dejaba en idle para siempre.
        if (!awaitSourceReady(r)) {
            android.util.Log.e(TAG, "el origen no quedó listo; no le mando nada al receptor: ${r.uri}")
            return
        }
        loadNow(r)
    }

    private suspend fun loadNow(r: CastRequest) = withContext(Dispatchers.Main) {
        android.util.Log.i(
            TAG,
            "cargando en el receptor · mime=${r.mimeType} · desde=${r.startPositionMs}ms · " +
                "offset=${r.baseOffsetMs}ms durConocida=${r.knownDurationMs}ms · ${r.uri}",
        )
        player.setMediaItem(
            MediaItem.Builder()
                .setUri(r.uri)
                .setMimeType(r.mimeType)
                .setMediaId(r.episodeId)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(r.title)
                        .setArtist(r.subtitle)
                        .apply { if (r.artworkUrl.isNotBlank()) setArtworkUri(android.net.Uri.parse(r.artworkUrl)) }
                        .build(),
                )
                .build(),
            r.startPositionMs,
        )
        player.playWhenReady = true
        player.prepare()
    }

    /**
     * Persiste el progreso mientras se castea, INCLUSO con el reproductor cerrado. Sin esto, ver un
     * capítulo entero desde el home guardaría la posición solo hasta el instante en que se salió de
     * la pantalla: la misma pérdida silenciosa que el resto del feature vino a evitar.
     */
    private fun startProgressLoop() {
        scope.launch {
            while (true) {
                delay(PROGRESS_MS)
                if (!_casting.value) continue
                val request = pending ?: continue
                val epId = request.episodeId
                // mediaId, posición y duración se leen juntos en el mismo tick del hilo principal:
                // setMedia() actualiza `pending` en sync pero el receptor tarda (red) en cargar el
                // nuevo item, así que sin este chequeo se podría guardar la posición/duración del
                // episodio viejo bajo el id del nuevo.
                val (mediaId, pos, dur) = withContext(Dispatchers.Main) {
                    Triple(player.currentMediaItem?.mediaId, player.currentPosition, player.duration)
                }
                if (mediaId != epId) continue
                // Con el audio transcodificado el receptor cuenta desde cero y no sabe la duración,
                // así que lo que reporta hay que traducirlo antes de guardarlo.
                val progress = CastProgress.toSave(
                    reportedPosMs = pos,
                    reportedDurMs = dur,
                    baseOffsetMs = request.baseOffsetMs,
                    knownDurationMs = request.knownDurationMs,
                )
                if (progress == null) {
                    // Loud on purpose -- born diagnosing "torrent always restarts from zero" (a
                    // source removed in this branch's pruning), but the same audio-transcoded-cast
                    // failure mode still reaches any source today: if this shows up, progress is
                    // NOT being saved and the culprit is the duration (the receiver sends
                    // TIME_UNSET and `durConocida` came in at 0).
                    android.util.Log.w(
                        TAG,
                        "progreso NO guardado · pos=${pos}ms durReceptor=${dur}ms " +
                            "offset=${request.baseOffsetMs}ms durConocida=${request.knownDurationMs}ms",
                    )
                    continue
                }
                runCatching { repository.savePlayback(epId, progress.positionMs, progress.durationMs) }
                    .onFailure { android.util.Log.w(TAG, "no se pudo guardar el progreso: ${it.message}") }
            }
        }
    }

    private companion object {
        const val TAG = "ArkivCast"
        const val PROGRESS_MS = 5_000L
        const val VENTANA_PARADA_MS = 15_000L
    }
}
