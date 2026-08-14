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
import com.arkiv.player.data.subtitles.PlaybackPrefs
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
    // Si hay imagen. Lo escribe el thread de eventos de VLC y lo lee la UI; el @Volatile vive
    // adentro del tracker. No alcanza con contar eventos: ver VoutTracker.
    private val voutTracker = VoutTracker()
    // Auto-retry con decodificación por SOFTWARE ante un EncounteredError: rescata formatos que abren
    // en HW pero fallan al decodificar (.avi/XviD, Dolby Vision, TrueHD/DTS). Una sola vez por ítem;
    // si vuelve a fallar en software, se emite el error. Se resetea al cargar otro ítem.
    private var triedSoftware = false
    // Cuándo se pasó a software, y si ya se volvió al hardware por falta de imagen. Una vuelta y
    // nada más: ir y venir entre decodificadores sería peor que cualquiera de los dos.
    private var softwareDesdeWallMs = 0L
    private var volvioAHardware = false
    // Web: si la reproducción DIRECTA del CDN falla (403/geo/anti-leech), se reintenta UNA vez con la
    // URL proxeada de respaldo (proxyUrl del tag). Se resetea al cargar otro ítem.
    private var triedProxy = false
    private var currentStartMs = 0L
    // Duración real del ítem cuando libVLC no puede deducirla (TS servido por HTTP: magis). Viaja en
    // el tag del MediaItem y la sondea TsDurationProbe. Ver UnknownLengthPolicy.
    private var knownDurationMs = 0L
    // Sin duración, `:start-time` se ignora: la posición de arranque hay que aplicarla por fracción
    // una vez que VLC ya está reproduciendo. Una sola vez por ítem.
    private var startPositionApplied = false
    // VENTANA (solo magis/TS): en qué milisegundo del contenido empieza el stream que VLC tiene
    // abierto. Con TS por HTTP el salto de VLC deja el demuxer con el reloj viejo y la
    // reproducción se muere (ver VentanaDeArchivo), así que en vez de saltar se le abre un stream
    // que YA empieza donde toca — el caso que sí funciona— y el desfase se suma acá afuera. VLC
    // siempre cree que va por el 0; el resto de la app ve tiempos absolutos.
    private var baseOffsetMs = 0L
    // Cuándo se abrió el media de ahora, para poder decir "lleva N segundos sin dar imagen".
    private var mediaCargadaWallMs = 0L
    // Cuándo apareció la PRIMERA imagen de este media (0 = todavía ninguna). Solo para cronometrar
    // el arranque; se reinicia con cada loadMedia para que una recarga no herede la marca vieja.
    private var primerVoutWallMs = 0L
    // Si ya se avisó "CORRIENDO" para este media. Solo interesa la PRIMERA vez: los estancamientos
    // posteriores ya los cuenta el "REANUDO tras Nms" de siempre.
    private var corriendoAvisado = false
    // El layout donde ESTE media dio su primera imagen. Null hasta que la da, y se reinicia con cada
    // carga: sirve para comparar contra el layout enganchado ahora. Ver superficieDistintaALaDelVideo.
    @Volatile private var layoutDelVideo: String? = null
    /**
     * Desde cuándo NO hay salida de video, de corrido (0 = ahora mismo sí hay).
     *
     * Se mide la ausencia SOSTENIDA y no la de un instante, y eso es el arreglo de un fallo real:
     * la salida de video parpadea cuando libVLC la reconstruye, así que preguntando "¿hay video
     * ahora?" una sola lectura en falso alcanzaba para disparar el cambio de decodificador — y en
     * device eso tumbó una reproducción que llevaba 16 s andando perfecta (`video=true pistas=v2/a3`)
     * para mandarla a un decodificador donde ya no arrancó.
     */
    private var sinVideoDesdeWallMs = 0L
    /**
     * Identidad de ESTE reproductor, para el diagnóstico.
     *
     * Va en cada línea que importa porque hay una sospecha concreta que sin esto no se puede ni
     * confirmar ni descartar: que al salir de la pantalla no se libere el reproductor y quede uno
     * viejo vivo peleando por el decodificador y por la conexión al CDN (se oyó audio de la
     * reproducción anterior al abrir la siguiente). Si en el log aparecen dos `player=#` distintos
     * cargando media a la vez, la sospecha es cierta y se ve de una.
     */
    private val idInstancia = System.identityHashCode(this)
    // Cuándo se reabrió la ventana por última vez. Reabrir es caro (conexión nueva al CDN) y sobre
    // todo EXCLUYENTE: la nueva mata a la anterior, así que una ráfaga de saltos deja a todos sin
    // datos. Visto en device: cinco reaperturas en 114 ms y pantalla negra sin sonido.
    private var ultimaReaperturaWallMs = 0L
    // Con qué decodificador está abierto lo de ahora. Reabrir por ventana tiene que respetarlo: si
    // ya se había caído a software, volver a hardware repetiría el fallo que motivó el cambio.
    private var hardwareActual = true
    // Subtítulos: NO se fuerzan en OFF a ciegas. Se decide por idioma (ver SubtitleDecision) apenas
    // las pistas pueblan — que es DESPUÉS de Playing, no en Playing. El apagado anterior reintentaba
    // 4 veces en ~1,5 s y, cuando VLC poblaba más tarde, ya se había rendido y se colaba la primera
    // pista. Ahora se re-afirma la DECISIÓN durante ~3 s. `userTouchedSpu` corta todo si el usuario
    // eligió un subtítulo a mano. Ambos flags se resetean al cargar otro ítem.
    private var defaultSpuApplied = false
    private var userTouchedSpu = false
    // Preferencias de idioma del usuario (audio y subtítulos). Las mantiene al día PlaybackService
    // observando el StateFlow de SubtitlePrefs, así un cambio en Ajustes (o sincronizado desde el
    // celular) pega en la próxima reproducción sin reiniciar nada.
    @Volatile var langPrefs: PlaybackPrefs = PlaybackPrefs()
    private var defaultAudioApplied = false
    // Idioma declarado de las pistas externas cuyo NOMBRE no lo delata (las de una fuente web llegan
    // como una URL opaca del CDN). Clave: un trozo de la URL, en minúsculas, que aparezca en el
    // nombre con que libVLC bautiza la pista. Ver clasificarSpu. Se limpia al cargar otro ítem.
    private val idiomaExterno = linkedMapOf<String, TrackLang>()
    /**
     * Idiomas que la FUENTE declara para las pistas de subtítulo EMBEBIDAS, en su orden.
     *
     * Existe por el MPEG-TS de magis: sus pistas de subtítulo llegan sin idioma por ningún lado
     * —medido en device, `language=null` en `IMedia.Track` y nombre pelado "Track 1"—, mientras las
     * de audio sí traen `spa`/`eng`/`jpn`. Sin esto no hay nada que clasificar y el subtítulo
     * automático es imposible en esa fuente. El portal sí sabe los idiomas y los entrega en el mismo
     * orden que las pistas (verificado a mano: portal `[en, es, es]` ↔ Track 1 inglés, Track 2 y 3
     * español). Ver clasificarSpuConFuente para el guardia que evita adivinar. Se limpia al cargar
     * otro ítem.
     */
    @Volatile var idiomasSpuDeLaFuente: List<String> = emptyList()
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
        // Nace un reproductor. Junto con RELEASE, permite contar cuántos hay vivos: si se crean dos
        // y solo se libera uno, ahí está el que se queda con el decodificador.
        runCatching { android.util.Log.w("ArkivVlc", "NACE player=#$idInstancia") }
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
                // La otra mitad del cronómetro del arranque (la primera está en loadMagis): cuánto
                // tarda libVLC desde que se le da la URL hasta que hay imagen. Se marca UNA vez por
                // media —el primer Vout, no el `Playing`— porque `Playing` llega con el reloj en 0 y
                // el buffer al 0%: medido en device, entre ese evento y el primer frame llegó a
                // haber 12 s de sondeos al CDN. El Vout es el instante en que el usuario ve algo.
                if (e.type == MediaPlayer.Event.Vout && e.voutCount > 0 &&
                    mediaCargadaWallMs > 0L && primerVoutWallMs == 0L
                ) {
                    primerVoutWallMs = System.currentTimeMillis()
                    // De QUÉ superficie salió esa imagen. Es lo que después permite saber si el
                    // reproductor volvió sobre una pantalla distinta. Ver superficieDistintaALaDelVideo.
                    layoutDelVideo = layoutEnganchado
                    android.util.Log.w(
                        "ArkivVlc",
                        "⏱ abrió en ${primerVoutWallMs - mediaCargadaWallMs}ms (loadMedia → primera imagen)",
                    )
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
                        // Después del audio (200 ms): la decisión depende de qué pista quedó sonando.
                        // 8 reintentos × 350 ms ≈ 3 s, que es lo que tarda VLC en poblar en el peor caso.
                        handler.postDelayed({ applyPreferredSpu(retries = 8) }, 400)
                    }
                    // Auto-seleccionar audio por idioma. En el looper (tocar el player en el thread de
                    // eventos crashea) y con reintentos: las pistas a veces pueblan justo tras Playing.
                    if (!defaultAudioApplied) {
                        defaultAudioApplied = true
                        handler.postDelayed({ applyPreferredAudio(retries = 3) }, 200)
                    }
                    // Reanudar donde ibas cuando VLC no conoce la duración: `:start-time` se ignora
                    // (arranca en 0) y hay que moverlo por fracción ya reproduciendo.
                    if (!startPositionApplied && currentStartMs > 0) {
                        startPositionApplied = true
                        handler.postDelayed({ aplicarArranquePorFraccion() }, 500)
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
                MediaPlayer.Event.Stopped -> { event = VlcEvent.Stopped; voutTracker.onVout(0) }
                // Salida de video viva o no. Al volver de segundo plano VLC la reconstruye recién en
                // el siguiente keyframe (unos segundos), y la UI necesita saberlo para no mostrar un
                // negro sin explicación mientras tanto.
                MediaPlayer.Event.Vout -> {
                    // El momento exacto en que la imagen aparece o se muere. Va con el layout
                    // enganchado al lado: un `-> 0` con un layout que ya no está en la ventana es
                    // la firma de que VLC quedó pintando sobre una Surface destruida.
                    android.util.Log.w(
                        "ArkivVout",
                        "VOUT ${if (voutTracker.hayVideo()) 1 else 0} -> ${e.voutCount} (layout=#${layoutEnganchado ?: "-"})",
                    )
                    voutTracker.onVout(e.voutCount)
                }
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
        // Sin duración propia se cae a la sondeada, o la barra se pinta llena y en 00:00 (y tampoco
        // se guarda el progreso, que exige dur>0). Y dentro de una ventana lo que informa el
        // reproductor es solo el tramo abierto, así que ahí no puede ganar.
        val duracionMs = UnknownLengthPolicy.duracionAbsolutaMs(lengthMs, knownDurationMs, baseOffsetMs)
        val playlist = items.mapIndexed { i, item ->
            MediaItemData.Builder(item.mediaId.ifEmpty { "item-$i" })
                .setMediaItem(item)
                .setDurationUs(if (duracionMs > 0) duracionMs * 1000 else C.TIME_UNSET)
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
            // Dentro de una VENTANA el reloj de VLC no sirve para ubicarse (ver
            // VentanaDeArchivo.posicionAbsolutaMs): se usa la fracción, que sí es por byte.
            .setContentPositionMs {
                if (baseOffsetMs <= 0L) {
                    runCatching { mediaPlayer.time.coerceAtLeast(0) }.getOrDefault(0L)
                } else {
                    VentanaDeArchivo.posicionAbsolutaMs(
                        baseOffsetMs,
                        runCatching { mediaPlayer.position }.getOrDefault(0f),
                        duracionMs,
                    )
                }
            }
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
        // TODO seek que entra, venga de donde venga. Sin esto una ráfaga de saltos es invisible:
        // solo se ve el efecto (reaperturas encadenadas) y no quién los pide ni con qué comando.
        // `cmd` distingue el salto del usuario del automático de media3.
        runCatching {
            android.util.Log.w(
                "ArkivVlc",
                "SEEK entra player=#$idInstancia idx=$mediaItemIndex pos=${positionMs}ms cmd=$seekCommand " +
                    "base=${baseOffsetMs}ms desdeLaUltima=${System.currentTimeMillis() - ultimaReaperturaWallMs}ms",
            )
        }
        if (mediaItemIndex != currentIndex) {
            currentIndex = mediaItemIndex.coerceIn(0, (items.size - 1).coerceAtLeast(0))
            loadCurrent(if (positionMs == C.TIME_UNSET) 0L else positionMs)
        } else if (positionMs != C.TIME_UNSET) {
            val lengthMs = runCatching { mediaPlayer.length }.getOrDefault(0L)
            // `baseOffsetMs > 0` cubre el volver atrás estando ya en una ventana (incluido el
            // seek a 0, que sin esto caería al principio de la VENTANA y no al de la película).
            val ahora = System.currentTimeMillis()
            val ventanaAplica = baseOffsetMs > 0L ||
                VentanaDeArchivo.hayQueAbrirVentana(positionMs, lengthMs, knownDurationMs)
            // Dos frenos, y los dos son necesarios. Reabrir es EXCLUYENTE (la conexión nueva mata a
            // la anterior), así que una ráfaga de saltos deja a todo el mundo sin datos: negro y sin
            // sonido, sin recuperación. Se ignoran los saltos que caen prácticamente donde ya está
            // abierto y los que llegan pisando una reapertura reciente.
            val yaEstaAhi = baseOffsetMs > 0L && kotlin.math.abs(positionMs - baseOffsetMs) < SALTO_MINIMO_MS
            val muySeguido = ahora - ultimaReaperturaWallMs < REAPERTURA_MINIMA_MS
            if (ventanaAplica && (yaEstaAhi || muySeguido)) {
                runCatching {
                    android.util.Log.w(
                        "ArkivVlc",
                        "seek ignorado a ${positionMs}ms (base=${baseOffsetMs}ms yaEstaAhi=$yaEstaAhi muySeguido=$muySeguido)",
                    )
                }
                return Futures.immediateVoidFuture()
            }
            if (ventanaAplica) {
                ultimaReaperturaWallMs = ahora
                // Sin duración propia, el salto de libVLC deja el demuxer con el reloj del punto
                // viejo y la reproducción no vuelve (752 MB tragados en 330 s con el reloj
                // congelado, medido en device). Se REABRE el stream desde el punto pedido, que es
                // lo único que sale bien siempre. Cuesta un arranque, pero arranca.
                runCatching {
                    android.util.Log.w("ArkivVlc", "seek por VENTANA a ${positionMs}ms de ${knownDurationMs}ms")
                }
                loadMedia(hardware = hardwareActual, startPositionMs = positionMs)
            } else {
                // Con duración propia el seek por tiempo es exacto y no hay nada que arreglar.
                mediaPlayer.time = positionMs
            }
        }
        return Futures.immediateVoidFuture()
    }

    override fun handleStop(): ListenableFuture<*> {
        runCatching { mediaPlayer.stop() }
        event = VlcEvent.Stopped
        return Futures.immediateVoidFuture()
    }

    override fun handleRelease(): ListenableFuture<*> {
        // La contracara del log de creación: si al salir de la pantalla no aparece esta línea, el
        // reproductor NO se liberó y sigue vivo con su decodificador y su conexión tomados.
        runCatching { android.util.Log.w("ArkivVlc", "RELEASE player=#$idInstancia") }
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
            // `dur` es lo que sabe VLC y `efectiva` lo que ve la UI: con TS por HTTP el primero es 0
            // y el segundo sale de la sonda. Verlos juntos dice de un vistazo si la sonda llegó.
            val efectiva = UnknownLengthPolicy.duracionAbsolutaMs(dur, knownDurationMs, baseOffsetMs)
            runCatching {
                android.util.Log.w(
                    "ArkivVlc",
                    // `video` y `pistas` son lo que separa "no llegan datos" de "llegan y no se
                    // decodifican": las dos se ven igual desde afuera (pantalla negra) y tienen
                    // causas opuestas. `player=#` delata si hay más de un reproductor vivo.
                    "HB player=#$idInstancia pos=${t}ms dur=${dur}ms efectiva=${efectiva}ms " +
                        "buf=${buffering}% estado=$event avanza=$avanza video=${voutTracker.hayVideo()} " +
                        "pistas=v${runCatching { mediaPlayer.videoTracksCount }.getOrDefault(-1)}" +
                        "/a${runCatching { mediaPlayer.audioTracksCount }.getOrDefault(-1)}" +
                        // Sin esto, "el subtítulo no se ve" tiene tres causas que desde afuera son
                        // idénticas: que la pista no llegó, que llegó y está apagada, o que está
                        // encendida y sale a destiempo.
                        "/s${runCatching { mediaPlayer.spuTracksCount }.getOrDefault(-1)} " +
                        "spu=${runCatching { mediaPlayer.spuTrack }.getOrDefault(-2)} " +
                        "spuDelay=${runCatching { mediaPlayer.spuDelay }.getOrDefault(0L)}",
                )
            }
        }
        // SOFTWARE QUE NO DA IMAGEN: el reloj corre pero no hay salida de video. Es la firma del
        // decodificador por software que acepta el formato y no puede con él (HEVC en un teléfono:
        // "get_buffer() failed" y pantalla negra, con el audio cayéndose atrás). Se vuelve al
        // hardware una sola vez; quedarse acá es quedarse en negro para siempre.
        // Racha de "sin imagen": se corta apenas aparece la salida de video. Los dos rescates de
        // abajo miran ESTA racha, no el instante. Qué instantes cuentan y por qué: [RachaSinVideo].
        val vTracks = runCatching { mediaPlayer.videoTracksCount }.getOrDefault(-1)
        val aTracks = runCatching { mediaPlayer.audioTracksCount }.getOrDefault(-1)
        // Un negativo en cualquiera de las dos es "no se pudo consultar", y hay que propagarlo como
        // tal: sumar a secas mezclaría un -1 con un 1 y daría 0, que significa lo contrario ("el
        // demuxer no sacó ninguna pista").
        val pistasDemuxeadas = if (vTracks < 0 || aTracks < 0) -1 else vTracks + aTracks
        if (!RachaSinVideo.cuenta(
                hayVideo = voutTracker.hayVideo(),
                superficieEnganchada = layoutEnganchado != null,
                pistas = pistasDemuxeadas,
            )
        ) sinVideoDesdeWallMs = 0L
        else if (sinVideoDesdeWallMs == 0L) sinVideoDesdeWallMs = now
        val rachaSinVideoMs = if (sinVideoDesdeWallMs == 0L) 0L else now - sinVideoDesdeWallMs

        if (triedSoftware && !volvioAHardware && softwareDesdeWallMs > 0L &&
            t > 0L && rachaSinVideoMs > SIN_VIDEO_EN_SOFTWARE_MS
        ) {
            volvioAHardware = true
            runCatching {
                android.util.Log.w(
                    "ArkivVlc",
                    "software sin imagen ${rachaSinVideoMs}ms seguidos (pos=${t}ms) → vuelvo a hardware",
                )
            }
            handler.post { reintentarEnHardware() }
            return
        }
        // HARDWARE QUE NO DA IMAGEN → software. Se mira la SALIDA DE VIDEO y no el reloj, y eso es
        // el arreglo: la condición vieja exigía que el tiempo estuviera clavado en 0, y el modo en
        // que falla de verdad el decodificador HEVC de un teléfono con estos TS es el contrario —
        // sin frames que marquen el ritmo, VLC vacía el archivo a toda velocidad y su reloj se
        // dispara (medido: 337 MB en 67 s, o sea 40× el bitrate, con el reloj a 50×). Como el
        // tiempo "avanzaba", el rescate no se disparaba nunca y la pantalla quedaba negra hasta
        // que la conexión se acababa sola.
        if (!triedSoftware && hardwareActual && mediaCargadaWallMs > 0L &&
            rachaSinVideoMs > SIN_VIDEO_MS && now - mediaCargadaWallMs > SIN_VIDEO_MS
        ) {
            triedSoftware = true
            runCatching {
                android.util.Log.w(
                    "ArkivVlc",
                    "hardware sin imagen ${rachaSinVideoMs}ms seguidos (pos=${t}ms) → paso a software",
                )
            }
            handler.post { retryInSoftware() }
            return
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
            } else if (
                !triedSoftware && t == 0L && now - bufferingSinceWallMs > STALL_SOFTWARE_MS
            ) {
                // El decodificador por hardware acepta el formato y despues se cuelga SIN dar error:
                // libVLC informa "Playing" y el tiempo nunca arranca, asi que el reintento por
                // software —que solo escucha EncounteredError— jamas se dispara y el reproductor se
                // queda en negro para siempre. Visto con HEVC en el emulador; le puede pasar a
                // cualquier equipo cuyo decodificador acepte un perfil que no puede con el.
                //
                // Solo cuando NUNCA arranco (t == 0): un estancamiento a mitad es falta de datos, no
                // un problema de codec, y reiniciar en software ahi seria peor.
                triedSoftware = true
                runCatching {
                    android.util.Log.w(
                        "ArkivVlc",
                        "estancado en 0 con hardware tras ${now - bufferingSinceWallMs}ms → reintento por software",
                    )
                }
                handler.post { retryInSoftware() }
            } else if (AguanteDeBuffering.hayQueRendirse(now - bufferingSinceWallMs)) {
                // Se acabó la esperanza: la capa de red ya agotó su presupuesto entero (ver
                // AguanteDeBuffering) y libVLC sigue sin leer un solo byte. Sin esto el reproductor
                // se queda girando para siempre — medido en device: 2 minutos largos de
                // `estado=Buffering pos=0ms` sin un solo aviso al usuario.
                //
                // No hace falta limpiar nada acá: con el estado en Error, checkStall() deja de
                // considerarse activo en el próximo sondeo y resetea el reloj del buffering solo.
                runCatching {
                    android.util.Log.w(
                        "ArkivVlc",
                        "buffering sin avanzar ${now - bufferingSinceWallMs}ms (tope ${AguanteDeBuffering.LIMITE_MS}ms) → error",
                    )
                }
                event = VlcEvent.Error
                invalidateState()
            }
        } else if (bufferingSinceWallMs > 0L) {
            runCatching { android.util.Log.w("ArkivVlc", "REANUDO tras ${now - bufferingSinceWallMs}ms de pausa (pos=${t}ms)") }
            bufferingSinceWallMs = 0L
            // La primera vez que el video corre de verdad: EL número que ve el usuario. Los otros
            // relojes miden tramos ("abrió en", "TOTAL" de loadMagis) y para saber cuánto duró el
            // spinner había que sumarlos a mano entre líneas sueltas de dos etiquetas distintas.
            // Ojo con leer "abrió en" como si fuera esto: la primera imagen aparece ANTES de que el
            // video arranque a caminar, y ese hueco fue de 8,5 s en la peor medición del 2026-08-13.
            if (!corriendoAvisado && mediaCargadaWallMs > 0L) {
                corriendoAvisado = true
                runCatching {
                    android.util.Log.w(
                        "ArkivVlc",
                        "⏱ CORRIENDO a los ${now - mediaCargadaWallMs}ms de loadMedia " +
                            "(primera imagen a los ${if (primerVoutWallMs > 0L) primerVoutWallMs - mediaCargadaWallMs else -1}ms)",
                    )
                }
            }
        }
    }

    /** Carga el ítem actual en HW (camino nuevo): resetea los flags de reintento (software y proxy). */
    private fun loadCurrent(startPositionMs: Long) {
        triedProxy = false
        softwareDesdeWallMs = 0L
        volvioAHardware = false
        // La fuente puede pedir software de entrada (HEVC de magis; ver PlayerSourceTag). Se marca
        // como "software ya intentado" para que el rescate no vuelva a proponer lo mismo, pero la
        // vuelta al hardware sigue disponible por si acá tampoco sale imagen.
        val tag = items.getOrNull(currentIndex)?.localConfiguration?.tag as? PlayerSourceTag
        val porSoftware = tag?.preferirSoftware == true
        triedSoftware = porSoftware
        // Arrancar en software por preferencia cuenta como "estoy en software desde ahora", o el
        // rescate de vuelta al hardware nunca puede dispararse: el de hardware→software se saltea
        // (ya estamos ahí) y el de software→hardware exigía haber llegado por un rescate previo.
        // Sin esto, un software que no da imagen queda sin ninguna salida.
        softwareDesdeWallMs = if (porSoftware) System.currentTimeMillis() else 0L
        loadMedia(hardware = !porSoftware, startPositionMs = startPositionMs)
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
        hardwareActual = hardware
        knownDurationMs = tag?.knownDurationMs ?: 0L
        // Acá se abría una VENTANA para magis: en vez de abrir en 0 y saltar, el proxy servía desde
        // el punto pedido fingiendo ser el archivo entero. Existía porque con el demuxer `ts` nativo
        // libVLC no conocía la duración del TS y su único salto posible era por byte, que dejaba al
        // demuxer con el reloj viejo y mataba la reproducción.
        //
        // Ya no hace falta: magis se demuxea con avformat, que informa la duración (medido en
        // device, `dur=5808498ms` donde antes decía 0), así que el salto por tiempo funciona como en
        // cualquier archivo. Y la ventana no salía gratis — la posición había que calcularla por
        // fracción, cada salto abría una conexión nueva al CDN, y el subtítulo salía corrido tantos
        // minutos como el punto donde se reanudaba, porque para VLC la película empezaba ahí.
        baseOffsetMs = 0L
        mediaCargadaWallMs = System.currentTimeMillis()
        primerVoutWallMs = 0L
        corriendoAvisado = false
        layoutDelVideo = null
        // La racha de "sin imagen" mide ESTA carga, no la anterior. Sin este reset se arrastraba
        // entre medias: medido en device, un capítulo nuevo arrancó con `rachaSinVideoMs=371079` a
        // los 11 s de cargar, heredados de la película anterior. Como el rescate solo exige que la
        // racha supere SIN_VIDEO_MS, con una racha vieja se dispara SIEMPRE a los 10 s de cargar —
        // y ahí no está diagnosticando el decodificador, está castigando a un origen lento: tira la
        // conexión y recarga entero justo cuando archive todavía no soltó el primer byte (que puede
        // tardar 72 s, ver PoliticaOrigen). Con un origen así, eso es recargar para siempre.
        sinVideoDesdeWallMs = 0L
        startPositionApplied = false
        defaultSpuApplied = false // cada ítem/recarga arranca sin decisión de subtítulo aplicada todavía
        userTouchedSpu = false // …hasta que el usuario elija uno a mano (prender o apagar) en ESTE ítem
        defaultAudioApplied = false // y re-evalúa la pista de audio preferida
        idiomaExterno.clear() // las pistas externas del ítem anterior ya no existen
        // OJO: [idiomasSpuDeLaFuente] NO se limpia acá. Se probó y sale mal: quien los asigna es
        // PlayerScreen al publicarse la playlist, y eso pasa en el mismo instante que este loadMedia
        // (medido: ambos a las …14.67). El borrado le ganaba a la asignación y la lista quedaba
        // vacía, con lo que el guardia de cantidad de clasificarSpuConFuente fallaba y ninguna pista
        // se podía clasificar. Se limpia del lado del que asigna, que sí tiene el orden garantizado.
        // Se calcula acá para que también salga en el log de abajo: cuál demuxer terminó eligiendo
        // avformat es lo primero que hay que mirar cuando magis abre y se queda en 0:00.
        val formatoMagis = if (tag?.kind == SourceKind.MAGIS) formatoAvformatDe(uri.toString()) else null
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
            // MAGIS (MPEG-TS): se fuerza el demuxer de ffmpeg en vez del `ts` nativo. Con el nativo,
            // adjuntar un subtítulo externo hace que libVLC cambie el programa activo y se lleve
            // puestas TODAS las pistas del stream (ver PlayerScreen). avformat no expone programas,
            // así que no hay ninguno que perder. Forzarlo es la ÚNICA manera de usarlo: avformat
            // tiene "mpegts" en su lista negra salvo que se lo pidan (demux/avformat/demux.c).
            if (tag?.kind == SourceKind.MAGIS) {
                addOption(":demux=avformat")
                // Y se le DICE qué formato es, en vez de dejar que lo adivine. Portado de la app
                // original de magis, que le pasa a su ijkplayer `setOption(4, "iformat", <formato>)`
                // con el formato que ya trae su backend (yc/C6276a.java en el decompilado). El
                // equivalente en libVLC es `avformat-format`, que le llega a avformat como el
                // `-f mpegts` de ffmpeg: se saltea `av_probe_input_format` y, sobre todo, se saltea
                // el riesgo de que adivine mal justo cuando la primera lectura del CDN llega lenta.
                //
                // El formato sale del ARCHIVO, no de una suposición. Acá decía `mpegts` fijo, con la
                // premisa de que "todo lo que resuelve magis es MPEG-TS": es falsa, el gateway elige
                // el contenedor por título y prefiere mp4 cuando existe. Ver [formatoAvformatDe].
                formatoMagis?.let { addOption(":avformat-format=$it") }
            }
            // Streams web: algunos hosts exigen Referer/UA o devuelven 403.
            tag?.referer?.takeIf { it.isNotBlank() }?.let { addOption(":http-referrer=$it") }
            tag?.userAgent?.takeIf { it.isNotBlank() }?.let { addOption(":http-user-agent=$it") }
        }
        runCatching {
            android.util.Log.w(
                "ArkivVlc",
                "loadMedia player=#$idInstancia hw=$hardware kind=${tag?.kind} referer=${tag?.referer} " +
                    "duracionConocida=${knownDurationMs}ms start=${startPositionMs}ms " +
                    "formato=${formatoMagis ?: "(sondea)"} uri=$uri",
            )
        }
        mediaPlayer.media = media
        media.release()
        if (playWhenReady) mediaPlayer.play()
    }

    /**
     * Aplica la decisión de subtítulos de [SubtitleDecision] (idioma preferido, o apagado si el audio
     * ya quedó en un idioma tuyo). Se re-afirma con reintentos porque libVLC auto-activa la primera
     * pista embebida en cuanto puebla — poco DESPUÉS de Playing — y hay que ganarle esa carrera.
     * Se detiene apenas el usuario elige un subtítulo a mano ([userTouchedSpu]).
     */
    private fun applyPreferredSpu(retries: Int) {
        if (userTouchedSpu) return
        val audio = vlcAudioTracks()
        // OJO: acá NO va un "todavía no hay pistas de audio → no decidas nada". Se probó, con la idea
        // de evitar un parpadeo al arrancar, y sale caro y gratis a la vez: sin audio a la vista el
        // nombre queda en null y SubtitleDecision APAGA, que es exactamente lo que hay que hacer en
        // el primer tick — libVLC ya auto-activó la primera pista embebida y hay que sacarla. Saltear
        // la decisión ahí deja ese subtítulo en pantalla hasta que el audio puebla. Y no evita ningún
        // parpadeo: PRENDER exige un audio extranjero, o sea una lista YA poblada, caso en el que ese
        // guardia ni se activa. El parpadeo real es otro y se corta desde applyPreferredAudio.
        val spu = vlcSpuTracks()
        val audioName = audio.firstOrNull { it.first == currentAudioTrack() }?.second
        val target = SubtitleDecision.decide(audioName, spu, langPrefs) { clasificarSpuConFuente(it, spu) }
        if (currentSpuTrack() != target) {
            runCatching { android.util.Log.w("ArkivVlc", "auto-spu -> id=$target de ${spu.map { it.second }}") }
            runCatching { mediaPlayer.spuTrack = target }
        }
        if (retries > 0) handler.postDelayed({ applyPreferredSpu(retries - 1) }, 350)
    }

    /**
     * Re-aplica la preferencia de idioma al ítem que YA está sonando, sin recargarlo.
     *
     * Hace falta porque los dos pases automáticos corren UNA sola vez por carga (los dispara el
     * evento Playing), y volver a darle play a un capítulo que ya está en el controller no recarga
     * nada: `MediaReusePolicy` elige REUSAR_ACTUAL —correctamente, recargar un TS de magis cuesta
     * segundos de sonda y re-buffer—, así que sin esto un cambio en Ajustes no se veía hasta la
     * próxima carga desde cero. Con esto se siente inmediato.
     *
     * `retries = 0`: acá las pistas ya poblaron hace rato, no hay ninguna carrera que esperar.
     *
     * El subtítulo elegido A MANO sobrevive: [applyPreferredSpu] corta por [userTouchedSpu] y esto no
     * lo toca. Es lo menos sorprendente — tu última acción directa sobre el subtítulo manda sobre el
     * automático, y de paso evita que la promoción (que al elegir a mano ESCRIBE la preferencia, y por
     * lo tanto vuelve a entrar por acá) se pise a sí misma.
     */
    fun reaplicarIdiomaAlItemActual() {
        handler.post {
            applyPreferredAudio(retries = 0)
            applyPreferredSpu(retries = 0)
        }
    }

    /**
     * Idioma de una pista de subtítulo. Antes que nada mira lo que declaró la fuente ([idiomaExterno]):
     * una fuente web adjunta su subtítulo por URL del CDN (`…/9f8a7b.vtt`), que no dice el idioma por
     * ningún lado, pero lo manda aparte. Sin esto esa pista queda `UNKNOWN` y no se puede elegir.
     */
    private fun clasificarSpu(nombre: String): TrackLang {
        val n = nombre.lowercase()
        // UNKNOWN acá es una clave QUEMADA por ambigua (ver recordarIdioma), no un idioma: se saltea
        // para que la clasificación siga de largo hasta el nombre de archivo.
        idiomaExterno.entries
            .firstOrNull { (clave, lang) -> lang != TrackLang.UNKNOWN && clave in n }
            ?.let { return it.value }
        return LangTokens.classifyFileName(nombre)
    }

    /**
     * [clasificarSpu] y, si el nombre no dice nada, el idioma que declaró la fuente para esa POSICIÓN
     * ([idiomasSpuDeLaFuente]). Es la única vía para el MPEG-TS de magis, cuyas pistas llegan sin
     * idioma en ningún campo.
     *
     * El mapeo cubre las PRIMERAS [idiomasSpuDeLaFuente].size pistas ordenadas por id, no todas. Las
     * del contenedor las numera el demuxer al abrir y quedan con los ids más bajos; cualquier pista
     * externa (`addSlave`) se agrega después y con un id mayor. Exigir que los totales coincidieran
     * —como se hizo primero— dejaba de mapear en cuanto aparecía una pista de más: visto en device,
     * una cuarta pista contra los tres idiomas del portal y el auto-subtítulo se apagaba en silencio.
     *
     * Fuera de esas primeras N no se adivina: se devuelve UNKNOWN, que deja el subtítulo apagado y al
     * usuario eligiendo a mano. Mostrar un idioma equivocado sería peor que no prender nada.
     */
    private fun clasificarSpuConFuente(nombre: String, spu: List<Pair<Int, String>>): TrackLang {
        val directo = clasificarSpu(nombre)
        if (directo != TrackLang.UNKNOWN) return directo
        val declarados = idiomasSpuDeLaFuente
        if (declarados.isEmpty()) return TrackLang.UNKNOWN
        val reales = spu.filter { it.first >= 0 }.sortedBy { it.first }
        val i = reales.indexOfFirst { it.second == nombre }
        if (i < 0 || i >= declarados.size) return TrackLang.UNKNOWN
        return LangTokens.classifyCode(declarados[i])
    }

    /**
     * Selecciona la pista de audio según [langPrefs] (Latino>Castellano>Spanish>Dual por defecto, ver
     * [PlaybackPrefs.audioLangs]). Corre en el looper. Si aún no hay >1 pista (VLC las expone poco
     * después de Playing), reintenta. Si ninguna pista coincide con la preferencia, deja la de VLC
     * (no toca nada). Ganancia clave para MKV DUAL.
     */
    private fun applyPreferredAudio(retries: Int) {
        val tracks = vlcAudioTracks()
        if (tracks.count { it.first >= 0 } <= 1) {
            if (retries > 0) handler.postDelayed({ applyPreferredAudio(retries - 1) }, 400)
            return
        }
        val id = TrackSelector.select(tracks, langPrefs.audioLangs) ?: return
        if (id != currentAudioTrack()) {
            runCatching { android.util.Log.w("ArkivVlc", "auto-audio -> id=$id de ${tracks.map { it.second }}") }
            setVlcAudioTrack(id)
            // Cambiar el audio INVALIDA la decisión de subtítulos: se decide por el idioma de la pista
            // que quedó SONANDO (ver SubtitleDecision), así que cualquier decisión tomada antes de
            // este cambio se tomó con la entrada vieja. Sin este re-pase hay un parpadeo real medido
            // en la cadencia de los dos pases, que van cada uno por su lado: el de audio corre a los
            // 200 ms con las pistas todavía sin poblar y se duerme hasta los 600 ms; las pistas
            // pueblan a los ~300 ms; el de subtítulos corre a los 400 ms, lee el inglés que VLC dejó
            // por defecto, lo ve extranjero y PRENDE el subtítulo en español; el audio recién pasa a
            // latino a los 600 ms y el pase de subtítulos solo se corrige en su tick siguiente, a los
            // 750 ms. Son ~350 ms de subtítulo visible de gusto en cualquier MKV dual.
            // Va posteado al looper (nunca se toca el player desde el thread de eventos) y respeta el
            // corte por `userTouchedSpu` que ya hace applyPreferredSpu: si el usuario eligió a mano,
            // esto no le pisa nada.
            handler.post { applyPreferredSpu(retries = 0) }
        }
    }

    /**
     * Aplica la posición de arranque por FRACCIÓN cuando VLC no conoce la duración (TS por HTTP).
     * Si sí la conoce, `:start-time` ya hizo su trabajo y esto no toca nada.
     */
    private fun aplicarArranquePorFraccion(intentos: Int = 12) {
        val lengthMs = runCatching { mediaPlayer.length }.getOrDefault(0L)
        val fraccion = UnknownLengthPolicy.seekFraction(currentStartMs, lengthMs, knownDurationMs) ?: return
        // Esperar a que el tiempo AVANCE antes de saltar. Saltar sobre un TS que todavía no decodificó
        // su primer frame deja el demuxer a medias: la posición se queda en 0, el video se muere y
        // VLC bufferea al 0% para siempre (visto en device). Con el tiempo ya corriendo, el salto cae
        // donde debe.
        // No basta con que el tiempo sea >0: con 69 ms (el primer frame) el salto también dejaba el
        // demuxer colgado. Se exige reproducción ya asentada.
        val t = runCatching { mediaPlayer.time }.getOrDefault(0L)
        if (t < ARRANQUE_MIN_MS) {
            if (intentos > 0) {
                handler.postDelayed({ aplicarArranquePorFraccion(intentos - 1) }, 400)
            } else {
                runCatching {
                    android.util.Log.w("ArkivVlc", "arranque descartado: VLC nunca movió el tiempo")
                }
            }
            return
        }
        runCatching {
            android.util.Log.w(
                "ArkivVlc",
                "arranque sin duracion: ${currentStartMs}ms de ${knownDurationMs}ms → position=$fraccion",
            )
        }
        runCatching { mediaPlayer.position = fraccion }
        runCatching {
            android.util.Log.w("ArkivVlc", "arranque aplicado: time=${mediaPlayer.time}ms")
        }
    }

    /** Reintento tras EncounteredError: recarga el mismo ítem en software, retomando donde iba. */
    private fun retryInSoftware() {
        val resumeAt = posicionParaRecargar()
        runCatching { mediaPlayer.stop() }
        softwareDesdeWallMs = System.currentTimeMillis()
        loadMedia(hardware = false, startPositionMs = resumeAt)
    }

    /**
     * Vuelta al hardware cuando el software no da imagen. Ver el chequeo en [checkStall]: el
     * software es el respaldo para formatos que el hardware acepta y no puede, pero con HEVC en un
     * teléfono el respaldo es peor que el problema.
     */
    private fun reintentarEnHardware() {
        val resumeAt = posicionParaRecargar()
        runCatching { mediaPlayer.stop() }
        softwareDesdeWallMs = 0L
        loadMedia(hardware = true, startPositionMs = resumeAt)
    }

    /**
     * Dónde retomar al recargar. Dentro de una ventana el reloj de VLC no ubica (ver
     * [VentanaDeArchivo.posicionAbsolutaMs]): tomarlo tal cual mandaría la recarga a otra parte de
     * la película, así que se convierte a tiempo absoluto igual que lo hace la barra.
     */
    private fun posicionParaRecargar(): Long {
        if (baseOffsetMs > 0L) {
            return VentanaDeArchivo.posicionAbsolutaMs(
                baseOffsetMs,
                runCatching { mediaPlayer.position }.getOrDefault(0f),
                UnknownLengthPolicy.effectiveDurationMs(
                    runCatching { mediaPlayer.length }.getOrDefault(0L),
                    knownDurationMs,
                ),
            )
        }
        val t = runCatching { mediaPlayer.time }.getOrDefault(0L).coerceAtLeast(0L)
        return if (t > 0) t else currentStartMs
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

    /**
     * ¿La superficie de ahora es OTRA que aquella en la que este media dio imagen?
     *
     * Dicho de otra forma: ¿el reproductor volvió sobre una pantalla nueva? Es lo que decide si se
     * puede reusar el media o hay que recargarlo, y el porqué —con los números— está en
     * [MediaReusePolicy.decide]: reusarlo con una superficie nueva mata al decodificador HEVC de
     * este aparato, y recargarlo no falló nunca.
     *
     * Falso mientras el media todavía no dio ninguna imagen: ahí no hay nada que comparar.
     */
    fun superficieDistintaALaDelVideo(): Boolean {
        val deLaImagen = layoutDelVideo ?: return false
        return deLaImagen != layoutEnganchado
    }

    /** ¿VLC está pintando video ahora? Falso mientras reconstruye el vout al volver de segundo plano. */
    fun hasVideoOutput(): Boolean = voutTracker.hayVideo()

    /**
     * ¿Este media se cargó y TODAVÍA no dio su primera imagen?
     *
     * Existe por el "arranca negro y con sonido" de magis: libVLC llega a `Playing` y suelta el
     * audio en cuanto tiene con qué, pero la primera imagen puede tardar bastante más —el
     * decodificador HEVC del Fire Stick tiene que arrancar— y en ese hueco `playbackState` ya NO es
     * `STATE_BUFFERING`, así que la pantalla se queda sin spinner y sin imagen: negro pelado con
     * audio, que se ve igual que un cuelgue. Medido el 2026-08-13 en el Fire TV, entre la primera
     * imagen y el video caminando llegó a haber 8,5 s.
     *
     * Dos guardias, porque acá el modo de fallar sería dejar el spinner puesto para siempre:
     *
     * 1. **Contenido sin video.** Si libVLC ya pobló las pistas y no hay ninguna de video, no hay
     *    imagen que esperar. Se pregunta por `> 0` en audio y no por "la lista está vacía": al
     *    principio TODAS las cuentas son 0 y eso no significa que no haya video, significa que
     *    todavía no sabe.
     * 2. **Un tope de tiempo igual.** Si algo sale distinto a lo previsto, a los [ESPERA_IMAGEN_MS]
     *    se muestra lo que haya. Más vale un negro que un spinner eterno encima de un video que sí
     *    estaba reproduciendo.
     */
    fun esperandoPrimeraImagen(): Boolean = EsperaDePrimeraImagen.hayQueEsperar(
        cargadoHaceMs = if (mediaCargadaWallMs > 0L) System.currentTimeMillis() - mediaCargadaWallMs else -1L,
        huboImagen = primerVoutWallMs > 0L,
        hayVideoAhora = voutTracker.hayVideo(),
        pistasDeVideo = runCatching { mediaPlayer.videoTracksCount }.getOrDefault(0),
        pistasDeAudio = runCatching { mediaPlayer.audioTracksCount }.getOrDefault(0),
    )

    /** Identidad del layout enganchado ahora mismo, para el diagnóstico de la pantalla negra. */
    @Volatile private var layoutEnganchado: String? = null

    /** El layout enganchado, para poder llegar al TextureView donde VLC está pintando. */
    @Volatile private var layoutActual: VLCVideoLayout? = null

    /** El TextureView donde libVLC está pintando, o null si todavía no hay salida de video. */
    fun textureViewActual(): android.view.TextureView? = textureViewDe(layoutActual)

    /**
     * El TextureView que haya adentro de [raiz].
     *
     * Se busca recorriendo el árbol porque VLCVideoLayout no lo expone: sus ids son internos de la
     * librería y no hay API pública para pedírselo.
     *
     * Se expone aparte de [textureViewActual] porque al salir del reproductor el layout se suelta
     * (`onRelease` del AndroidView → [detachVideo]) y este player deja de tener por dónde llegar al
     * view; la pantalla, en cambio, sigue con su propio layout en la mano y puede pasarlo acá para
     * capturar el último frame antes de irse.
     */
    fun textureViewDe(raiz: android.view.View?): android.view.TextureView? {
        raiz ?: return null
        val pendientes = ArrayDeque<android.view.View>()
        pendientes.add(raiz)
        while (pendientes.isNotEmpty()) {
            when (val v = pendientes.removeFirst()) {
                is android.view.TextureView -> return v
                is android.view.ViewGroup -> for (i in 0 until v.childCount) pendientes.add(v.getChildAt(i))
            }
        }
        return null
    }

    private fun idDe(layout: VLCVideoLayout) = Integer.toHexString(System.identityHashCode(layout))

    /**
     * @param motivo quién pide el enganche (factory de la pantalla, ON_START del ciclo de vida…).
     *
     * El `motivo` y la identidad del layout no son adorno: el síntoma de la pantalla negra es VLC
     * pintando sobre una Surface ya destruida (`BufferQueue has been abandoned` + `EGL_BAD_ALLOC`),
     * y para saber por qué hay dos explicaciones que en el log se ven idénticas sin esto —que se
     * reenganche un layout viejo, o que el onRelease de la pantalla saliente desarme el attach de
     * la entrante—. Con quién llama, qué layout entra y si ese layout sigue en la ventana, se
     * distinguen de una sola lectura.
     */
    fun attachVideo(layout: VLCVideoLayout, motivo: String) {
        android.util.Log.w(
            "ArkivVout",
            "ATTACH motivo=$motivo layout=#${idDe(layout)} enVentana=${layout.isAttachedToWindow} " +
                "previo=#${layoutEnganchado ?: "-"} hayVideo=${voutTracker.hayVideo()}",
        )
        // attachViews() PISA el VideoHelper anterior sin liberarlo (fuga + callbacks viejos sobre el
        // holder), así que soltamos primero. detachViews() es no-op si no había nada enganchado.
        runCatching { mediaPlayer.detachViews() }
        // El último `true`/`false` elige TextureView o SurfaceView para pintar. Ver RENDER_POR_TEXTURE.
        runCatching { mediaPlayer.attachViews(layout, null, true, RENDER_POR_TEXTURE) }
        layoutEnganchado = idDe(layout)
        layoutActual = layout
        // La superficie no está lista en el mismo instante del attach (el callback del holder llega
        // después), así que se le da un respiro a VLC para que rehaga el vout por su cuenta y recién
        // ahí se lo empuja. Se vuelve a preguntar al disparar: si en el intervalo apareció la imagen,
        // no se toca nada — el empujón apaga y prende la pista de video y se vería como un parpadeo.
        handler.postDelayed({
            if (voutTracker.necesitaEmpujon()) forzarReconstruccionDelVout()
        }, REBUILD_VOUT_DELAY_MS)
    }

    fun detachVideo(motivo: String) {
        android.util.Log.w(
            "ArkivVout",
            "DETACH motivo=$motivo soltando=#${layoutEnganchado ?: "-"} hayVideo=${voutTracker.hayVideo()}",
        )
        runCatching { mediaPlayer.detachViews() }
        voutTracker.onDetach()
        layoutEnganchado = null
        // Se limpia JUNTO a layoutEnganchado: este player es un singleton de proceso y el
        // VLCVideoLayout se construye con el contexto de la Activity. Dejar acá el layout ya
        // soltado retenía la Activity entera mientras el reproductor estaba cerrado.
        layoutActual = null
    }

    /**
     * Obliga a libVLC a reconstruir la salida de video apagando y prendiendo la pista.
     *
     * `attachViews()` sobre un media que ya viene reproduciendo NO recrea el display: VLC sigue
     * convencido de que su vout está vivo (nunca emite el `Vout 0` al perderse la superficie) y la
     * superficie nueva se queda sin nada — imagen negra con el audio andando. Cambiar la pista de
     * video es lo que fuerza el ciclo de destrucción y creación del vout.
     */
    private fun forzarReconstruccionDelVout() {
        val pista = runCatching { mediaPlayer.videoTrack }.getOrDefault(-1)
        if (pista < 0) {
            android.util.Log.w("ArkivVout", "EMPUJON omitido: no hay pista de video (pista=$pista)")
            return
        }
        android.util.Log.w("ArkivVout", "EMPUJON reconstruyendo vout (pista=$pista)")
        runCatching { mediaPlayer.setVideoTrack(-1) }
        runCatching { mediaPlayer.setVideoTrack(pista) }
    }

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
    fun setVlcSpuTrack(id: Int) {
        // CUALQUIER elección a mano corta la re-afirmación automática, prenda o apague. Antes solo
        // cortaba con id>=0 porque el pase viejo (applyDefaultSpuOff) solo sabía apagar: un usuario
        // que elegía "Desactivar" y el pase de fondo estaban de acuerdo, así que no hacía falta
        // frenar nada. applyPreferredSpu ya no solo apaga — puede volver a PRENDER una pista en tu
        // idioma — así que si no se corta acá, elegir "Desactivar" a mano se revierte solo en el
        // próximo tick (≤350 ms) porque sigue viendo audio extranjero + subtítulo disponible.
        userTouchedSpu = true
        runCatching { mediaPlayer.setSpuTrack(id) }
    }
    /**
     * Agrega una pista de subtítulo externa. [byUser] distingue la elección del usuario (OpenSubtitles)
     * de la carga automática (los `.srt` sueltos del torrent, los del portal de magis): la automática
     * no corta la selección por idioma — la pista nueva entra como candidata y `applyPreferredSpu`
     * decide, en vez de quedar forzada.
     *
     * El `select` va en true incluso en la carga automática, que es el comportamiento que esto tenía
     * antes de existir [byUser]: el re-pase de abajo lo corrige enseguida si la decisión dice que no
     * van, así que no queda forzado. Se probó en false y no cambia nada de lo que importa.
     *
     * OJO — en magis el slave NO se materializa, con true ni con false: `addSlave` devuelve sin
     * excepción, las URLs del portal contestan 200 con SRT válido, y la pista no aparece nunca
     * (`pistas=…/s4` = Disable + las tres embebidas del TS, medido en device). No importa: ese mismo
     * contenido YA viene embebido en el TS, y lo que se aprovecha del portal es la lista de idiomas
     * (ver [idiomasSpuDeLaFuente]), no los archivos.
     *
     * [lang] es el idioma que declaró la fuente, para cuando la URL no lo dice (una fuente web adjunta
     * `…/9f8a7b.vtt` a secas). Los `.srt` del torrent y los de OpenSubtitles ya lo llevan en el nombre
     * del archivo y no lo necesitan.
     *
     * OJO con el MPEG-TS: esto solo es seguro porque magis se demuxea con avformat (ver loadMedia).
     * Con el demuxer `ts` nativo, adjuntar un subtítulo externo le cambia a libVLC el programa activo
     * y se lleva puestas TODAS las pistas del stream.
     */
    fun addSubtitleSlave(uri: Uri, byUser: Boolean = true, lang: String = "") {
        if (byUser) userTouchedSpu = true
        recordarIdioma(uri, lang)
        runCatching { mediaPlayer.addSlave(IMedia.Slave.Type.Subtitle, uri, true) }
        // Recién cargada, la pista todavía no figura: se re-decide un instante después. Este re-pase
        // es el que deshace el `select = true` de arriba cuando la decisión dice que no van.
        if (!byUser) handler.postDelayed({ applyPreferredSpu(retries = 2) }, 300)
        // NO corregir acá el desfase de la ventana con `spuDelay`. Se probó y congela la
        // reproducción: con un desfase de −29 min VLC se queda clavado en `pos=0` con el buffer
        // subiendo de a gotas hasta que salta el rescate de "estancado en 0" (medido en device,
        // dos veces seguidas). Pedirle un subtítulo casi media hora antes de su marca le rompe el
        // reloj de entrada. El desfase hay que sacarlo de raíz: que magis no abra por ventana.
    }

    /**
     * Guarda el idioma declarado de una pista externa contra trozos de su URI, porque libVLC bautiza
     * la pista con la ruta y es lo único que se puede reconocer después. Se guarda la URI entera y
     * además su último tramo: según el origen, libVLC muestra una o el otro.
     *
     * El último tramo puede CHOCAR entre subtítulos: una fuente web que sirve `…/es/1.vtt` y
     * `…/en/1.vtt` deja los dos en la clave `1.vtt`, y el segundo registro se quedaba con la clave
     * pisando al primero. Una pista bautizada solo con el tramo salía entonces con el idioma del otro
     * subtítulo — mal, y con seguridad. Ante un choque la clave se QUEMA con UNKNOWN (sigue ocupada,
     * así que un tercer registro tampoco la revive) y clasificarSpu la saltea: se cae al nombre de
     * archivo, que a lo sumo no sabe. La clave de la URI entera, que es única, no se toca.
     */
    private fun recordarIdioma(uri: Uri, lang: String) {
        val bucket = LangTokens.classifyCode(lang)
        if (bucket == TrackLang.UNKNOWN) return
        val completa = uri.toString().lowercase()
        if (completa.isBlank()) return
        idiomaExterno[completa] = bucket
        val tramo = completa.substringAfterLast('/').takeIf { it.isNotBlank() && it != completa } ?: return
        val previo = idiomaExterno[tramo]
        idiomaExterno[tramo] = if (previo == null || previo == bucket) bucket else TrackLang.UNKNOWN
    }

    private companion object {
        // Respiro tras enganchar una superficie antes de forzar la reconstrucción del vout: le da
        // margen a VLC para rehacerlo solo (y así no parpadear de gusto) sin que la espera se note.
        const val REBUILD_VOUT_DELAY_MS = 400L
        /**
         * Si libVLC pinta en un `TextureView` (true) o en el `SurfaceView` que usa por defecto.
         *
         * Está en `true` desde que se hicieron las miniaturas de frame: de un `SurfaceView` no se
         * pueden leer los píxeles y de un `TextureView` sí (ver `textureViewActual`). O sea que
         * ponerlo en `false` DEJA SIN MINIATURAS a la app — no es un ajuste gratuito.
         *
         * Se saca a constante porque hay una pregunta abierta que solo se contesta midiendo: al
         * recrearse la pantalla del reproductor, el decodificador HEVC se reinicia (`MediaCodec […]
         * setting surface generation`) y hay que esperar al próximo fotograma clave para volver a
         * ver imagen — medido en el Fire TV el 2026-08-13 entre 2,2 s y 6,5 s de pantalla negra con
         * el audio andando. Ese reinicio lo dispara recibir una superficie NUEVA, y eso pasa con las
         * dos clases de vista, así que el TextureView no era sospechoso obvio.
         *
         * SE PROBÓ en `false` el 2026-08-13 y NO sirvió: con SurfaceView el decodificador se reinició
         * igual —dos `setting surface generation` seguidos en el mismo arranque— y el negro al
         * reentrar siguió estando. O sea que la clase de vista no es la causa; la causa es recibir
         * una superficie nueva, y eso pasa siempre que se recrea la pantalla. Queda en `true`, que
         * además es lo que las miniaturas necesitan.
         *
         * De paso, algo que cuesta al diagnosticar: con SurfaceView `adb exec-out screencap` captura
         * NEGRO aunque en la tele se vea bien, porque el video va en una capa de hardware aparte.
         */
        const val RENDER_POR_TEXTURE = true

        const val STALL_POLL_MS = 500L  // cada cuánto sondea el watcher de estancamiento
        const val STALL_MS = 900L       // tiempo sin avanzar (queriendo reproducir) para marcar buffering
        // Cuánto se le aguanta al hardware antes de darlo por colgado y caer a software.
        //
        // 12 s, y la tentación de subirlo ya se probó y salió mal. El razonamiento era: el CDN de
        // magis tarda hasta 20 s en soltar el primer byte, así que 12 s confunde "red lenta" con
        // "códec roto". Se subió a 25 s y en device fue PEOR: con estos HEVC el decodificador por
        // hardware de un teléfono falla al arrancar bastante seguido —también abriendo en el byte
        // 0, o sea que no es cosa de reanudar— y el que rescata la reproducción es precisamente
        // este cambio a software (medido: video a los 0,65 s de recargar, con los datos que ya
        // venían llegando desde hacía 29 s). Subir el umbral solo alarga el negro.
        //
        // Equivocarse por rescatar de más ahora es barato: si el software tampoco da imagen se
        // vuelve al hardware (ver SIN_VIDEO_EN_SOFTWARE_MS). Antes ese error no tenía vuelta.
        const val STALL_SOFTWARE_MS = 12_000L
        // Y si aun así se cayó a software y ahí no sale imagen, volver al hardware: es preferible
        // reintentar el único decodificador capaz que quedarse en negro para siempre.
        const val SIN_VIDEO_EN_SOFTWARE_MS = 8_000L
        // Reproducción ya asentada antes de saltar a la posición guardada (ver aplicarArranquePorFraccion).
        const val ARRANQUE_MIN_MS = 2_000L
        // Solo lo que sale de nuestro proxy local se puede ventanear: el `f=` lo entiende él.
        const val PROXY_LOCAL = "http://127.0.0.1"
        // Sin salida de video en este tiempo = el decodificador no está produciendo, pase lo que
        // pase con el reloj. Es el detector bueno: el modo de fallo real de estos HEVC no es que el
        // tiempo se clave, es que se dispara.
        const val SIN_VIDEO_MS = 10_000L
        // Frenos de la reapertura de ventana. Un salto de menos de 5 s no justifica tirar la
        // conexión, y dos reaperturas en menos de 1,5 s son siempre una ráfaga, no una intención.
        const val SALTO_MINIMO_MS = 5_000L
        const val REAPERTURA_MINIMA_MS = 1_500L

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
