package com.arkiv.player.playback

import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.interfaces.IMedia
import org.videolan.libvlc.util.VLCVideoLayout

/**
 * Formato crudo de una pista de audio, tal como lo reporta libVLC. Sirve para decidir si un receptor
 * remoto (Chromecast) puede decodificarla o hay que transcodificarla antes de mandársela.
 */
data class AudioTrackFormat(
    val fourcc: Int,
    val channels: Int,
    /** Posición entre las pistas de audio (0, 1, 2…), que es lo que entiende `--audio-track` de VLC.
     *  No confundir con el id de la pista, que es un número interno y no correlativo. */
    val index: Int,
)

/**
 * Motor libVLC expuesto como Player de media3. Aloja un único MediaPlayer y traduce su estado
 * al modelo de SimpleBasePlayer, para que la MediaSession/notificación/segundo plano/playlist
 * de media3 funcionen sin cambios. El render (VLCVideoLayout) lo aporta la UI.
 */
@UnstableApi
class VlcPlayer(context: Context, looper: Looper) : SimpleBasePlayer(looper) {

    // ¿Corremos en una TV (Fire Stick, decoder flojo)? En TV NO forzamos "no descartar/saltar frames":
    // dejamos que VLC descarte frames tardíos para ponerse al día tras un bache de descarga en vez de
    // congelarse. En teléfono (decoder potente) los forzamos = máxima calidad. (Portado del torrent screen.)
    private val isTv: Boolean = runCatching {
        (context.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager)
            ?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION
    }.getOrDefault(false)

    // Preferencia de calidad web (Ajustes). Se lee FRESCA en cada loadMedia (SharedPreferences refleja el
    // cambio al toque), para que aplicar una calidad nueva valga en la próxima reproducción.
    private val settingsPrefs = context.applicationContext
        .getSharedPreferences(com.arkiv.player.data.SettingsStore.PREFS_NAME, Context.MODE_PRIVATE)

    private val libVlc = LibVLC(
        context,
        arrayListOf(
            "--network-caching=1500",
            "--file-caching=1500",
            "--audio-time-stretch",
        ).also {
            if (!isTv) { it.add("--no-drop-late-frames"); it.add("--no-skip-frames") }
        },
    )
    private val mediaPlayer = MediaPlayer(libVlc)
    private val handler = Handler(looper)

    private var items: List<MediaItem> = emptyList()
    private var currentIndex = 0
    private var playWhenReady = false
    private var event: VlcEvent = VlcEvent.Stopped
    private var buffering = 0f
    // Cantidad de salidas de video vivas (evento Vout). Lo escribe el thread de eventos de VLC y lo
    // lee la UI, de ahí el @Volatile.
    @Volatile private var voutCount = 0
    // Auto-retry con decodificación por SOFTWARE ante un EncounteredError: rescata formatos que abren
    // en HW pero fallan al decodificar (.avi/XviD, Dolby Vision, TrueHD/DTS). Una sola vez por ítem;
    // si vuelve a fallar en software, se emite el error. Se resetea al cargar otro ítem.
    private var triedSoftware = false
    // Web: si la reproducción DIRECTA del CDN falla (403/geo/anti-leech), se reintenta UNA vez con la
    // URL proxeada de respaldo (proxyUrl del tag). Se resetea al cargar otro ítem.
    private var triedProxy = false
    private var currentStartMs = 0L
    // Subtítulos APAGADOS por defecto: libVLC auto-activa la primera pista de subtítulos embebida, pero
    // el usuario quiere arrancar sin subs y prenderlos a mano. Al primer Playing de cada ítem forzamos
    // spu=-1 (una sola vez, para no pisar una selección posterior del usuario). Se resetea al cargar otro ítem.
    private var defaultSpuApplied = false
    // Auto-selección de pista de AUDIO por idioma (ventaja exclusiva de Arkiv: elige la pista correcta
    // dentro de un MKV DUAL en vez de la que ponga VLC). Preferencia configurable (default Latino>Cast>Dual);
    // se aplica una sola vez por ítem (defaultAudioApplied) para no pisar una elección manual posterior.
    @Volatile var audioLangPreference: List<AudioLang> = AudioTrackSelector.DEFAULT_PREFERENCE
    private var defaultAudioApplied = false
    // Detección de estancamiento por falta de buffer: cuando VLC se queda sin datos a mitad de la
    // reproducción, a veces NO emite un evento Buffering — simplemente deja de avanzar el tiempo. Este
    // watcher sondea la posición: si debería estar reproduciendo (playWhenReady) pero el tiempo no
    // avanza por más de STALL_MS, marca estado Buffering para que salga el overlay de "descargando".
    private var lastObservedTimeMs = -1L
    private var lastAdvanceWallMs = 0L
    // Diagnóstico de reproducción: heartbeat periódico + medición de cada pausa (buffering).
    private var heartbeatTick = 0
    private var bufferingSinceWallMs = 0L   // >0 mientras está estancado; para medir cuánto dura la pausa
    private val stallWatcher = object : Runnable {
        override fun run() { checkStall(); handler.postDelayed(this, STALL_POLL_MS) }
    }

