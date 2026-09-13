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
) {
    // Falla rápido y con causa explícita si algo construye esto fuera del hilo principal, en vez de
    // un crash oscuro dentro del SDK de Cast (CastPlayer/CastContext lo exigen, ver clase doc).
    init {
        check(android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            "CastSessionManager debe construirse en el hilo principal: CastPlayer y CastContext lo exigen"
        }
    }

    // Custom converter, because the default one never states the duration -- see
    // [ConversorConDuracion] for what that costs on a fragmented MP4 still being written.
    val player: CastPlayer = CastPlayer(castContext, ConversorConDuracion())

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
                "the receiver failed: code=${error.errorCode} (${error.errorCodeName}) · ${error.message}",
                error,
            )
        }

        override fun onPlaybackStateChanged(state: Int) {
            android.util.Log.i(TAG, "receiver state: $state (1=idle 2=buffering 3=ready 4=ended)")
        }

        override fun onIsPlayingChanged(playing: Boolean) {
            android.util.Log.i(
                TAG,
                "receiver playing=$playing · pos=${player.currentPosition}ms · dur=${player.duration}ms",
            )
        }

        /**
         * The most informative thing for "it plays but doesn't sound": which tracks the receiver
         * ACCEPTED. If the TV drops the audio due to codec (DTS often isn't there), here you see
         * the selected video track and the missing or unsupported audio one, with no error popping
         * up.
         */
        override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
            if (tracks.groups.isEmpty()) {
                android.util.Log.w(TAG, "the receiver isn't reporting ANY track")
                return
            }
            tracks.groups.forEach { g ->
                for (i in 0 until g.length) {
                    val f = g.getTrackFormat(i)
                    android.util.Log.i(
                        TAG,
                        "track type=${g.type} codec=${f.codecs} mime=${f.sampleMimeType} " +
                            "language=${f.language} supported=${g.isTrackSupported(i)} selected=${g.isTrackSelected(i)}",
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
                val device = runCatching {
                    castContext.sessionManager.currentCastSession?.castDevice?.friendlyName
                }.getOrNull()
                android.util.Log.i(TAG, "session available · receiver=${device ?: "?"} · pending=${pending?.episodeId}")
                pending?.let { scope.launch { load(it) } }
                    ?: android.util.Log.w(TAG, "session available but nothing pending: nothing will be loaded")
            }

            override fun onCastSessionUnavailable() {
                android.util.Log.i(TAG, "session gone")
                _casting.value = false
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
        // Without this line "nothing was ever asked of the receiver" and "it was asked and refused"
        // look identical from a log: both end up as a session with an idle player.
        android.util.Log.i(
            TAG,
            "cast requested · ep=${request.episodeId} · mime=${request.mimeType} · from=${request.startPositionMs}ms " +
                "· session=${_casting.value} · ${request.uri}",
        )
        if (_casting.value) scope.launch { load(request) } else {
            android.util.Log.i(TAG, "no session yet: it stays pending until one shows up")
        }
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

    /**
     * Appends one more finished chunk to what the receiver is already playing.
     *
     * A title cast this way is a QUEUE of complete little mp4s rather than one file: each states
     * its own duration and never changes, so the receiver has nothing to recompute. See
     * `TsRemuxer.remuxearTrozo` for why a single growing file could not work.
     */
    fun encolar(uri: String, episodeId: String, titulo: String) {
        // The duration rides along so the converter can set autoplay and preload on the queue item
        // -- without them the receiver announces each entry with a countdown.
        scope.launch(Dispatchers.Main) {
            runCatching {
                player.addMediaItem(
                    MediaItem.Builder()
                        .setUri(uri)
                        .setMimeType("video/mp4")
                        .setMediaId(episodeId)
                        .setMediaMetadata(MediaMetadata.Builder().setTitle(titulo).build())
                        .build(),
                )
                android.util.Log.i(TAG, "queued one more chunk · ${player.mediaItemCount} in the queue")
            }.onFailure { android.util.Log.w(TAG, "could not queue a chunk: $it") }
        }
    }

    private suspend fun load(r: CastRequest) = withContext(Dispatchers.Main) {
        android.util.Log.i(
            TAG,
            "loading on the receiver · mime=${r.mimeType} · from=${r.startPositionMs}ms · ${r.uri}",
        )
        val loadOutcome = runCatching { player.setMediaItem(
            MediaItem.Builder()
                .setUri(r.uri)
                .setMimeType(r.mimeType)
                .setMediaId(r.episodeId)
                .setRequestMetadata(
                    MediaItem.RequestMetadata.Builder()
                        .setExtras(
                            android.os.Bundle().apply {
                                putLong(ConversorConDuracion.CLAVE_DURACION, r.durationMs)
                                putBoolean(ConversorConDuracion.CLAVE_EN_VIVO, r.comoEnVivo)
                            },
                        )
                        .build(),
                )
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(r.title)
                        .setArtist(r.subtitle)
                        .apply { if (r.artworkUrl.isNotBlank()) setArtworkUri(android.net.Uri.parse(r.artworkUrl)) }
                        .build(),
                )
                .build(),
            r.startPositionMs,
        ) }
        loadOutcome.onFailure { android.util.Log.e(TAG, "setMediaItem FAILED: $it", it) }
        player.playWhenReady = true
        runCatching { player.prepare() }
            .onFailure { android.util.Log.e(TAG, "prepare FAILED: $it", it) }
        android.util.Log.i(
            TAG,
            "load handed to the receiver · state=${player.playbackState} · item=${player.currentMediaItem?.mediaId}",
        )
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
                val request = pending
                if (request == null) {
                    // A session with nothing pending is one of the two ways a cast "does nothing",
                    // and the one that used to leave no trace at all: the receiver sits on its logo
                    // because it was never handed any media, not because it refused ours.
                    android.util.Log.w(TAG, "casting with NOTHING pending: the receiver was never given media")
                    continue
                }
                val epId = request.episodeId
                // mediaId, posición y duración se leen juntos en el mismo tick del hilo principal:
                // setMedia() actualiza `pending` en sync pero el receptor tarda (red) en cargar el
                // nuevo item, así que sin este chequeo se podría guardar la posición/duración del
                // episodio viejo bajo el id del nuevo.
                val (mediaId, pos, dur) = withContext(Dispatchers.Main) {
                    Triple(player.currentMediaItem?.mediaId, player.currentPosition, player.duration)
                }
                if (mediaId != epId) continue
                // Without a transcoder the receiver reports the real position and duration; the
                // only reason not to save is a live stream, which sends TIME_UNSET.
                // The receiver reports no duration for a stream announced as live, and a remux
                // being written is announced exactly that way -- so without this, casting a title
                // saved nothing at all and "continue watching" quietly stopped working. The phone
                // knows the duration (it has been drawing the bar with it) and knows where the
                // remux was clipped, so both are supplied here rather than trusted from the TV.
                val durReal = if (dur > 0L) dur else request.durationMs
                val progress = CastProgress.toSave(
                    reportedPosMs = pos + request.desfaseMs,
                    reportedDurMs = durReal,
                )
                if (progress == null) {
                    // Loud on purpose -- born diagnosing "torrent always restarts from zero" (a
                    // source removed in this branch's pruning); if this shows up for VOD, progress
                    // is NOT being saved and the culprit is the duration (the receiver sent
                    // TIME_UNSET, which live streams do and downloads should not).
                    android.util.Log.w(TAG, "progress NOT saved · pos=${pos}ms receiverDur=${dur}ms")
                    continue
                }
                runCatching { repository.savePlayback(epId, progress.positionMs, progress.durationMs) }
                    .onFailure { android.util.Log.w(TAG, "couldn't save the progress: ${it.message}") }
            }
        }
    }

    private companion object {
        const val TAG = "ArkivCast"
        const val PROGRESS_MS = 5_000L
        const val VENTANA_PARADA_MS = 15_000L
    }
}