    init {
        handler.postDelayed(stallWatcher, STALL_POLL_MS)
        mediaPlayer.setEventListener { e ->
            // LOG diagnóstico de todos los eventos de libVLC (para depurar reproducción web).
            runCatching {
                val name = when (e.type) {
                    MediaPlayer.Event.Buffering -> "Buffering ${e.buffering}%"
                    MediaPlayer.Event.Playing -> "Playing"
                    MediaPlayer.Event.Paused -> "Paused"
                    MediaPlayer.Event.Stopped -> "Stopped"
                    MediaPlayer.Event.EncounteredError -> "EncounteredError"
                    MediaPlayer.Event.EndReached -> "EndReached"
                    MediaPlayer.Event.Opening -> "Opening"
                    MediaPlayer.Event.TimeChanged -> "TimeChanged ${mediaPlayer.time}"
                    MediaPlayer.Event.Vout -> "Vout ${e.voutCount}"
                    else -> "type=${e.type}"
                }
                if (e.type != MediaPlayer.Event.TimeChanged && e.type != MediaPlayer.Event.PositionChanged) {
                    android.util.Log.w("ArkivVlc", "event=$name")
                }
            }
            when (e.type) {
                MediaPlayer.Event.Buffering -> {
                    // VLC bufferea (carga inicial, o cache underrun en torrent que descarga). <100% =
                    // no puede avanzar todavía → mostrar overlay. 100% = cache llena → listo/reproduciendo.
                    buffering = e.buffering
                    event = if (e.buffering >= 100f) VlcEvent.Playing else VlcEvent.Buffering
                }
                MediaPlayer.Event.Playing -> {
                    event = VlcEvent.Playing
                    if (!defaultSpuApplied) {
                        defaultSpuApplied = true
                        runCatching { mediaPlayer.spuTrack = -1 }
                    }
                    // Auto-seleccionar audio por idioma. En el looper (tocar el player en el thread de
                    // eventos crashea) y con reintentos: las pistas a veces pueblan justo tras Playing.
                    if (!defaultAudioApplied) {
                        defaultAudioApplied = true
                        handler.postDelayed({ applyPreferredAudio(retries = 3) }, 200)
                    }
                }
                // El tiempo de reproducción avanza ⇒ está reproduciendo DE VERDAD. VLC a veces deja el
                // estado pegado en Buffering (emite Buffering<100 mientras rellena cache sin re-emitir
                // Playing); si el tiempo avanza, limpiamos ese Buffering pegado para que el overlay de
                // "descargando" NO tape un video que se está reproduciendo bien. Si en cambio el torrent
                // se estanca por falta de buffer, TimeChanged deja de llegar y el Buffering persiste →
                // el overlay se muestra (que es justo lo que se quiere ahí).
                MediaPlayer.Event.TimeChanged ->
                    if (event == VlcEvent.Buffering) event = VlcEvent.Playing else return@setEventListener
                MediaPlayer.Event.Paused -> { event = VlcEvent.Paused }
                MediaPlayer.Event.Stopped -> { event = VlcEvent.Stopped; voutCount = 0 }
                // Salida de video viva o no. Al volver de segundo plano VLC la reconstruye recién en
                // el siguiente keyframe (unos segundos), y la UI necesita saberlo para no mostrar un
                // negro sin explicación mientras tanto.
                MediaPlayer.Event.Vout -> { voutCount = e.voutCount }
                MediaPlayer.Event.EncounteredError -> {
                    // El reload NO se hace aquí (corremos en el thread de eventos de VLC: tocar el media
                    // player ahí crashea), sino en el looper.
                    val tag = items.getOrNull(currentIndex)?.localConfiguration?.tag as? PlayerSourceTag
                    // Web: si la directa del CDN falló (403/geo/anti-leech), reintentar UNA vez con el proxy.
                    if (tag?.kind == SourceKind.WEB && !triedProxy && !tag.proxyUrl.isNullOrBlank()) {
                        triedProxy = true
                        handler.post { retryWithProxy(tag.proxyUrl) }
                        return@setEventListener
                    }
                    // Primer error (o web sin proxy): reintentar en software.
                    if (!triedSoftware) {
                        triedSoftware = true
                        handler.post { retryInSoftware() }
                        return@setEventListener
                    }
                    event = VlcEvent.Error
                }
                MediaPlayer.Event.EndReached -> onEndReached()
                // La duración de HLS/adaptive llega DESPUÉS del load (evento LengthChanged); hay que
                // refrescar el estado para que media3/UI tomen la duración (si no, la barra queda en 0:00).
                MediaPlayer.Event.LengthChanged -> { /* cae a invalidateState() abajo */ }
                else -> return@setEventListener
            }
            invalidateState()
        }
    }

    override fun getState(): State {
        // libVLC lanza IllegalStateException ("can't get VLCObject instance") si se consulta el
        // MediaPlayer nativo cuando aún no hay media válido o durante el release. media3 invoca
        // getState() (incluido este durationUs y el contentPosition de abajo) también al liberar el
        // player, así que TODA lectura nativa acá va protegida para no tumbar la app al salir.
        val lengthMs = runCatching { mediaPlayer.length }.getOrDefault(0L)
        val playlist = items.mapIndexed { i, item ->
            MediaItemData.Builder(item.mediaId.ifEmpty { "item-$i" })
                .setMediaItem(item)
                .setDurationUs(if (lengthMs > 0) lengthMs * 1000 else C.TIME_UNSET)
                .setIsSeekable(true)
                .build()
        }
        val playbackState = if (items.isEmpty()) Player.STATE_IDLE
            else VlcPlaybackState.mediaPlaybackState(event, buffering)
        val builder = State.Builder()
            .setAvailableCommands(AVAILABLE_COMMANDS)
            .setPlaybackState(playbackState)
            .setPlayWhenReady(playWhenReady, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setPlaylist(playlist)
            .setCurrentMediaItemIndex(currentIndex)
            .setContentPositionMs { runCatching { mediaPlayer.time.coerceAtLeast(0) }.getOrDefault(0L) }
        if (event == VlcEvent.Error) {
            builder.setPlayerError(
                PlaybackException("VLC playback error", null, PlaybackException.ERROR_CODE_UNSPECIFIED),
            )
        }
        return builder.build()
    }

    override fun handleSetMediaItems(
        mediaItems: MutableList<MediaItem>, startIndex: Int, startPositionMs: Long,
    ): ListenableFuture<*> {
        items = mediaItems.toList()
        currentIndex = if (startIndex == C.INDEX_UNSET) 0 else startIndex
        loadCurrent(if (startPositionMs == C.TIME_UNSET) 0L else startPositionMs)
        return Futures.immediateVoidFuture()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        this.playWhenReady = playWhenReady
        if (playWhenReady) mediaPlayer.play() else mediaPlayer.pause()
        return Futures.immediateVoidFuture()
    }

    override fun handlePrepare(): ListenableFuture<*> {
        if (items.isNotEmpty() && mediaPlayer.media == null) loadCurrent(0L)
        return Futures.immediateVoidFuture()
    }

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        if (mediaItemIndex != currentIndex) {
            currentIndex = mediaItemIndex.coerceIn(0, (items.size - 1).coerceAtLeast(0))
            loadCurrent(if (positionMs == C.TIME_UNSET) 0L else positionMs)
        } else if (positionMs != C.TIME_UNSET) {
            mediaPlayer.time = positionMs
        }
        return Futures.immediateVoidFuture()
    }

    override fun handleStop(): ListenableFuture<*> {
        runCatching { mediaPlayer.stop() }
        event = VlcEvent.Stopped
        return Futures.immediateVoidFuture()
    }

    override fun handleRelease(): ListenableFuture<*> {
        handler.removeCallbacks(stallWatcher)
        runCatching { mediaPlayer.stop() }
        runCatching { mediaPlayer.detachViews() }
        runCatching { mediaPlayer.release() }
        runCatching { libVlc.release() }
        return Futures.immediateVoidFuture()
    }

    /**
     * Sondeo periódico (en el looper) para detectar estancamiento por falta de buffer: si el usuario
     * quiere reproducir (playWhenReady) pero el tiempo de VLC no avanza por más de [STALL_MS], marca
     * estado Buffering → sale el overlay de "descargando". Si el tiempo avanza, limpia ese estado. Es
     * el complemento del listener: VLC no siempre avisa el underrun con un evento Buffering, pero SÍ
     * deja de avanzar el tiempo, y eso es lo que miramos acá.
     */
    private fun checkStall() {
        val active = playWhenReady && event != VlcEvent.Paused && event != VlcEvent.Stopped &&
            event != VlcEvent.Error && event != VlcEvent.EndReached
        if (!active) { lastObservedTimeMs = -1L; bufferingSinceWallMs = 0L; return }
        val now = System.currentTimeMillis()
        val t = runCatching { mediaPlayer.time }.getOrDefault(-1L)
        if (t < 0L) return
        // Heartbeat de salud cada ~3s (6 sondeos × 500ms): posición/duración/buffer%/estado/avanza.
        if (++heartbeatTick >= 6) {
            heartbeatTick = 0
            val dur = runCatching { mediaPlayer.length }.getOrDefault(0L)
            val avanza = t != lastObservedTimeMs
            runCatching { android.util.Log.w("ArkivVlc", "HB pos=${t}ms dur=${dur}ms buf=${buffering}% estado=$event avanza=$avanza") }
        }
        if (t != lastObservedTimeMs) {
            // El tiempo avanzó → está reproduciendo; limpiar un posible estado "buffering" pegado.
            lastObservedTimeMs = t
            lastAdvanceWallMs = now
            if (event == VlcEvent.Buffering) { event = VlcEvent.Playing; invalidateState() }
        } else if (event != VlcEvent.Buffering && now - lastAdvanceWallMs > STALL_MS) {
            // Debería reproducir pero el tiempo lleva rato sin avanzar → estancado por falta de buffer.
            event = VlcEvent.Buffering
            invalidateState()
        }
        // Medir la duración de cada pausa (buffering), venga del evento de libVLC o del watcher.
        if (event == VlcEvent.Buffering) {
            if (bufferingSinceWallMs == 0L) {
                bufferingSinceWallMs = now
                runCatching { android.util.Log.w("ArkivVlc", "PAUSA (buffering) en pos=${t}ms buf=${buffering}%") }
            }
        } else if (bufferingSinceWallMs > 0L) {
            runCatching { android.util.Log.w("ArkivVlc", "REANUDO tras ${now - bufferingSinceWallMs}ms de pausa (pos=${t}ms)") }
            bufferingSinceWallMs = 0L
        }
    }

    /** Carga el ítem actual en HW (camino nuevo): resetea los flags de reintento (software y proxy). */
    private fun loadCurrent(startPositionMs: Long) {
        triedSoftware = false
        triedProxy = false
        loadMedia(hardware = true, startPositionMs = startPositionMs)
    }

    /**
     * Arma y carga el Media del ítem actual. El caching de red se decide por FUENTE: un torrent
     * (origen VARIABLE, piezas llegando) necesita más colchón (2500ms) para absorber baches de
     * llegada sin cortar; archive/local va con 1500ms. HW por defecto; software en el reintento.
     */
    private fun loadMedia(hardware: Boolean, startPositionMs: Long, uriOverride: Uri? = null) {
        val item = items.getOrNull(currentIndex) ?: return
        val uri = uriOverride ?: item.localConfiguration?.uri ?: return
        val tag = item.localConfiguration?.tag as? PlayerSourceTag
        // Torrent: colchón de 6s para absorber baches de descarga durante la reproducción (validado en
        // device: con 2.5s VLC se quedaba sin datos y estancaba; 6s da el arranque más limpio). El bache
        // que ocurre justo al abrir VLC es CPU-bound (arranque del decoder HW en la TV) y VLC lee por
        // delante, así que subir más el caché no lo elimina — sólo agrega latencia de arranque.
        // WEB: el HLS va PROXEADO por blog (2 CPU) + Cloudflare, con latencia por-segmento variable →
        // 1.5s se drena y la reproducción alcanza al buffer ("se va pasando"). 8s de colchón para
        // absorber esa variabilidad de la red/proxy. archive/local: 1.5s basta (origen estable).
        val networkCaching = when (tag?.kind) {
            SourceKind.TORRENT -> 6000
            SourceKind.WEB -> 8000
            else -> 1500
        }
        currentStartMs = startPositionMs
        defaultSpuApplied = false // cada ítem/recarga arranca con subtítulos apagados
        defaultAudioApplied = false // y re-evalúa la pista de audio preferida
        val media = Media(libVlc, uri).apply {
            setHWDecoderEnabled(hardware, false)
            addOption(":network-caching=$networkCaching")
            if (startPositionMs > 0) addOption(":start-time=${startPositionMs / 1000}")
            // WEB: calidad HLS según la preferencia de Ajustes (AUTO por defecto). AUTO decide por CAMINO:
            // por PROXY (túnel ~1 Mbps) fuerza 480p para no cortarse; en DIRECTO (CDN ~6+ Mbps) usa 'rate'
            // y sube a 720p/1080p si tu conexión aguanta. SD/HD/MÁX = fijo, la elección del usuario manda.
            // (Ignorado si no es HLS adaptativo.)
            if (tag?.kind == SourceKind.WEB) {
                val viaProxy = uri.toString().contains("/proxy?url=")
                val webQ = runCatching {
                    com.arkiv.player.data.WebQuality.valueOf(
                        settingsPrefs.getString(com.arkiv.player.data.SettingsStore.KEY_WEB_QUALITY, "AUTO")!!,
                    )
                }.getOrDefault(com.arkiv.player.data.WebQuality.AUTO)
                when (webQ) {
                    com.arkiv.player.data.WebQuality.AUTO ->
                        addOption(if (viaProxy) ":adaptive-logic=lowest" else ":adaptive-logic=rate")
                    com.arkiv.player.data.WebQuality.SD -> addOption(":adaptive-logic=lowest")
                    com.arkiv.player.data.WebQuality.HD -> addOption(":adaptive-maxheight=720")
                    com.arkiv.player.data.WebQuality.MAX -> addOption(":adaptive-logic=highest")
                }
            }
            // Streams web: algunos hosts exigen Referer/UA o devuelven 403.
            tag?.referer?.takeIf { it.isNotBlank() }?.let { addOption(":http-referrer=$it") }
            tag?.userAgent?.takeIf { it.isNotBlank() }?.let { addOption(":http-user-agent=$it") }
        }
        runCatching {
            android.util.Log.w("ArkivVlc", "loadMedia hw=$hardware kind=${tag?.kind} referer=${tag?.referer} uri=$uri")
        }
        mediaPlayer.media = media
        media.release()
        if (playWhenReady) mediaPlayer.play()
    }

    /**
     * Selecciona la pista de audio según [audioLangPreference] (Latino>Castellano>Dual por defecto). Corre
     * en el looper. Si aún no hay >1 pista (VLC las expone poco después de Playing), reintenta. Si ninguna
     * pista coincide con la preferencia, deja la de VLC (no toca nada). Ganancia clave para MKV DUAL.
     */
    private fun applyPreferredAudio(retries: Int) {
        val tracks = vlcAudioTracks()
        if (tracks.count { it.first >= 0 } <= 1) {
            if (retries > 0) handler.postDelayed({ applyPreferredAudio(retries - 1) }, 400)
            return
        }
        val id = AudioTrackSelector.select(tracks, audioLangPreference) ?: return
        if (id != currentAudioTrack()) {
            runCatching { android.util.Log.w("ArkivVlc", "auto-audio -> id=$id de ${tracks.map { it.second }}") }
            setVlcAudioTrack(id)
        }
    }

    /** Reintento tras EncounteredError: recarga el mismo ítem en software, retomando donde iba. */
    private fun retryInSoftware() {
        val resumeAt = runCatching { mediaPlayer.time }.getOrDefault(0L).coerceAtLeast(0L)
        runCatching { mediaPlayer.stop() }
        loadMedia(hardware = false, startPositionMs = if (resumeAt > 0) resumeAt else currentStartMs)
    }

    /** Web: la directa del CDN falló → recargar el MISMO ítem por la URL proxeada de respaldo (en HW),
     * retomando donde iba. El /proxy hornea el Referer server-side, así que sirve para casting/geo/anti-leech. */
    private fun retryWithProxy(proxyUrl: String) {
        runCatching { android.util.Log.w("ArkivVlc", "directa falló → reintento por proxy: $proxyUrl") }
        val resumeAt = runCatching { mediaPlayer.time }.getOrDefault(0L).coerceAtLeast(0L)
        runCatching { mediaPlayer.stop() }
        loadMedia(hardware = true, startPositionMs = if (resumeAt > 0) resumeAt else currentStartMs, uriOverride = Uri.parse(proxyUrl))
    }

    private fun onEndReached() {
        // Web: un EndReached casi inmediato (posición ~0) NO es fin real — el stream directo no entregó
        // datos (roto/geo/anti-leech) y VLC lo dio por "terminado". Reintentar UNA vez con el proxy antes
        // de pasar de largo. (VLC en estos casos emite EndReached, no EncounteredError, así que el fallback
        // del listener de error no alcanza.)
        val tag = items.getOrNull(currentIndex)?.localConfiguration?.tag as? PlayerSourceTag
        val playedMs = runCatching { mediaPlayer.time }.getOrDefault(0L)
        if (tag?.kind == SourceKind.WEB && !triedProxy && !tag.proxyUrl.isNullOrBlank() && playedMs < 5000L) {
            triedProxy = true
            runCatching { android.util.Log.w("ArkivVlc", "EndReached en pos=${playedMs}ms (stream directo roto) → fallback a proxy") }
            handler.post { retryWithProxy(tag.proxyUrl) }
            return
        }
        // Autoplay: pasar al siguiente ítem de la playlist, o terminar.
        if (currentIndex < items.size - 1) {
            currentIndex++
            loadCurrent(0L)
        } else {
            event = VlcEvent.EndReached
        }
    }

    // --- Control de velocidad / zoom / volumen (para la UI). Todos defensivos (runCatching). ---
    /** Velocidad de reproducción (0.75×…2×). */
    fun setRate(rate: Float) { runCatching { mediaPlayer.rate = rate } }
    fun currentRate(): Float = runCatching { mediaPlayer.rate }.getOrDefault(1f)
    /** Zoom nativo de VLC: scale=0 = "Ajustar"/fit; >0 = factor de crop (recorta barras negras). */
    fun setScale(scale: Float) { runCatching { mediaPlayer.scale = scale } }
    fun currentScale(): Float = runCatching { mediaPlayer.scale }.getOrDefault(0f)
    /** Volumen 0..200 (VLC permite amplificar por encima de 100). Nombre vlc* para no chocar con
     * el getVolume/setVolume (Float 0..1) de media3 SimpleBasePlayer. */
    fun vlcVolume(): Int = runCatching { mediaPlayer.volume }.getOrDefault(100)
    fun setVlcVolume(v: Int) { runCatching { mediaPlayer.setVolume(v.coerceIn(0, 200)) } }

    /** ¿VLC está pintando video ahora? Falso mientras reconstruye el vout al volver de segundo plano. */
    fun hasVideoOutput(): Boolean = voutCount > 0

    fun attachVideo(layout: VLCVideoLayout) {
        // attachViews() PISA el VideoHelper anterior sin liberarlo (fuga + callbacks viejos sobre el
        // holder), así que soltamos primero. detachViews() es no-op si no había nada enganchado.
        runCatching { mediaPlayer.detachViews() }
        runCatching { mediaPlayer.attachViews(layout, null, true, false) }
    }
    fun detachVideo() { runCatching { mediaPlayer.detachViews() } }

    /**
     * Formato crudo de la pista de audio en uso. `audioTracks` NO sirve para esto: da id y nombre
     * para el selector, no el códec. El códec vive en las pistas del Media.
     *
     * Se lee el `fourcc` y no el string `codec` porque ese string es la descripción para humanos que
     * devuelve libVLC ("A/52 Audio (aka AC3)"), no un identificador estable.
     *
     * Devuelve null si todavía no hay media parseada o no hay pista de audio.
     */
    fun currentAudioFormat(): AudioTrackFormat? = runCatching {
        // getMedia() RETIENE la referencia (lo dice su doc): sin el release() de abajo se fuga en
        // cada consulta.
        val media = mediaPlayer.media ?: return@runCatching null
        try {
            val selectedId = runCatching { mediaPlayer.audioTrack }.getOrDefault(-1)
            var fallback: AudioTrackFormat? = null
            var audioIndex = 0
            for (i in 0 until media.trackCount) {
                val track = media.getTrack(i) as? IMedia.AudioTrack ?: continue
                val format = AudioTrackFormat(track.fourcc, track.channels, audioIndex)
                audioIndex++
                // La pista elegida manda; si no hay ninguna marcada, vale la primera de audio.
                if (track.id == selectedId) return@runCatching format
                if (fallback == null) fallback = format
            }
            fallback
        } finally {
            runCatching { media.release() }
        }
    }.getOrNull()

    fun vlcAudioTracks(): List<Pair<Int, String>> =
        runCatching { mediaPlayer.audioTracks?.map { it.id to it.name } }.getOrNull().orEmpty()
    fun vlcSpuTracks(): List<Pair<Int, String>> =
        runCatching { mediaPlayer.spuTracks?.map { it.id to it.name } }.getOrNull().orEmpty()
    fun currentAudioTrack(): Int = runCatching { mediaPlayer.audioTrack }.getOrDefault(-1)
    fun currentSpuTrack(): Int = runCatching { mediaPlayer.spuTrack }.getOrDefault(-1)
    fun setVlcAudioTrack(id: Int) { runCatching { mediaPlayer.setAudioTrack(id) } }
    fun setVlcSpuTrack(id: Int) { runCatching { mediaPlayer.setSpuTrack(id) } }
    fun addSubtitleSlave(uri: Uri) {
        runCatching { mediaPlayer.addSlave(IMedia.Slave.Type.Subtitle, uri, true) }
    }

    private companion object {
        const val STALL_POLL_MS = 500L  // cada cuánto sondea el watcher de estancamiento
        const val STALL_MS = 900L       // tiempo sin avanzar (queriendo reproducir) para marcar buffering

        val AVAILABLE_COMMANDS = Player.Commands.Builder()
            .addAll(
                Player.COMMAND_PLAY_PAUSE, Player.COMMAND_PREPARE, Player.COMMAND_STOP,
                Player.COMMAND_SEEK_TO_DEFAULT_POSITION, Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
                Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
                Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_PREVIOUS,
                Player.COMMAND_SEEK_TO_MEDIA_ITEM, Player.COMMAND_SET_MEDIA_ITEM,
                // setMediaItems(List) del MediaController requiere COMMAND_CHANGE_MEDIA_ITEMS; sin él
                // media3 descarta el comando en silencio y el player nunca recibe la playlist (pantalla
                // negra, no reproduce). COMMAND_SET_MEDIA_ITEM (singular) NO habilita setMediaItems(List).
                Player.COMMAND_CHANGE_MEDIA_ITEMS,
                Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
                Player.COMMAND_GET_TIMELINE, Player.COMMAND_GET_METADATA, Player.COMMAND_RELEASE,
            )
            .build()
    }
}
