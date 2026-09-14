package com.arkiv.player.ui.player

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.net.Uri
import android.view.KeyEvent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Brightness2
import androidx.compose.material.icons.filled.BrightnessHigh
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.ClosedCaptionOff
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import com.arkiv.player.ui.live.DrawerAction
import com.arkiv.player.ui.live.DrawerDpad
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.text.CueGroup
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.SubtitleView
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.arkiv.player.cast.CastProgress
import com.arkiv.player.cast.localAudioFormat
import com.arkiv.player.cast.localVideoFormat
import com.arkiv.player.data.ChapterMarker
import com.arkiv.player.ui.tv.library.SAFE_H
import com.arkiv.player.ui.tv.library.SAFE_V
import com.arkiv.player.playback.AutoAdvance
import com.arkiv.player.playback.DecoderWatchdog
import com.arkiv.player.playback.FirstFrameWait
import com.arkiv.player.playback.LoadedMedia
import com.arkiv.player.playback.MediaReusePolicy
import com.arkiv.player.playback.NowPlaying
import com.arkiv.player.playback.PlaybackEngine
import com.arkiv.player.playback.PlaybackService
import com.arkiv.player.playback.PlayerSource
import com.arkiv.player.playback.PlayerSourceTag
import com.arkiv.player.playback.ReloadPositionPolicy
import com.arkiv.player.playback.SourceKind
import com.arkiv.player.playback.VideoAttachPolicy
import com.arkiv.player.playback.setPlayerSourceTag
import com.arkiv.player.playback.toIpcBundle
import com.arkiv.player.ui.formatDuration
import com.arkiv.player.ui.rememberGraph
import com.arkiv.player.ui.theme.ArkivRed
import com.arkiv.player.ui.theme.ArkivSurface
import com.arkiv.player.ui.theme.ArkivTextSecondary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

private fun Context.findActivity(): Activity? {
    var ctx: Context? = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}

/** Pasos de velocidad de reproducción (portado de TorrentPlayerScreen). */

/** Pasos de zoom: 0 = ajustar a pantalla; >0 = crop que recorta las barras negras (ver PlayerGestos). */

/** Swipe vertical mínimo (px) para que el modo vivo (Tarea 14) lo tome como zapping en el teléfono. */
private const val UMBRAL_ZAP_PX = 80f

// Controles ocultos en la barra superior del TELÉFONO: llegó a tener 8 elementos y se veían
// amontonados. El código se conserva —no se borra— para poder reactivarlos con un solo cambio acá.
// En TV ninguno de los tres existía. Los subtítulos no se ocultan: se movieron abajo a la derecha.
private const val MOSTRAR_MARCADORES_EN_TELEFONO = false
private const val MOSTRAR_VELOCIDAD_Y_ZOOM_EN_TELEFONO = true

// Night mode: the black veil sits ON TOP of the video, with opacity level/DIM_MAX_LEVEL -- 0 =
// normal brightness (no veil), DIM_MAX_LEVEL = fully black. The screen's real brightness isn't
// used because on the Fire TV Stick it's a no-op (the TV controls brightness, not Android), and
// libVLC 3.x didn't expose the `adjust` filter. Changing the step's granularity = changing only
// this line.

/**
 * Cuánto se espera, sin tocar nada, antes de confirmar una ráfaga de saltos incrementales (ver
 * `seekBy`). Corto a propósito: una pulsación suelta sigue sintiéndose inmediata y solo se fusionan
 * las ráfagas, que es donde estaba el costo — un Range request y su rebuffer por cada pulsación.
 */
private const val SEEK_INCREMENTAL_DEBOUNCE_MS = 350L


/** Construye los MediaItem locales para el controller, propagando el tag de fuente/marcadores. */
private fun localMediaItems(items: List<PlayerData>): List<MediaItem> = items.map { d ->
    val tag = PlayerSourceTag(
        kind = d.kind,
        openingStartMs = d.openingStartMs,
        openingEndMs = d.openingEndMs,
        endingStartMs = d.endingStartMs,
        castUrl = d.castUrl,
        referer = d.referer,
        userAgent = d.userAgent,
        proxyUrl = d.proxyUrl,
        preferSoftware = d.preferirSoftware,
    )
    MediaItem.Builder()
        .setUri(d.mediaUrl)
        .setMediaId(d.episodeId)
        // The URI in localConfiguration is LOST crossing MediaController→MediaSession, so we keep
        // it in requestMetadata too (which does survive the IPC) for
        // PlaybackService.MediaItemResolverCallback.onAddMediaItems to rebuild on the session.
        // The TAG (kind/referer/etc.) is LOST crossing controller→session just like the URI, so we
        // keep it in extras (which DO survive the IPC, see PlayerSourceTagIpc) to rebuild it in
        // PlaybackService.
        .setRequestMetadata(
            MediaItem.RequestMetadata.Builder().setMediaUri(Uri.parse(d.mediaUrl))
                .setExtras(tag.toIpcBundle())
                .build(),
        )
        .setPlayerSourceTag(tag)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(d.title).setArtist(d.subtitle)
                .apply { if (d.artworkUrl.isNotEmpty()) setArtworkUri(Uri.parse(d.artworkUrl)) }
                .build(),
        )
        .build()
}

/**
 * The exact player operations the decoder watchdog's software reload performs (see
 * [reloadInSoftware]), narrowed from `Player` so a test can pin the call ORDER with a small
 * recording fake instead of implementing all of `Player`'s members. [MediaController] satisfies it
 * through [asSoftwareReloadPlayer].
 */
internal interface SoftwareReloadPlayer {
    fun stop()
    fun setMediaItems(mediaItems: List<MediaItem>, startIndex: Int, startPositionMs: Long)
    fun prepare()
    var playWhenReady: Boolean
}

private fun MediaController.asSoftwareReloadPlayer(): SoftwareReloadPlayer =
    object : SoftwareReloadPlayer {
        override fun stop() = this@asSoftwareReloadPlayer.stop()
        override fun setMediaItems(mediaItems: List<MediaItem>, startIndex: Int, startPositionMs: Long) =
            this@asSoftwareReloadPlayer.setMediaItems(mediaItems, startIndex, startPositionMs)
        override fun prepare() = this@asSoftwareReloadPlayer.prepare()
        override var playWhenReady: Boolean
            get() = this@asSoftwareReloadPlayer.playWhenReady
            set(value) { this@asSoftwareReloadPlayer.playWhenReady = value }
    }

/**
 * Performs the decoder watchdog's software reload on [player]: `stop()` BEFORE `setMediaItems(…)`,
 * then `prepare()` at the preserved position.
 *
 * The order matters: on a media-item change media3 1.5.1 KEEPS the video codec (it flushes and
 * re-uses it -- `releaseCodec()` only runs from the renderer's reset) and `prepare()` returns
 * immediately outside `STATE_IDLE` -- and this failure sits in `BUFFERING` -- so the software-first
 * selector would never be consulted without `stop()` first. `stop()` resets the renderers, which
 * releases the codec; the reload then starts from `IDLE` and the selector runs again. The position
 * is not lost: it travels to `setMediaItems`. This was a Critical review finding: without the
 * ordering, the rescue is a silent no-op. Split out from `watchLocalDecoder` so a recording fake
 * [SoftwareReloadPlayer] can pin it without needing the whole composition.
 */
internal fun reloadInSoftware(
    player: SoftwareReloadPlayer,
    mediaItems: List<MediaItem>,
    startIndex: Int,
    startPositionMs: Long,
) {
    player.stop()
    player.setMediaItems(mediaItems, startIndex, startPositionMs)
    player.prepare()
    player.playWhenReady = true
}

@Composable
private fun rememberMediaController(): MediaController? {
    val context = LocalContext.current
    var controller by remember { mutableStateOf<MediaController?>(null) }
    DisposableEffect(Unit) {
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        future.addListener(
            { runCatching { controller = future.get() } },
            ContextCompat.getMainExecutor(context),
        )
        onDispose {
            controller = null
            MediaController.releaseFuture(future)
        }
    }
    return controller
}

/** Numera las composiciones del reproductor. Ver el DisposableEffect de `pantallaId`. */
private val PANTALLA_SEQ = java.util.concurrent.atomic.AtomicInteger(0)

@OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(
    episodeId: String,
    onBack: () -> Unit,
    onOpenEpisodes: () -> Unit,
    onNextEpisode: (String) -> Unit = {},
    isTv: Boolean = false,
) {
    val controller = rememberMediaController()
    // The service's ExoPlayer, used only to bind the local video surface (see PlaybackEngine).
    // By the time the controller connects, the service exists.
    val serviceExo = PlaybackEngine.player
    if (controller == null || serviceExo == null) {
        Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = Color.White)
        }
        return
    }
    PlayerContent(episodeId, onBack, onOpenEpisodes, onNextEpisode, controller, serviceExo, isTv)
}

@OptIn(UnstableApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun PlayerContent(
    episodeId: String,
    onBack: () -> Unit,
    onOpenEpisodes: () -> Unit,
    onNextEpisode: (String) -> Unit,
    controller: MediaController,
    serviceExo: ExoPlayer,
    isTv: Boolean,
) {
    val graph = rememberGraph()
    val context = LocalContext.current
    val activity = context.findActivity()
    val scope = rememberCoroutineScope()
    val castContext = remember { graph.castContext }
    val dlna = remember { graph.dlna }

    // Mantener la pantalla encendida al reproducir (en TV lo maneja el root de la app).
    val view = LocalView.current
    if (!isTv) {
        DisposableEffect(Unit) {
            view.keepScreenOn = true
            onDispose { view.keepScreenOn = false }
        }
    }

    val vm: PlayerViewModel = viewModel(
        factory = viewModelFactory {
            initializer {
                PlayerViewModel(
                    graph.repository, graph.archiveCacheProxy,
                    graph.localLibrary, graph.localFileServer, graph.frameCapturer,
                    graph.liveController, graph.database.liveRecentDao(),
                    esTelevision = isTv,
                    fuente = graph.fuenteDeContenido,
                    dituFuente = graph.dituFuente,
                    hayCuentaDeMagis = { graph.magisSession.hasAccountLinked },
                    datosCuriosos = graph.datosCuriosos,
                )
            }
        },
    )
    val playlist by vm.playlist.collectAsStateWithLifecycle()
    val magisItem by vm.magisItem.collectAsStateWithLifecycle()
    var magisPlayer by remember { mutableStateOf<Player?>(null) }
    var magisTextureView by remember { mutableStateOf<android.view.TextureView?>(null) }
    // Task 1 (poda de light-magis): canal en vivo, mismo patrón que magisItem/magisPlayer.
    val liveItem by vm.liveItem.collectAsStateWithLifecycle()
    var livePlayer by remember { mutableStateOf<Player?>(null) }
    // Caracol: mismo patrón que magisItem/magisPlayer. Lo reproduce DituExoPlayer (DASH + Widevine).
    val dituPlay by vm.dituPlayable.collectAsStateWithLifecycle()
    var dituPlayer by remember { mutableStateOf<Player?>(null) }
    /**
     * Si el reproductor de ExoPlayer ya puso un fotograma en pantalla.
     *
     * Lo necesita el spinner: `espejo.buffereando` dice si FALTAN DATOS, que no es lo mismo que si
     * HAY IMAGEN. ExoPlayer se declara READY en cuanto tiene el búfer lleno, pero el primer frame
     * puede tardar bastante más —se midieron 6,5 s en ditu, esperando la superficie— y en ese hueco
     * el spinner ya se había ido: pantalla negra sin nada que explicara la espera. libVLC sí lo
     * distinguía con `esperandoPrimeraImagen`, y al pasar a ExoPlayer esa distinción se perdió.
     */
    var exoYaPintoAlgo by remember { mutableStateOf(false) }
    val generacionVivo by vm.generacionVivo.collectAsStateWithLifecycle()
    val loadError by vm.error.collectAsStateWithLifecycle()
    // Fuente web: mientras el resolver de blog snifea el stream, y los subtítulos sniffeados a adjuntar.
    val resolving by vm.resolving.collectAsStateWithLifecycle()
    val webExtras by vm.webExtras.collectAsStateWithLifecycle()
    val trivia by vm.trivia.collectAsStateWithLifecycle()

    // Cómo se nombra la fuente en el cartel de "Resolviendo…". `vm.resolving` lo prenden las cargas
    // that resolve against the network —`loadMagis` and `loadDitu`; `loadUnknownSource` (ids from
    // sources removed in this branch's pruning) only turns it off—, but the text assumed it was
    // web: playing a Magis chapter announced a web source that doesn't exist on that path.
    // No other source turns on that flag (archive had its own banner, and it was removed in this
    // branch's pruning).
    val fuenteQueResuelve = remember(episodeId) {
        when (PlayerSource.kindFor(episodeId)) {
            SourceKind.MAGIS -> "de Magis"
            SourceKind.DITU -> "de Caracol"
            else -> "web"
        }
    }
    // Modo vivo (Tarea 14): aísla TODO el comportamiento distinto de VOD (sin barra de progreso ni
    // seek, overlay propio, zapping) detrás de estas banderas calculadas UNA vez del episodeId con el
    // que se compuso la pantalla. Zapear cambia el canal DENTRO del playlist del ViewModel; nunca
    // navega a un episodeId nuevo (ver el LaunchedEffect(playlist) más abajo), así que no pueden
    // quedar obsoletas durante la sesión de vivo.
    //
    // Son dos. `enVivo` es CUALQUIER canal en vivo, el de Magis o el de Caracol
    // ([PlayerSource.isLiveChannel]): de ella cuelga lo que no tiene sentido en un directo (la barra
    // de avance y el overlay de VOD, el seek por gestos y por D-pad, guardar la posición, el
    // auto-avance al terminar). `vivoDeMagis` es solo el de Magis: el zapeo, el cajón y la ficha de
    // canales, la reapertura por cortes y el "Cambiando de canal…". Un canal de Caracol no tiene nada
    // de eso: se abre desde su sección, y `loadDitu` no arma ningún zapeo.
    val enVivo = remember(episodeId) { PlayerSource.isLiveChannel(episodeId) }
    val vivoDeMagis = remember(episodeId) { PlayerSource.kindFor(episodeId) == SourceKind.LIVE }

    // Índice del ítem que suena DENTRO de la playlist del ViewModel. Vive acá arriba —y no con el
    // resto del estado de transporte, más abajo— porque `episodioEnCurso` lo necesita.
    var currentIndex by remember { mutableIntStateOf(0) }

    /**
     * The chapter that's playing RIGHT NOW, which isn't always the `episodeId` the screen was
     * opened with: archive.org (source removed in this branch's pruning) used to load the whole
     * section as a playlist -the only multi-item source that ever existed-, so when a chapter
     * ended the player advanced to the next one internally —or "Skip outro" did it with its
     * `seekToNextMediaItem()`— without navigating to a new route. The navigation argument was left
     * with the old chapter forever. No current source builds a playlist with more than one item
     * (see [SaltoDeOutro]), but the read-by-index stays: it's the same source of truth that avoids
     * this whole class of bug if it's ever needed again.
     *
     * Colgar los vecinos y el encabezado de ese argumento tenía consecuencias visibles: tras el
     * auto-avance, "Siguiente episodio" llevaba al capítulo que YA se estaba viendo, "Capítulo
     * anterior" al que acababa de terminar, el encabezado seguía nombrando al viejo y el carrusel
     * resaltaba el chip equivocado. El resto de la pantalla (guardar progreso, capturar el frame)
     * ya se identificaba así, por la playlist y no por el argumento.
     */
    val episodioEnCurso = playlist?.items?.getOrNull(currentIndex)?.episodeId ?: episodeId

    // Cabecera del overlay y episodios vecinos: en `PlayerCabecera.kt`, los tres salen de la misma
    // consulta y cambian juntos al saltar de capítulo.
    val cabecera = rememberEstadoDeCabecera(graph.repository)
    EfectoDeCabecera(cabecera, episodioEnCurso)

    // Foco D-pad (TV) de los controles del overlay de pausa: los doce puntos de aterrizaje viven
    // juntos en `PlayerFoco.kt`, ver su KDoc.
    val focos = rememberOverlayFocusPoints()
    // Carrusel de capítulos (TV): un paso más abajo desde la fila de íconos. Aparece con todos
    // los episodios de la serie en scroll horizontal, con el actual centrado y enfocado. Todo su
    // estado y sus tres efectos viven en `PlayerCapitulos.kt`.
    val estadoCapitulos = rememberEstadoDeCapitulos(graph.repository)
    EfectosDeCapitulos(estadoCapitulos, episodeId, episodioEnCurso, isTv)

    // El CastPlayer vive en el AppGraph, no acá: liberarlo termina la sesión de Chromecast, así que
    // mientras fue de la pantalla, salir del reproductor mataba el casteo.
    val castSession = remember { graph.castSession }
    val castPlayer = castSession?.player
    // El `remember` del flujo de respaldo es necesario: sin él se crearía un MutableStateFlow nuevo
    // en cada recomposición y el colector se reiniciaría una y otra vez.
    val castingFlow = remember(castSession) {
        castSession?.casting ?: kotlinx.coroutines.flow.MutableStateFlow(false)
    }
    val casting by castingFlow.collectAsStateWithLifecycle()

    // Lo que la pantalla sabe del player activo (posicion, duracion, playing, buffering) vive
    // junto en `PlayerEspejo.kt`: son los valores que casi toda la interfaz lee a la vez.
    val espejo = rememberEspejoDelPlayer()

    // --- Datos curiosos ---
    // El dato avanza a PULSACIÓN, no con el reloj: cada `arriba` (o el botón "i", o tocar el
    // cartel en el teléfono) muestra el siguiente, y al pasar el último vuelve el primero. El
    // estado y las dos piezas de interfaz viven en `TriviaDelPlayer.kt`.
    val estadoTrivia = rememberEstadoDeTrivia()
    EfectosDeTrivia(estadoTrivia, trivia.size, episodeId)

    var loaded by remember { mutableStateOf(false) }
    // Episodio que esta pantalla ya mandó al receptor. Coordina los dos caminos que castean (la
    // carga de playlist y el salto local→cast de LaunchedEffect(casting)): si el usuario conecta
    // justo en el frame en que llega la playlist, ambos efectos corren y sin esto el receptor
    // recargaría dos veces lo mismo. Se limpia al desconectar.
    var casteadoAlReceptor by remember { mutableStateOf<String?>(null) }

    // Titles whose remux failed. They fall back to HLS segments immediately instead of waiting for
    // something that will not arrive: a muxer error left the screen with nothing cast at all
    // (measured 2026-09-12, `code=7002` after seven minutes), and stuttery beats blank every time.
    val remuxImposible = remember { mutableStateListOf<String>() }

    /**
     * Where each title's remux is clipped, in ms, already snapped to a real keyframe.
     *
     * Computed once per title because finding it costs a couple of reads from the CDN, and read
     * back everywhere the remux is looked up so the key always matches the one it was filed under.
     */
    val puntoDeArranque: androidx.compose.runtime.snapshots.SnapshotStateMap<String, Long> =
        remember { mutableStateMapOf() }

    /**
     * Which episode is on the receiver AS A REMUX, as opposed to as HLS segments.
     *
     * Separate from [casteadoAlReceptor] because they answer different questions, and conflating
     * them broke the upgrade: the HLS cast goes out first and marks the episode as cast, so the
     * loop waiting for the remux saw "already cast" and gave up -- the TV stayed on the stuttering
     * segments forever while a perfectly good mp4 finished behind it.
     */
    var casteadoComoRemux by remember { mutableStateOf<String?>(null) }

    // El player que estamos manejando ahora mismo: el del Chromecast mientras haya sesión, el
    // local si no. Ambos implementan Player, así que los controles no necesitan saber cuál es.
    // El `?: controller` cubre el caso sin Google Play Services (castContext y castPlayer nulos).
    // For downloaded files it is `controller`: the local player lives in PlaybackService (see the
    // background rule in the ON_STOP observer below).
    val activePlayer: Player = when {
        casting -> castPlayer ?: controller
        magisItem != null && magisPlayer != null -> magisPlayer!!
        liveItem != null && livePlayer != null -> livePlayer!!
        dituPlay != null && dituPlayer != null -> dituPlayer!!
        else -> controller
    }

    /**
     * Content position and duration, whether local or cast.
     *
     * Without a transcoder the receiver always counts from the same point as the file, but a live
     * stream can still send `TIME_UNSET` as duration: reading it raw would leave the bar showing a
     * negative number instead of "no duration". The translation lives in CastProgress (with tests)
     * so there isn't a second copy that can drift.
     */
    /**
     * Where the remux being cast BEGINS inside the title, in ms, or 0.
     *
     * A remux is clipped to start where playback was, so the receiver counts from ITS zero while
     * the title is minutes further along. Without adding this back, the phone's bar mixes two
     * different clocks.
     */
    fun desfaseDelRemux(): Long {
        if (!casting) return 0L
        val ep = magisItem?.episodeId ?: return 0L
        val cdn = magisItem?.castUrl?.takeIf { it.isNotBlank() } ?: return 0L
        // From the stored, keyframe-aligned point -- NOT from the local player's live position,
        // which keeps moving and would make the bar jump every time it was read.
        val clave = com.arkiv.player.playback.RemuxPolicy.keyFrom(cdn, puntoDeArranque[ep] ?: 0L)
        return com.arkiv.player.playback.RemuxPolicy.fromInKey(clave)
    }

    fun contentPositionMs(): Long =
        CastProgress.contentPosition(activePlayer.currentPosition) + desfaseDelRemux()

    /**
     * ¿La posición que reporta el player habla de lo que ESTA pantalla abrió?
     *
     * Al entrar a un episodio nuevo el controller todavía tiene el anterior: sigue en READY y sigue
     * devolviendo su posición y su duración. Adoptarlas pintaba la barra del video nuevo con el
     * progreso del viejo —una película recién abierta arrancaba marcando 30 minutos— hasta que
     * llegaba la playlist y lo corregía sola. Vale cuando esta pantalla ya cargó su playlist, o
     * cuando lo que suena YA es este episodio (volver a entrar a lo que estaba sonando, donde la
     * posición es correcta desde el primer frame y esconderla sería el parpadeo contrario).
     */
    fun posicionEsDeEstaPantalla(): Boolean =
        magisItem != null ||
        liveItem != null ||
        dituPlay != null ||
        loaded || runCatching { controller.currentMediaItem?.mediaId }.getOrNull() == episodeId

    /**
     * How long the title runs, for the bar.
     *
     * Casting a remux, the receiver reports no duration at all: the file is announced as a live
     * stream, which is what stopped it inventing an end and stalling against it, and a live stream
     * has none. The bar then had a position and nothing to divide it by, so it filled and emptied
     * at random. The phone does know the real duration -- the local player has been showing it all
     * along -- so it uses that instead of the receiver's non-answer.
     */
    fun contentDurationMs(): Long {
        // Casting a REMUX, the receiver's duration is never usable and "is it greater than zero"
        // is not a good enough test of that. It reports nothing at all for a file announced as
        // live, and when it does report something it is whatever it worked out from the fragments
        // that had arrived -- measured at 6592 ms for a title running one hour fifty, which drew
        // the bar at 55077% and is exactly the "bar goes crazy" being chased here. The phone knows
        // the real figure and has been drawing its own bar with it all along.
        val local = runCatching {
            (magisPlayer ?: controller).duration.takeIf { it > 0 } ?: 0L
        }.getOrDefault(0L)
        if (casting && local > 0L && desfaseDelRemux() >= 0L && magisItem != null) return local
        val delReceptor = CastProgress.contentDuration(activePlayer.duration)
        if (delReceptor > 0L) return delReceptor
        return local
    }

    // Controles custom (estilo torrent): visibles al tocar, se auto-ocultan mientras reproduce.
    // Arranca OCULTO: al abrir se ve el spinner de carga y luego el video limpio, sin el overlay de
    // pausa/barra encima. El usuario toca la pantalla para mostrar los controles.
    val controles = rememberEstadoDeControles()

    // Modo vivo (Tarea 14): overlay PROPIO, no reusa controles.visible/controles.tickDeActividad -- esos
    // gobiernan la barra de progreso/fila de transporte de VOD, que en vivo no existen. Todo su
    // estado (ficha del canal, EPG y cajón) vive en `PlayerVivo.kt`; de acá solo lo mueve el
    // listener de teclas del video, que sigue siendo de esta pantalla.
    val estadoVivo = rememberEstadoDeVivo()
    val liveCanal by vm.liveCanal.collectAsStateWithLifecycle()
    // Tarea 15: publicar el nombre del canal para NowPlayingPublisher (solo corre en el TV, pero
    // no cuesta nada tenerlo también seteado acá en el celu). Sin esto la barra del miniplayer
    // remoto, al enviar un canal al TV, queda en blanco: "live:<code>" no es un episodeId de la
    // biblioteca, así que ArkivRepository.headerInfo() no tiene título que devolver.
    LaunchedEffect(liveCanal?.nombre) {
        com.arkiv.player.playback.NowPlaying.liveChannelName = liveCanal?.nombre
    }

    // ExoPlayer (Magis): NowPlaying no se actualiza por onMediaItemTransition.
    LaunchedEffect(magisItem?.episodeId) {
        val epId = magisItem?.episodeId ?: return@LaunchedEffect
        NowPlaying.episodeId = epId
    }
    LaunchedEffect(liveItem?.episodeId) {
        val epId = liveItem?.episodeId ?: return@LaunchedEffect
        NowPlaying.episodeId = epId
    }
    LaunchedEffect(dituPlay?.episodeId) {
        val epId = dituPlay?.episodeId ?: return@LaunchedEffect
        NowPlaying.episodeId = epId
    }

    // Intro/outro marker editor: the source that used it (archive.org) was removed in this branch
    // (that source's ids fall through to `loadUnknownSource` today, which only reports an error)
    // and the button that opens it sits behind `MOSTRAR_MARCADORES_EN_TELEFONO = false`, but the
    // state stays alive because the rest of the overlay (the `marcadores.marcando` guards, the
    // `BackHandler`, the key listener) reads it.
    val marcadores = rememberEstadoDeMarcadores()

    // Audio/subtitle picker. Tracks come from the bound in-screen ExoPlayer or, by default, from the
    // local (service) player through `controller`. Todo el bloque vive en `PlayerPistas.kt`; de acá
    // solo se consulta `haySubtitulo`, para el ícono de CC.
    val estadoPistas = rememberEstadoDePistas(controller, graph, episodeId)


    // Modo noche: nivel del velo negro sobre el video, 0..DIM_MAX_LEVEL. Persistido en
    // SettingsStore (sobrevive a cerrar la app). Se acota al leerlo por si quedó un valor viejo
    // fuera de rango guardado.
    val dimLevel by graph.settings.dimLevel.collectAsStateWithLifecycle()
    val dimNivel = dimLevel.coerceIn(0, DIM_MAX_LEVEL)
    // Moverse por la barra -- arrastre del slider y saltos incrementales -- vive en
    // `PlayerSeek.kt`. Quien dispara el seek de verdad se queda aca: depende del player activo
    // y de si el cast esta transcodificando.
    val seek = rememberEstadoDeSeek()

    // The local player's TextureView: where downloaded files paint, what their frames are captured
    // from, and (TV) the view that holds the D-pad key listener and gets the focus back after a dialog.
    var videoView by remember { mutableStateOf<android.view.TextureView?>(null) }
    // What the screen knows about the local player's picture (first frame, aspect, surface). See
    // PlayerVideoLocal.kt.
    val localVideo = remember { LocalVideoState() }
    // Embedded subtitles of a downloaded file: libVLC painted them itself, ExoPlayer hands the cues
    // to whoever draws them (same as MagisExoPlayer's SubtitleView). Created by its AndroidView
    // factory, like the video view: a remembered View can't be re-parented when it is mounted again.
    var localSubtitles by remember { mutableStateOf<SubtitleView?>(null) }

    /**
     * A local item started loading: the first-frame wait restarts and the track menu forgets the
     * previous item, whose tracks the service player is still reporting (it keeps playing in the
     * background) and which would otherwise spend the one-shot language auto-pick.
     */
    fun markLocalLoad(prefersSoftware: Boolean) {
        localVideo.onLoad(android.os.SystemClock.elapsedRealtime(), prefersSoftware)
        estadoPistas.onLocalItemLoad()
    }

    /**
     * El TextureView donde se está pintando el video, para las capturas de frame.
     *
     * Magis (ExoPlayer): MagisExoPlayer configura SURFACE_TYPE_TEXTURE_VIEW y nos lo pasa vía
     * `onTextureViewReady` → `magisTextureView`.
     *
     * Local (downloaded files): this screen's own TextureView, bound to the service's ExoPlayer. It
     * belongs to the screen, not to the player, so it is still here for the exit capture even when
     * the AndroidView's `onRelease` already unbound it.
     */
    fun textureViewDelVideo(): android.view.TextureView? = when {
        magisItem != null -> magisTextureView
        // Caracol pinta en el SurfaceView de PlayerView, no en un TextureView (ver DituExoPlayer):
        // no hay de dónde capturar.
        dituPlay != null -> null
        else -> videoView
    }

    // Re-bind the local video when coming back from another app (see VideoAttachPolicy). The decoder
    // only paints again from the next keyframe, and without a notice that gap looks like a hang.
    // Only if there WAS a picture before and it is playing: audio-only content never paints, and a
    // paused player has nothing new to paint.
    var esperandoVideo by remember { mutableStateOf(false) }

    // Distinto de [esperandoVideo], que es "HABÍA imagen y se perdió al volver del fondo". Esto es
    // "todavía no hubo ninguna": el arranque negro con sonido. For the local player it is decided by
    // [FirstFrameWait] from [localVideo] and the controller's tracks (see the polling loop).
    var sinPrimeraImagen by remember { mutableStateOf(false) }

    // Identidad de ESTA composición del reproductor. Al recrearse la pantalla (volver del segundo
    // plano, navegación) llegan a convivir dos, cada una con su layout y su observador de ciclo de
    // vida; sin poder nombrarlas, en el log se ven como la misma y no hay manera de saber cuál
    // engancha el video y cuál lo suelta.
    val pantallaId = remember { PANTALLA_SEQ.incrementAndGet() }
    DisposableEffect(Unit) {
        android.util.Log.w("ArkivVout", "SCREEN #$pantallaId enters")
        onDispose {
            android.util.Log.w("ArkivVout", "SCREEN #$pantallaId exits (dispose)")
            // LEAVING THE PLAYER ENDS THE CAST. A Cast session outlives this screen, so walking
            // out of an episode and opening another one used to arrive with casting already on:
            // the new title went to the TV without anyone asking, and every remux and wait that
            // implies started on its own. Casting is a thing the person does on purpose, once,
            // for what they are watching -- so it ends with what they were watching.
            //
            // Only when the screen really goes away. Rotating or the app going to the background
            // does not come through here with the activity kept, and auto-advance to the next
            // episode replaces the item WITHOUT disposing this, so a series still plays on
            // through to the TV.
            if (runCatching { graph.castSession?.casting?.value }.getOrNull() == true) {
                android.util.Log.w("ArkivCast", "leaving the player → ending the cast session")
                runCatching { graph.castSession?.stopIntentionally() }
            }
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, serviceExo) {
        var habiaVideo = false
        val policy = VideoAttachPolicy(
            attach = {
                val v = videoView
                if (v == null) {
                    android.util.Log.w("ArkivVout", "ON_START #$pantallaId but videoView=null → nothing to bind")
                } else {
                    android.util.Log.w("ArkivVout", "ATTACH ON_START#$pantallaId view=#${Integer.toHexString(System.identityHashCode(v))}")
                    serviceExo.setVideoTextureView(v)
                    localVideo.onSurfaceAttached(android.os.SystemClock.elapsedRealtime())
                }
                esperandoVideo = habiaVideo && controller.playWhenReady
            },
            detach = {
                // Only the local player's own picture counts: with an in-screen player on, the
                // service player has nothing loaded.
                habiaVideo = localVideo.renderedFirstFrame &&
                    magisItem == null && liveItem == null && dituPlay == null
                android.util.Log.w("ArkivVout", "DETACH ON_STOP#$pantallaId hadVideo=$habiaVideo")
                videoView?.let { serviceExo.clearVideoTextureView(it) }
                localVideo.onSurfaceDetached()
            },
        )
        // Los eventos crudos se loguean aparte de lo que decide la política: la política ignora a
        // propósito el primer ON_START (ver VideoAttachPolicy), así que "llegó el evento" y "hubo
        // reenganche" son dos hechos distintos y hay que poder verlos por separado.
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> {
                    android.util.Log.w("ArkivVout", "CYCLE #$pantallaId ON_START (owner=${lifecycleOwner.hashCode()})")
                    policy.onStart()
                }
                Lifecycle.Event.ON_STOP -> {
                    android.util.Log.w("ArkivVout", "CYCLE #$pantallaId ON_STOP (owner=${lifecycleOwner.hashCode()})")
                    policy.onStop()
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            android.util.Log.w("ArkivVout", "CYCLE #$pantallaId observer removed")
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    // Polls until the local player paints on the re-bound surface (ExoPlayer notifies
    // onRenderedFirstFrame again for each new surface). The timeout is a safety net: if no frame comes
    // back (error, no video), the spinner goes away anyway instead of hanging forever.
    LaunchedEffect(esperandoVideo) {
        if (!esperandoVideo) return@LaunchedEffect
        withTimeoutOrNull(15_000) {
            while (!localVideo.paintedSinceAttach) delay(150)
        }
        esperandoVideo = false
    }

    // Estado DLNA: vive entero en `PlayerDlna.kt` (estado, acciones y sus tres piezas de UI). De
    // todo eso, esta pantalla solo consulta `activo`, porque tener un renderer andando esconde los
    // controles locales.
    val estadoDlna = rememberEstadoDlna(dlna, graph.applicationScope)

    val d = playlist?.items?.getOrNull(currentIndex)
    val playlistRef = rememberUpdatedState(playlist)

    /**
     * Arma lo que hay que mandarle al receptor para el ítem [idx] de [pl], arrancando en
     * [startPositionMs]. Un solo lugar a propósito: lo usan los DOS caminos que castean —abrir un
     * capítulo estando ya casteando, y conectar el Chromecast con el capítulo ya sonando en el
     * celu—. Si divergieran, lo que llega a la TV dependería de por dónde entraste.
     */
    /**
     * Is this Magis title an MPEG-TS? Only those need the HLS wrapper for cast.
     *
     * Read from the CDN url, which is the only place the true container survives: the proxy url
     * the phone plays from has no extension at all, so guessing from it always answers mp4.
     * `MagisResolve` builds the CDN url as `_media.ts` or `_media.mp4` straight from the portal's
     * `videoFormat`.
     */
    fun magisEsTs(item: PlayerData): Boolean =
        com.arkiv.player.cast.CastRequestBuilder.mimeForUrl(item.castUrl.orEmpty()) == "video/mp2t"

    fun castRequestFor(pl: PlaylistData, idx: Int, startPositionMs: Long): com.arkiv.player.cast.CastRequest? {
        val item = pl.items.getOrNull(idx) ?: return null
        // DIAGNÓSTICO TEMPORAL (cast Magis): qué se le quiere mandar al receptor y con qué URL.
        android.util.Log.w(
            "ArkivCast",
            "castRequestFor · ep=${item.episodeId} kind=${item.kind} desde=${startPositionMs}ms " +
                "castUrl=${item.castUrl?.take(120)} mediaUrl=${item.mediaUrl.take(120)}",
        )
        val esVivo = item.kind == SourceKind.LIVE
        // Qué audio lleva esto y si el receptor puede con él. Se lee del player LOCAL, que es el que
        // ya parseó el archivo. Un "no lo decodifica" acá explica el video mudo que antes no dejaba
        // ni un rastro: AC-3 (Avatar) y DTS (Naruto), los dos con H.264, por eso se veía la imagen.
        // Vivo (Tarea 18) usa EXACTAMENTE el mismo portero: se lee el audio que YA está sonando en
        // el celu -el canal está reproduciéndose cuando se llega hasta acá, nunca antes- así que no
        // hace falta ninguna lista de canales permitidos ni adivinar por nombre/categoría.
        val audio = localAudioFormat(controller.currentTracks)
        // Why a remux costs minutes instead of being a copy. `TransformerUtil.shouldTranscodeVideo`
        // re-encodes unconditionally when `pixelWidthHeightRatio != 1`, and broadcast transport
        // streams very often declare a non-square pixel. This is the one number that says whether
        // a true transmux is even reachable for this title.
        val videoLocal = localVideoFormat(controller.currentTracks)
            ?: magisPlayer?.let { runCatching { localVideoFormat(it.currentTracks) }.getOrNull() }
        android.util.Log.w(
            "ArkivCast",
            "source video · mime=${videoLocal?.sampleMimeType} ${videoLocal?.width}x${videoLocal?.height} " +
                "par=${videoLocal?.pixelWidthHeightRatio} → transmux ${
                    if (videoLocal?.pixelWidthHeightRatio == 1f) "possible" else "BLOCKED by a non-square pixel"
                }",
        )
        // .coerceAtLeast(0): media3 reports an unset channel count as Format.NO_VALUE (-1), which
        // would otherwise show up in the log below as "canales=-1". Doesn't change the decodable
        // decision (receiverDecodes only compares it against AAC's <=2 stereo cap).
        val channelCount = (audio?.channelCount ?: 0).coerceAtLeast(0)
        val decodable = com.arkiv.player.cast.CastAudioSupport.receiverDecodes(
            sampleMimeType = audio?.sampleMimeType,
            channelCount = channelCount,
        )
        android.util.Log.i(
            "ArkivCast",
            "source audio · mime=${audio?.sampleMimeType ?: "unknown"} " +
                "channels=$channelCount → ${if (decodable) "goes straight through" else "might play mute"}",
        )

        // La URL alcanzable por el receptor: la del proxy de vivo (LiveHlsProxy) LAN -- mismo motivo
        // que el resto de este método, ver el KDoc de CastRequestBuilder. `graph.lanIp()` es un
        // helper genérico (IP del celu en la LAN), no algo específico de ninguna fuente.
        //
        // MAGIS is the same shape for a different reason: its origin is remote, but the CDN wants
        // `Content-Auth`/`Content-License` and the Cast receiver cannot send custom headers, so it
        // gets 401 from the CDN and has to come through our proxy like live does. No socket is
        // widened to do this -- `ArchiveCacheProxy.start()` already listens on every interface; it
        // is the same loopback url the phone is playing from, respelled. See its `lanUrl` KDoc.
        val lanIp = graph.lanIp()
        // Finished OR still being written: a fragmented MP4 is playable before it is complete,
        // which is what turns "wait minutes, then cast" into "cast now, it fills in behind you".
        var remuxMagisCreciendo = false
        val remuxMagis = if (item.kind == SourceKind.MAGIS) {
            item.castUrl?.let { cdn ->
                // COMPLETE only. Serving one while it grew was the plan, and the receiver
                // settled it: it recomputes the duration from the fragments it has and reports a
                // new one every second or two (`kDurationChanged 75.25 … 80.25`, read off its own
                // log), ignoring the duration we send it. So playback chases an end that keeps
                // moving just ahead of it, reaches it, stalls, gets more, resumes -- the "loading"
                // that came back no matter how large the head start was, 64 s of cushion included.
                // A finished file has one duration and stays still.
                // The same key the remux was filed under: the one that says where it begins.
                // The SAME key the remux was filed under: the keyframe-aligned point, not the
                // raw position, which drifts as the local player keeps its own time.
                graph.tsRemuxer.inProgress(
                    com.arkiv.player.playback.RemuxPolicy.keyFrom(
                        cdn,
                        puntoDeArranque[item.episodeId] ?: 0L,
                    ),
                )?.let { (archivo, completo) ->
                    // ALWAYS chunked, finished or not. Measured 2026-09-12, and it is the
                    // difference between playing and not: served while it grew -- chunked, no
                    // Content-Length, no ranges -- the receiver had nothing to do but play from
                    // the start, and it played. Served complete, with a length and range support,
                    // it went hunting through 1.4 GB for an index a fragmented MP4 does not carry
                    // (`range=bytes=308510720-`, 4 MB, broken pipe, a slightly later range, over
                    // and over) and never produced a frame. Withholding the ability to seek is
                    // what makes it work, which is backwards but it is what the device does.
                    graph.localFileServer.growing = true
                    remuxMagisCreciendo = !completo
                    android.util.Log.w(
                        "ArkivCast",
                        "magis → remuxed mp4 (${if (completo) "complete" else "still growing, ${archivo.length()}B"})",
                    )
                    graph.localFileServer.serve(archivo)
                }
            }
        } else {
            null
        }
        val lanUrl = when (item.kind) {
            SourceKind.LIVE -> lanIp?.let { graph.liveHlsProxy.lanUrl(it) }
            // Magis: through the proxy either way, because the CDN wants headers the receiver
            // cannot send. WHICH proxy url depends on the container -- see `magisEsTs` below.
            // A finished remux wins over both: it is an MP4 served off this device, so the CDN's
            // headers stop mattering and the receiver gets per-sample timing. Keyed by the CDN url
            // because the proxy url carries tokens that change on every resolve.
            SourceKind.MAGIS -> remuxMagis ?: lanIp?.let {
                if (magisEsTs(item)) {
                    com.arkiv.player.playback.ArchiveCacheProxy.lanPlaylistUrl(item.mediaUrl, it)
                } else {
                    com.arkiv.player.playback.ArchiveCacheProxy.lanUrl(item.mediaUrl, it)
                }
            }
            else -> null
        }
        if (item.kind == SourceKind.MAGIS) {
            android.util.Log.w(
                "ArkivCast",
                "magis cast url · lanIp=${lanIp ?: "NONE"} " +
                    "proxyLocal=${item.mediaUrl.take(60)} → lan=${lanUrl?.take(60) ?: "NULL (cannot cast)"}",
            )
        }

        // A downloaded file is the one case where the container can be KNOWN instead of guessed:
        // the bytes are on this device. The cast URL is the local server's ("…/file", no extension),
        // so guessing by extension always answered mp4 while the server served what the bytes say --
        // the receiver was told one container and handed another. `mediaUrl` is "file://<path>" for
        // LOCAL, which is where the path comes from.
        // SPIKE: a local file is cast as a one-segment HLS playlist, not as a bare MPEG-TS. The
        // receiver refuses the latter (measured 2026-09-12: fetched 5.3 MB, broken pipe) and accepts
        // the former, whose segments are that very same MPEG-TS.
        // A downloaded file that has already been remuxed is cast as the MP4, which is what the
        // receiver wants: explicit per-sample timing instead of deriving it from the transport
        // stream's PTS/DTS. Only when there is no remux yet does it fall back to serving the
        // original as HLS segments. The remux itself is kicked off by the effect below -- this
        // function stays synchronous because every cast path calls it.
        val remuxLocal = if (item.kind == SourceKind.LOCAL) {
            graph.tsRemuxer.alreadyDone(item.mediaUrl)?.let {
                graph.localFileServer.growing = true
                graph.localFileServer.serve(it)
            }
        } else {
            null
        }
        if (item.kind == SourceKind.LOCAL) {
            android.util.Log.i(
                "ArkivCast",
                "local file → ${if (remuxLocal != null) "remuxed mp4" else "HLS segments (no remux yet)"}",
            )
        }
        val hlsLocal = if (item.kind == SourceKind.LOCAL && remuxLocal == null) {
            graph.localFileServer.playlistUrl()
        } else {
            null
        }
        val mimeLocal = if (item.kind == SourceKind.LOCAL) {
            runCatching {
                com.arkiv.player.playback.VideoContainer
                    .ofFile(java.io.File(item.mediaUrl.removePrefix("file://"))).mime
            }.getOrNull()
        } else {
            null
        }
        if (mimeLocal != null) {
            android.util.Log.i(
                "ArkivCast",
                "local container from its bytes: $mimeLocal (url guess was ${
                    com.arkiv.player.cast.CastRequestBuilder.mimeForUrl(item.castUrl ?: item.mediaUrl)
                })",
            )
        }

        // Only an MPEG-TS needs the playlist, and it is the container that decides -- not the
        // source. The receiver refuses a bare transport stream served progressively
        // (`FFmpegDemuxer: open context failed`, read off its own log 2026-09-12) so that one is
        // announced as HLS and served in segments. An mp4 it accepts as-is, and wrapping one
        // would only add the segmenter's cost and its rough edges for nothing: Magis serves both
        // (`MagisResolve` picks `_media.ts` vs `_media.mp4` from the portal's `videoFormat`).
        val mimeMagis = if (item.kind == SourceKind.MAGIS) {
            when {
                remuxMagis != null -> "video/mp4"
                magisEsTs(item) -> "application/vnd.apple.mpegurl"
                else -> com.arkiv.player.cast.CastRequestBuilder.mimeForUrl(item.castUrl.orEmpty())
            }
        } else {
            null
        }
        if (mimeMagis != null) {
            android.util.Log.i(
                "ArkivCast",
                "magis container=${com.arkiv.player.cast.CastRequestBuilder.mimeForUrl(item.castUrl.orEmpty())} " +
                    "→ ${if (magisEsTs(item)) "HLS playlist (only the announcement changes)" else "straight through, no playlist needed"}",
            )
        }
        val directo = com.arkiv.player.cast.CastRequestBuilder.build(
            episodeId = item.episodeId,
            title = item.title,
            subtitle = item.subtitle,
            artworkUrl = item.artworkUrl,
            mediaUrl = item.mediaUrl,
            castUrl = remuxLocal ?: hlsLocal ?: item.castUrl,
            lanUrl = lanUrl,
            // HLS segments keep the resume position -- every segment boundary is a real entry
            // point since TsSegmenter cuts them on keyframes. A REMUX does not: a fragmented MP4
            // carries no seek index, that being the price of playing while it is written. Asking
            // the receiver to start at minute 4:52 of one sent it hunting through the file blind
            // -- `range=bytes=308510720-`, 4 MB, broken pipe, a slightly later range, again,
            // without ever playing a frame (measured 2026-09-12). Starting at zero is what makes
            // it play. Losing "where you were" is the cost, and getting it back means writing a
            // real index.
            startPositionMs = if (remuxLocal != null || remuxMagis != null) 0L else startPositionMs,
            isLive = esVivo,
            mimeOverride = when {
                remuxLocal != null -> "video/mp4"
                hlsLocal != null -> "application/vnd.apple.mpegurl"
                mimeMagis != null -> mimeMagis
                else -> mimeLocal
            },
            // Magis has no usable fallback: `castUrl` is the CDN, which answers 401 without headers
            // the receiver cannot send, and `mediaUrl` is loopback. Either `lanUrl` or nothing --
            // ALWAYS, including when a remux exists, because the remux's url is what `lanUrl`
            // holds in that case. Letting this go false when there was a remux sent the receiver
            // the raw CDN url instead (measured 2026-09-12: `uri=http://…_media.ts mime=video/mp4`),
            // since the builder falls back to `castUrl` whenever it is not required to use the LAN.
            requiresLanUrl = item.kind == SourceKind.MAGIS,
            // While the remux is still being written it IS a live stream, and saying so is what
            // keeps the receiver from inventing an end and stalling against it.
            asLive = remuxMagisCreciendo,
            // Where the remux begins, so a saved position lands on the right minute of the title.
            offsetMs = if (item.kind == SourceKind.MAGIS) {
                com.arkiv.player.playback.RemuxPolicy.fromInKey(
                    com.arkiv.player.playback.RemuxPolicy.keyFrom(
                        item.castUrl.orEmpty(),
                        puntoDeArranque[item.episodeId] ?: 0L,
                    ),
                )
            } else {
                0L
            },
            // The local player already knows how long this runs -- it has been showing it on the
            // bar. A remux still being written cannot state it, so without this the receiver
            // invents one from the fragments it has (5 s for a two-hour film) and stalls on that
            // imaginary end every few seconds.
            durationMs = runCatching {
                (magisPlayer ?: controller).duration.takeIf { it > 0 } ?: 0L
            }.getOrDefault(0L),
        )
        // No transcoder: audio the receiver can't decode still gets cast, muted, instead of not
        // casting at all. The warning is the only thing that tells that case apart from a normal cast.
        if (!decodable && directo != null) {
            android.widget.Toast.makeText(
                context,
                "Este audio podría no sonar en el Chromecast",
                android.widget.Toast.LENGTH_SHORT,
            ).show()
        }
        // DIAGNÓSTICO TEMPORAL (cast Magis): con qué sale — null significa "sin URL alcanzable".
        android.util.Log.w(
            "ArkivCast",
            "castRequestFor → ${if (directo == null) "NULL" else "uri=${directo.uri.take(120)} mime=${directo.mimeType}"}",
        )
        return directo
    }

    /**
     * Cast-to-TV del canal en vivo cuando lo reproduce ExoPlayer (Task 1, poda de light-magis).
     *
     * Antes esto lo disparaba `LaunchedEffect(playlist, generacionVivo)` (más abajo) porque el
     * canal viajaba en `_playlist`; ahora viaja en `liveItem` (ver PlayerViewModel.abrirCanalActual)
     * y ese efecto solo corre para VOD. Se arma un `PlaylistData` sintético de un solo ítem para
     * reusar [castRequestFor] tal cual -- esa función no lee de `PlaylistData` nada más que
     * `items`/el índice, así que no hace falta duplicar la lógica de lanUrl/lector de audio.
     *
     * `generacionVivo` en la clave: reabrir el MISMO canal tras un corte produce un `PlayerData`
     * igual al anterior (mismo motivo que `_generacionVivo` en el ViewModel, ver su KDoc), así que
     * sin esta clave un re-zap al canal que ya estaba en pantalla no volvería a empujar el receptor.
     *
     * The local player's audio read means nothing here (the channel plays on LiveExoPlayer, not on
     * the service player, so it reads nothing or a stale item), so `castRequestFor` falls back to its
     * conservative default ("don't know → send it straight through", see its own KDoc), the same one
     * it already accepts for Magis VOD. A channel with AC-3/DTS audio can cast mute: a known
     * limitation, the same category as Magis's.
     */
    LaunchedEffect(casting, liveItem, generacionVivo) {
        if (!casting || castSession == null) return@LaunchedEffect
        val item = liveItem ?: return@LaunchedEffect
        val pl = PlaylistData(listOf(item), 0, 0L, pedido = item.episodeId)
        val req = castRequestFor(pl, 0, 0L)
        if (req == null) {
            // Mismo aviso que ya da VOD cuando castRequestFor no encuentra una URL alcanzable
            // por la TV (ver el Toast idéntico más abajo en este archivo) -- antes de esta migración
            // el vivo-vía-VLC lo mostraba también; se había perdido al portar el bloque a ExoPlayer.
            android.util.Log.w("ArkivCast", "live (exo): no URL the receiver can reach")
            android.widget.Toast.makeText(
                context,
                "No se pudo castear: la TV no puede alcanzar este stream (revisa el WiFi)",
                android.widget.Toast.LENGTH_SHORT,
            ).show()
            return@LaunchedEffect
        }
        castSession.setMedia(req)
        casteadoAlReceptor = item.episodeId
        NowPlaying.episodeId = item.episodeId
    }

    /**
     * Remuxes a downloaded MPEG-TS to MP4 while it is being cast, and re-casts it when done.
     *
     * The first cast of a title goes out as HLS segments, which plays but leaves the receiver
     * deriving every frame's presentation time from PTS/DTS -- hundreds of
     * `Failed to get frame timestamps` a minute on the KALLEY, and visible judder with the decoder
     * otherwise healthy. The remux removes that entirely, and it costs almost no CPU because
     * nothing is re-encoded.
     *
     * It runs WHILE the cast plays rather than before it, so the person waits for nothing: the
     * segments carry the picture meanwhile, and the swap happens when the MP4 is whole. Done once
     * per file -- `yaHecho` short-circuits every later cast of the same title.
     *
     * Only for a LOCAL file. A remote title would mean downloading all of it before the MP4 could
     * be finalised (the index lands at the end), which is the wait a fragmented MP4 exists to
     * avoid; that path is separate.
     */
    LaunchedEffect(casting, d?.episodeId, d?.kind, magisItem?.episodeId) {
        if (!casting || castSession == null) return@LaunchedEffect
        // Magis travels in `magisItem`, everything else in the playlist.
        val item = magisItem?.takeIf { it.kind == SourceKind.MAGIS } ?: d ?: return@LaunchedEffect

        // What to feed the remuxer, and what to key it by. They differ for Magis: the input is the
        // loopback proxy (which puts the CDN's auth headers on), while the key is the CDN url,
        // stable across resolves -- keying by the proxy url would remux the same title again every
        // time its tokens were refreshed.
        val (entrada, clave, mime) = when (item.kind) {
            SourceKind.LOCAL -> {
                val archivo = java.io.File(item.mediaUrl.removePrefix("file://"))
                if (!archivo.exists()) return@LaunchedEffect
                Triple(
                    item.mediaUrl,
                    item.mediaUrl,
                    runCatching { com.arkiv.player.playback.VideoContainer.ofFile(archivo).mime }.getOrNull(),
                )
            }
            SourceKind.MAGIS -> {
                val cdn = item.castUrl?.takeIf { it.isNotBlank() } ?: return@LaunchedEffect
                // Where it must start, SNAPPED TO A KEYFRAME. Clipping anywhere else leaves the
                // tracks misaligned -- measured at 1.57 s of audio with no picture -- because the
                // muxer moves video back to a keyframe while audio begins exactly where asked.
                // Where playback actually is. NOT `magisPlayer ?: controller`: when this runs the
                // Magis player may not exist yet -- it is created from the item and the cast can
                // beat it -- and `controller` is the service player, which for Magis holds nothing
                // and answers 0. That silently cast from the beginning every time the race went
                // that way. `startPositionMs` is where the item was told to open, which is the
                // right answer whenever the live position is not available yet.
                val vivo = runCatching { magisPlayer?.currentPosition }.getOrNull()?.takeIf { it > 0 }
                val pedido = (vivo ?: item.startPositionMs).coerceAtLeast(0L)
                android.util.Log.w(
                    "ArkivCast",
                    "resume point: ${pedido}ms (${if (vivo != null) "live position" else "the item's startPosition, player not ready"})",
                )
                // ALWAYS FROM ZERO. Clipping works -- the cut lands exactly on a keyframe now,
                // verified in the log -- and the audio still ran ahead of the picture. The cause
                // measured earlier (1.57 s of audio with no video in the opening fragment) was
                // fixed and the symptom survived it, so something else misaligns the tracks when
                // the remux does not start at the beginning. The likeliest remaining suspect is
                // outside our reach: media3's fragmented muxer writes no `tfdt`, the box that
                // anchors each fragment in time, so nothing ever re-syncs what starts out skewed.
                //
                // Starting at zero has no such problem and is measured good: real time, no stalls,
                // audio correct. Resuming is a convenience; watchable sound is not. The keyframe
                // search and the clipping stay in the code -- they are correct and they are what a
                // receiver that can seek would need.
                val alineado = 0L
                puntoDeArranque[item.episodeId] = 0L
                if (pedido > 0L) {
                    android.util.Log.w(
                        "ArkivCast",
                        "starting the cast from zero, not from ${pedido}ms: clipping desynchronises the audio",
                    )
                }
                Triple(
                    item.mediaUrl,
                    com.arkiv.player.playback.RemuxPolicy.keyFrom(cdn, alineado),
                    com.arkiv.player.cast.CastRequestBuilder.mimeForUrl(cdn),
                )
            }
            else -> return@LaunchedEffect
        }

        // Why this remux will cost minutes instead of being a copy. `TransformerUtil`
        // re-encodes unconditionally when `pixelWidthHeightRatio != 1`, and that is the only
        // condition left that can be firing here: the in-app muxer does accept H265 and AAC.
        // Logged before starting, because by the time the export runs the answer is already baked.
        val fmt = localVideoFormat(controller.currentTracks)
            ?: magisPlayer?.let { runCatching { localVideoFormat(it.currentTracks) }.getOrNull() }
        android.util.Log.w(
            "ArkivCast",
            "source video · mime=${fmt?.sampleMimeType} ${fmt?.width}x${fmt?.height} " +
                "par=${fmt?.pixelWidthHeightRatio} → transmux ${
                    when (fmt?.pixelWidthHeightRatio) {
                        null -> "unknown, no video format available"
                        1f -> "possible"
                        else -> "BLOCKED by a non-square pixel"
                    }
                }",
        )

        if (!com.arkiv.player.playback.RemuxPolicy.needsRemux(mime)) {
            android.util.Log.i("ArkivCast", "${item.kind} is $mime, no remux needed")
            return@LaunchedEffect
        }
        if (graph.tsRemuxer.alreadyDone(clave) != null) return@LaunchedEffect

        // One growing fragmented mp4, announced as LIVE.
        //
        // The chase that broke every earlier attempt was ours to cause: a file still being written
        // was announced as "buffered", which tells the receiver the media has a definite end. It
        // then works one out from the fragments that have arrived and reports a new one every
        // second or two (`kDurationChanged 75.25 … 80.25`, off its own log), plays toward it, and
        // stalls each time it catches up. No head start fixed that -- 64 s of cushion stalled the
        // same as 6 MB -- because the end moves with the file.
        //
        // A live stream has no end to reach. That is both the truth about a file being written and
        // the thing that stops the chase. Cutting the title into a queue of finished chunks also
        // worked around it, but the receiver announces every queue entry with a countdown
        // ("Your video will play in N"), twice a minute.
        if (item.kind == SourceKind.MAGIS) {
            graph.applicationScope.launch(Dispatchers.Main) {
                repeat(600) {
                    delay(1000)
                    if (!casting || castSession == null) return@launch
                    if (casteadoComoRemux == item.episodeId) return@launch
                    val parcial = graph.tsRemuxer.inProgress(clave) ?: return@repeat
                    // 40 MB, not 12. Measured 2026-09-12: casting at 14 MB stalled seven times
                    // in the first forty-five seconds and then never again -- the remux is still
                    // getting up to speed at that point, so playback catches it repeatedly, and
                    // once it is running (about 22x faster than playback consumes) it pulls away
                    // and the problem disappears on its own. Waiting for a bigger head start
                    // spends a few more seconds once and skips that whole stretch.
                    if (parcial.first.length() < 40_000_000L) return@repeat
                    val plr = PlaylistData(listOf(item), 0, 0L, pedido = item.episodeId)
                    val reqr = castRequestFor(plr, 0, 0L) ?: return@repeat
                    android.util.Log.w(
                        "ArkivCast",
                        "remux has ${parcial.first.length() / 1_000_000}MB → casting it as a live stream",
                    )
                    castSession.setMedia(reqr)
                    casteadoAlReceptor = item.episodeId
                    casteadoComoRemux = item.episodeId
                    return@launch
                }
            }
        }

        val res = graph.tsRemuxer.remux(entrada, clave)
        if (res !is com.arkiv.player.playback.TsRemuxer.RemuxResult.Done) {
            // Remember the failure so this title stops waiting for a remux that will not come, and
            // fall back to the segments NOW. For Magis that fallback is the only thing standing
            // between the person and a blank screen, because nothing was cast while it prepared.
            android.util.Log.w("ArkivCast", "remux failed → falling back to HLS segments for this title")
            remuxImposible.add(clave)
            if (item.kind == SourceKind.MAGIS && casting && castSession != null) {
                val ahora = runCatching { contentPositionMs() }.getOrDefault(0L).coerceAtLeast(0L)
                val plFallback = PlaylistData(listOf(item), 0, ahora, pedido = item.episodeId)
                castRequestFor(plFallback, 0, ahora)?.let {
                    castSession.setMedia(it)
                    casteadoAlReceptor = item.episodeId
                }
            }
            return@LaunchedEffect
        }
        // Still casting the same thing? The export takes a while and the person may have moved on.
        if (!casting || castSession == null) return@LaunchedEffect
        val desde = runCatching { contentPositionMs() }.getOrDefault(0L).coerceAtLeast(0L)
        // Magis has no playlist -- same synthetic one-item PlaylistData the rest of this screen
        // uses for it, so castRequestFor stays the single place that decides what goes to the TV.
        val pl = if (item.kind == SourceKind.MAGIS) {
            PlaylistData(listOf(item), 0, desde, pedido = item.episodeId)
        } else {
            playlistRef.value ?: return@LaunchedEffect
        }
        val idx = pl.items.indexOfFirst { it.episodeId == item.episodeId }.coerceAtLeast(0)
        val req = castRequestFor(pl, idx, desde) ?: return@LaunchedEffect
        android.util.Log.w("ArkivCast", "remux ready → re-casting as mp4 from ${desde}ms")
        castSession.setMedia(req)
        casteadoAlReceptor = item.episodeId
        casteadoComoRemux = item.episodeId
    }

    /**
     * "Preparándolo para la TV — NN%" while a streaming MPEG-TS is being remuxed.
     *
     * The wait is the price of reading the title once instead of twice (see the cast effect), and
     * a still screen for a few minutes with no sign of life reads as a hang. Only for the case
     * that actually waits: a downloaded file casts immediately and swaps later, and an mp4 never
     * waits at all.
     */
    val progresoDeRemux by graph.tsRemuxer.progress.collectAsStateWithLifecycle()
    val preparandoParaLaTv = casting &&
        magisItem?.let { magisEsTs(it) && graph.tsRemuxer.alreadyDone(it.castUrl.orEmpty()) == null } == true
    if (preparandoParaLaTv) {
        Box(
            Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.75f)),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(color = Color.White)
                Spacer(Modifier.height(16.dp))
                Text(
                    "Preparándolo para la TV" + if (progresoDeRemux in 0..100) " — $progresoDeRemux%" else "",
                    color = Color.White,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Solo la primera vez de cada título",
                    color = Color.White.copy(alpha = 0.7f),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }

    /**
     * What the progress bar is being drawn from, while casting.
     *
     * The bar is computed from three numbers that come from different places, and when it goes
     * wrong it is never obvious which one is lying: the position comes from the RECEIVER (counting
     * from its own zero, because a remux is clipped), the offset says where that zero sits inside
     * the title, and the duration comes from the PHONE when the receiver reports none -- which it
     * does for anything announced as a live stream. Printing all three together is what tells
     * "the receiver reset" from "the offset is wrong" from "there is no duration to divide by".
     *
     * Every two seconds and only while casting: enough to see a bar jump, quiet the rest of the time.
     */
    LaunchedEffect(casting) {
        if (!casting) return@LaunchedEffect
        while (true) {
            val crudo = runCatching { activePlayer.currentPosition }.getOrDefault(0L)
            val desfase = runCatching { desfaseDelRemux() }.getOrDefault(0L)
            val pos = runCatching { contentPositionMs() }.getOrDefault(0L)
            val dur = runCatching { contentDurationMs() }.getOrDefault(0L)
            val delReceptor = runCatching { activePlayer.duration }.getOrDefault(0L)
            android.util.Log.i(
                "ArkivBarra",
                "receiver=${crudo}ms + offset=${desfase}ms = ${pos}ms · dur=${dur}ms " +
                    "(receiver said ${delReceptor}ms) → ${
                        if (dur > 0) "%.1f%%".format(pos * 100.0 / dur) else "NO FRACTION (no duration)"
                    }",
            )
            delay(2000)
        }
    }

    fun bump() = controles.huboActividad()

    // Velocidad, zoom, modo noche y el HUD central: todo en `PlayerGestos.kt`. El `bump()` que
    // recibe es lo único que los ata a esta pantalla — cada ajuste cuenta como actividad y
    // reinicia el auto-ocultado de los controles. Va acá abajo, y no con el resto del estado,
    // porque necesita que `bump` ya esté declarado.
    val gestos = rememberEstadoDeGestos(controller, graph.settings) { bump() }
    EfectoDelHudDeBrillo(gestos)




    LaunchedEffect(controles.visible, espejo.buffereando, casting, estadoDlna.activo, marcadores.modo, loadError) {
        android.util.Log.i(
            "ArkivCast",
            "UI bar · controls=${controles.visible} buffering=${espejo.buffereando} casting=$casting " +
                "dlna=${estadoDlna.activo != null} marking=${marcadores.marcando} error=${loadError != null} " +
                "→ overlay=${controles.visible && loadError == null && estadoDlna.activo == null && !marcadores.marcando}",
        )
    }

    // El SPINNER DE CARGA, que es otra cosa que el overlay de controles de arriba (ese log dice
    // `overlay=` y es la barra de transporte; confundirlos cuesta una ronda de medición).
    //
    // Se loguea aparte porque el fallo que interesa es invisible desde afuera: el arranque negro con
    // sonido es exactamente el instante en que `espejo.buffereando` ya es false y todavía no hay imagen, o
    // sea que ninguna de las señales viejas lo delata. Con `sinImagen` se ve si el spinner tapó ese
    // hueco o si la pantalla se quedó en negro.
    LaunchedEffect(playlist == null, magisItem == null, liveItem == null, dituPlay == null, espejo.buffereando, sinPrimeraImagen, esperandoVideo, casting) {
        val spinner = hayQueMostrarElSpinner(
            sinPlaylist = playlist == null && magisItem == null && liveItem == null && dituPlay == null,
            buffereando = espejo.buffereando,
            sinPrimeraImagen = sinPrimeraImagen,
            perdioLaSalidaDeVideo = esperandoVideo,
            casting = casting,
        )
        android.util.Log.w(
            "ArkivSpinner",
            "spinner=$spinner " +
                "· noPlaylist=${playlist == null && magisItem == null && liveItem == null && dituPlay == null} buffering=${espejo.buffereando} noImage=$sinPrimeraImagen " +
                "lostVideo=$esperandoVideo",
        )
    }

    // Al abrir contenido distinto al que está cargado, cortar la reproducción anterior ANTES de
    // resolver la fuente nueva. Sin esto, el resolver web (lento, ~10s) dejaba el video previo
    // sonando detrás del overlay "Resolviendo…", y al volver atrás el player retomaba el video viejo.
    // Si el episodio YA es parte de la playlist cargada (navegación dentro de una serie de archive),
    // no se corta: el efecto de abajo reusa el buffer y salta dentro de la playlist.
    LaunchedEffect(episodeId) {
        val kind = PlayerSource.kindFor(episodeId)
        val loadedIds = (0 until controller.mediaItemCount).mapNotNull { controller.getMediaItemAt(it).mediaId }
        val yaCargado = episodeId in loadedIds
        android.util.Log.w("ArkivPlay", "PlayerScreen enter episodeId=$episodeId kind=$kind alreadyInController=$yaCargado loaded=$loaded loadedIds=$loadedIds")
        if (!yaCargado) {
            // stop() corta el video viejo; el setMediaItems de abajo reemplaza la playlist cuando la
            // fuente nueva termina de resolver.
            android.util.Log.w("ArkivPlay", "stop() + loaded=false (new episodeId)")
            controller.stop()
            loaded = false
        }
        vm.load(episodeId)
    }

    // Carga inicial de la playlist en el controller (una sola vez; editar marcadores no recarga).
    // `generacionVivo` además de `playlist`: reabrir un canal cortado republica un PlaylistData
    // IGUAL al anterior y el StateFlow lo descarta, así que sin esta clave el efecto no volvía a
    // correr y la reapertura no cargaba nada. Ver su KDoc en PlayerViewModel.
    LaunchedEffect(playlist, generacionVivo) {
        val pl = playlist ?: run { android.util.Log.w("ArkivPlay", "playlist=null (still resolving or discarded)"); return@LaunchedEffect }
        // Vivo (Tarea 14) YA NO pasa por acá (Task 1, poda de light-magis): `abrirCanalActual` deja
        // de publicar `_playlist` y publica `liveItem` -- ver LiveExoPlayer/isLiveExo más arriba y
        // el LaunchedEffect(casting, liveItem, generacionVivo) que reemplaza el cast-to-TV que antes
        // vivía acá. `enVivo` sigue existiendo para el resto de la pantalla (overlay/gestos/D-pad),
        // pero este efecto es puramente VOD desde ahora.
        // ¿Esto es lo que pidió ESTA pantalla, o todavía es la playlist del capítulo anterior? El
        // ViewModel sobrevive a la navegación entre capítulos, así que al entrar al siguiente lo
        // publicado sigue siendo lo de antes durante todo el resolve (~4 s en magis). Se pregunta
        // ANTES de tocar `loaded`, la posición o el cast: darla por buena era reproducir el capítulo
        // anterior desde el principio y —peor— dejar `loaded=true`, con lo que la playlist buena ya
        // no entraba nunca. Ver el KDoc de PlaylistData.pedido y MediaReusePolicy.decide.
        // Captured once and reused below by ReloadPositionPolicy: it needs the exact same
        // "what's the controller currently on" identity that the reuse decision itself used.
        val actualMediaId = controller.currentMediaItem?.mediaId
        val decision = MediaReusePolicy.decide(
            episodeId = episodeId,
            // Qué hay cargado, con su URI. La URI se lee de requestMetadata y NO de localConfiguration:
            // este lado es el controller, y localConfiguration se pierde al cruzar el IPC (ver
            // PlaybackService.MediaItemResolverCallback). Without the URI, "it's the same episode" was
            // the only signal to reuse it — and that signal alone isn't enough: the local server or
            // the origin URL can change from one load to the next even if the episode is the same
            // (it used to be torrent's case, now removed; today it's the live server's and Magis's
            // token's).
            loaded = (0 until controller.mediaItemCount).map { i ->
                val mi = controller.getMediaItemAt(i)
                LoadedMedia(mi.mediaId, mi.requestMetadata.mediaUri?.toString().orEmpty())
            },
            currentMediaId = actualMediaId,
            fresh = pl.items.map { LoadedMedia(it.episodeId, it.mediaUrl) },
            requested = pl.pedido,
            // Did we land back on a NEW screen? Reusing the media with a new surface kills the
            // decoder (see MediaReusePolicy.decide for the measured numbers). The loaded media
            // already painted (the service player's decoder counters) but not on THIS screen, so it
            // painted on another screen's surface: the same question libVLC's
            // `superficieDistintaALaDelVideo` used to answer. False while it never painted.
            newScreen = (serviceExo.videoDecoderCounters?.renderedOutputBufferCount ?: 0) > 0 &&
                !localVideo.renderedFirstFrame,
        )
        if (decision == MediaReusePolicy.Decision.WAIT) {
            android.util.Log.w("ArkivPlay", "playlist for ANOTHER episode (requested=${pl.pedido} ≠ $episodeId) → waiting for mine")
            return@LaunchedEffect
        }
        if (loaded) {
            android.util.Log.w("ArkivPlay", "playlist ready but loaded=true → skip reload (guard). items=${pl.items.map { it.episodeId }}")
            return@LaunchedEffect
        }
        loaded = true
        espejo.saltoA(pl.startPositionMs)
        android.util.Log.w("ArkivPlay", "playlist ready → load. decision=$decision startPos=${pl.startPositionMs}")
        if (casting && castSession != null) {
            val idx = pl.items.indexOfFirst { it.episodeId == episodeId }.coerceAtLeast(0)
            val req = castRequestFor(pl, idx, pl.startPositionMs)
            if (req != null) {
                // La pantalla avanzó a este episodio: NowPlaying es lo que lee la notificación y el
                // remoto entre dispositivos, no bookkeeping local — no puede quedar apuntando al
                // capítulo anterior.
                NowPlaying.episodeId = episodeId
                android.util.Log.w("ArkivPlay", "branch=CAST → the episode goes to Chromecast, the local one stays primed in pause")
                // El local se carga IGUAL —setMediaItems() ya dispara loadMedia() y abre el archivo/URL,
                // eso no lo evita el prepare()— pero NO arranca: playWhenReady=false es lo que hace que
                // no compita con el receptor por el stream. Se deja sin preparar a propósito: el
                // prepare() real ocurre al reanudar (LaunchedEffect(casting)) o, si la pantalla se
                // destruyó antes de reanudar, en el guard STATE_IDLE de sameEpisodePlaying/samePlaylist
                // de más abajo. Cargarlo es necesario igual: si no, seguiría conteniendo el capítulo
                // anterior y al desconectar reanudaría ése en vez del que se estaba viendo.
                currentIndex = idx
                controller.setMediaItems(localMediaItems(pl.items), idx, pl.startPositionMs)
                controller.playWhenReady = false
                castSession.setMedia(req)
                casteadoAlReceptor = episodeId
                return@LaunchedEffect
            }
            // Sin URL que el receptor pueda alcanzar. NO se corta acá: si se cortara, la app quedaría
            // mintiendo para siempre —`loaded` ya está en true así que nadie reintenta, y el local
            // seguiría con el capítulo ANTERIOR mientras la pantalla dice que este está sonando—.
            // Se cae a la reproducción local normal: el usuario pidió un capítulo, se lo damos en el
            // celu, y el Toast ya explica que a la TV no se pudo mandar.
            android.util.Log.w("ArkivCast", "casting but there's no URL to send the receiver → plays on the phone instead")
            android.widget.Toast.makeText(
                context,
                "No se pudo castear: la TV no puede alcanzar este stream (revisa el WiFi)",
                android.widget.Toast.LENGTH_SHORT,
            ).show()
        }
        when (decision) {
            // Inalcanzable: se corta arriba, apenas se calcula la decisión. La rama existe porque el
            // `when` sobre Decision es exhaustivo.
            MediaReusePolicy.Decision.WAIT -> Unit
            // Mismo episodio ya en curso Y con la misma URL: re-enganchar (aprovecha el buffer).
            MediaReusePolicy.Decision.REUSE_CURRENT -> {
                android.util.Log.w("ArkivPlay", "branch=REUSE_CURRENT → controller.play() (does NOT reload media)")
                currentIndex = controller.currentMediaItemIndex
                // El controller puede llegar acá cebado-pero-no-preparado: la rama CAST de arriba lo
                // carga con setMediaItems() sin prepare(), y si el cast se desconectó ESTANDO AFUERA del
                // reproductor (botón de cast / notificación) la pantalla se destruyó de por medio — el
                // prepare() de LaunchedEffect(casting) nunca llegó a correr porque casteabaAntes es un
                // `remember` de esa composición, no del player. play() sobre un player IDLE no arranca
                // nada; prepararlo primero es inofensivo si ya estaba preparado.
                if (controller.playbackState == Player.STATE_IDLE) {
                    controller.prepare()
                    markLocalLoad(prefersSoftware = false)
                }
                controller.play()
            }
            // Misma sección ya cargada (mismas URLs), otro episodio: saltar dentro de la playlist.
            MediaReusePolicy.Decision.SKIP_IN_PLAYLIST -> {
                // `actualMediaId` is guaranteed different from `episodeId` here -- decide() would
                // have returned REUSAR_ACTUAL/RECARGAR otherwise -- so this always resolves to
                // Source.PLAYLIST. Routed through the same policy as RECARGAR below anyway: it's
                // the single place that knows the rule, and the guarantee is decide()'s, not this
                // call site's to re-derive.
                val resume = ReloadPositionPolicy.resumePosition(
                    episodeId = episodeId,
                    currentMediaId = actualMediaId,
                    currentPositionMs = controller.currentPosition.coerceAtLeast(0L),
                    playlistPositionMs = pl.startPositionMs,
                )
                android.util.Log.w(
                    "ArkivPlay",
                    "branch=SKIP_IN_PLAYLIST → seekTo within the playlist (does NOT reload media). " +
                        "resume pos=${resume.positionMs}ms source=${resume.source} (playlist had ${pl.startPositionMs}ms)",
                )
                val idx = pl.items.indexOfFirst { it.episodeId == episodeId }.coerceAtLeast(0)
                currentIndex = idx
                controller.seekTo(idx, resume.positionMs)
                // Mismo caso que REUSAR_ACTUAL de arriba: puede llegar cebado-pero-no-preparado.
                if (controller.playbackState == Player.STATE_IDLE) controller.prepare()
                controller.playWhenReady = true
                markLocalLoad(prefersSoftware = false)
            }
            // New content, or the URL changed under the same episodeId (before: torrent re-served
            // on another port, source now removed; today: magis token renewed on re-resolution):
            // load the playlist with the fresh URL.
            MediaReusePolicy.Decision.RELOAD -> {
                // The stale-playlist race (see ReloadPositionPolicy's KDoc): the screen can reach
                // RECARGAR on a re-mount whose `playlist` StateFlow value is minutes old while the
                // controller kept playing THIS episode in the background the whole time. Resuming
                // at the playlist's `startPositionMs` in that case would throw away real progress
                // -- ask the controller's own clock instead of trusting the playlist blindly.
                val resume = ReloadPositionPolicy.resumePosition(
                    episodeId = episodeId,
                    currentMediaId = actualMediaId,
                    currentPositionMs = controller.currentPosition.coerceAtLeast(0L),
                    playlistPositionMs = pl.startPositionMs,
                )
                android.util.Log.w(
                    "ArkivPlay",
                    "branch=new → setMediaItems + prepare (opens the local player with the fresh URL). " +
                        "resume pos=${resume.positionMs}ms source=${resume.source} (playlist had ${pl.startPositionMs}ms)",
                )
                currentIndex = pl.startIndex
                controller.setMediaItems(localMediaItems(pl.items), pl.startIndex, resume.positionMs)
                controller.playWhenReady = true
                controller.prepare()
                markLocalLoad(prefersSoftware = pl.items.getOrNull(pl.startIndex)?.preferirSoftware == true)
            }
        }
        NowPlaying.episodeId =
            controller.currentMediaItem?.mediaId ?: pl.items.getOrNull(currentIndex)?.episodeId
    }

    // Capítulo cuyo final YA se atendió, para no encadenar dos avances por el mismo final: el
    // reproductor puede repetir su STATE_ENDED y, casteando, el CastPlayer emite además el suyo.
    var finAtendido by remember { mutableStateOf<String?>(null) }

    /**
     * Fin del capítulo → seguir con el siguiente.
     *
     * Until now this didn't exist and only archive.org (source removed in this branch's pruning)
     * advanced, as a side effect: it was the only multi-item source (it loaded the whole section
     * as a playlist), so media3 did the advancing on its own, internally. The others —magis, Ditu,
     * local, and the legacy web/torrent— publish ONE item: when it ended, the player was left in
     * STATE_ENDED with the bar full and nothing else happened.
     *
     * Va por el mismo camino que el botón "Siguiente episodio" del transporte ([onNextEpisode]):
     * navegar a la ruta del capítulo nuevo, que es lo que re-arranca la resolución de la fuente.
     */
    fun alTerminarElCapitulo() {
        android.util.Log.w("ArkivPlay", "alTerminarElCapitulo · pos=${espejo.posicionMs} dur=${espejo.duracionMs} live=$enVivo endHandled=$finAtendido ep=$episodeId")
        // Un directo no termina: su fin es el stream que se cortó, y ahí no hay "siguiente
        // capítulo" que valga (el único siguiente del modo vivo es el zapping). Reopening it isn't
        // hooked here: live channels play on LiveExoPlayer, whose error goes to
        // `vm.reabrirVivoPorCorte` (see `onLiveExoError`).
        if (enVivo) return
        val actual = playlistRef.value?.items?.getOrNull(controller.currentMediaItemIndex)?.episodeId
            ?: episodeId
        if (finAtendido == actual) return
        // Un stream cortado avisa igual que un capítulo terminado: ver AutoAdvance.
        if (!AutoAdvance.isEndOfChapter(espejo.posicionMs, espejo.duracionMs)) {
            android.util.Log.w("ArkivPlay", "end at pos=${espejo.posicionMs} of ${espejo.duracionMs} → not actually the end, not advancing")
            return
        }
        finAtendido = actual
        val siguiente = cabecera.siguiente
        android.util.Log.w("ArkivPlay", "end of $actual → next=$siguiente")
        if (siguiente != null) onNextEpisode(siguiente)
    }

    /**
     * Si tiene sentido ofrecer "corregir los tiempos de este capítulo".
     *
     * Es la respuesta al agujero que dejaba la feature: el editor viejo es de teléfono, está
     * apagado por bandera y edita la SERIE, así que en el Fire TV un tiempo automático malo NO se
     * podía corregir en el aparato. Y la fuente automática se equivoca de verdad (para un capítulo
     * devolvió los créditos etiquetados como opening), sin forma fiable de detectarlo desde acá.
     *
     * Pide capítulo identificable y duración conocida: sin duración, la posición que se marcaría
     * todavía no significa nada.
     */
    val hayMarcadoresQueCorregir = !enVivo && d != null && episodioEnCurso.isNotBlank() && espejo.duracionMs > 0

    /**
     * Guarda la posición actual como fin del opening / inicio del ending **de este capítulo**.
     *
     * Marca por posición y no con un slider a propósito: en el televisor la barra de progreso ya
     * es D-pad (izquierda/derecha hacen seek), así que "poner el capítulo donde termina el opening
     * y confirmar" se hace con los mismos controles de siempre y sin un panel nuevo que navegar.
     */
    fun marcarTiempo(modo: ModoDeMarcado) {
        val posicion = espejo.posicionMs
        marcadores.cerrarMenuDeCapitulo()
        if (modo == ModoDeMarcado.INTRO) vm.setOpeningEnd(posicion, episodioEnCurso)
        else vm.setEndingStart(posicion, episodioEnCurso)
        val que = if (modo == ModoDeMarcado.INTRO) "Intro" else "Outro"
        android.widget.Toast.makeText(
            context,
            "$que de este capítulo guardado en ${formatDuration(posicion)}",
            android.widget.Toast.LENGTH_SHORT,
        ).show()
    }

    fun quitarLosMarcadoresDelCapitulo() {
        marcadores.cerrarMenuDeCapitulo()
        vm.clearMarkers(episodioEnCurso)
        android.widget.Toast.makeText(
            context,
            "Este capítulo queda sin intro ni outro",
            android.widget.Toast.LENGTH_SHORT,
        ).show()
    }

    // Índice/buffering/estado del transporte. Sigue al player activo: al conectar o desconectar
    // el cast, el efecto se relanza solo y el listener se re-engancha al que corresponda.
    val isMagis = magisItem != null   // MagisExoPlayer maneja sus propios errores.
    val isLiveExo = liveItem != null  // LiveExoPlayer maneja sus propios errores (→ reabrirVivoPorCorte).
    val isDitu = dituPlay != null     // DituExoPlayer maneja sus propios errores (→ onDituExoError).
    val isExo = isMagis || isLiveExo || isDitu     // Any in-screen ExoPlayer (vs the local player behind `controller`).
    // The local player's picture, tracks and cues, from the controller. Apart from the transport
    // listener below on purpose: that one follows `activePlayer` (the Chromecast while casting), and
    // these belong to the local player whatever is active.
    DisposableEffect(controller) {
        controller.videoSize.let { localVideo.onVideoSize(it.width, it.height, it.pixelWidthHeightRatio) }
        estadoPistas.onLocalTracksChanged(controller.currentTracks)
        val listener = object : Player.Listener {
            override fun onRenderedFirstFrame() {
                val desdeLaCarga = localVideo.msSinceLoad(android.os.SystemClock.elapsedRealtime())
                android.util.Log.i("ArkivPlay", "local first frame · ${desdeLaCarga}ms after load · screen #$pantallaId")
                localVideo.onFirstFrame()
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                localVideo.onVideoSize(videoSize.width, videoSize.height, videoSize.pixelWidthHeightRatio)
            }

            override fun onTracksChanged(tracks: Tracks) {
                estadoPistas.onLocalTracksChanged(tracks)
            }

            override fun onCues(cueGroup: CueGroup) {
                localSubtitles?.setCues(cueGroup.cues)
            }
        }
        controller.addListener(listener)
        onDispose { controller.removeListener(listener) }
    }

    /**
     * Local counterpart of `exoYaPintoAlgo`: the first-frame spinner rule, fed by [localVideo].
     *
     * `requestedMs`/`positionMs` keep [FirstFrameWait]'s resume-landing branch wired: this may
     * well be a no-op today, because ExoPlayer's `setMediaItems(…, startPositionMs)` opens straight
     * at the requested position instead of opening at 0 and seeking there the way libVLC did (an
     * upcoming device test will settle that) -- but the rule is cheap insurance meanwhile.
     */
    fun localWaitsForFirstFrame(): Boolean {
        val tracks = controller.currentTracks
        return FirstFrameWait.shouldWait(
            loadedMsAgo = localVideo.msSinceLoad(android.os.SystemClock.elapsedRealtime()),
            hadFrame = localVideo.renderedFirstFrame,
            videoTracks = tracks.groups.count { it.type == C.TRACK_TYPE_VIDEO },
            audioTracks = tracks.groups.count { it.type == C.TRACK_TYPE_AUDIO },
            requestedMs = playlistRef.value?.startPositionMs ?: 0L,
            positionMs = controller.currentPosition.coerceAtLeast(0L),
        )
    }

    /**
     * Decoder watchdog for downloaded files (see [DecoderWatchdog]): a load that never painted gets
     * ONE reload preferring a software decoder, at the same position. The preference travels in the
     * item's `preferSoftware` extra, which the service player's codec selector honours; the
     * `software-first decoders for …` line it logs is the proof the rescue actually took effect.
     */
    fun watchLocalDecoder() {
        val ahora = android.os.SystemClock.elapsedRealtime()
        val esperaMs = localVideo.msWithSurface(ahora)
        val pistasDeVideo = controller.currentTracks.groups.count { it.type == C.TRACK_TYPE_VIDEO }
        val recargar = DecoderWatchdog.shouldReloadInSoftware(
            waitingMs = esperaMs,
            renderedFirstFrame = localVideo.renderedFirstFrame,
            videoTracks = pistasDeVideo,
            wantsToPlay = controller.playWhenReady,
            hasSurface = localVideo.hasSurface,
            // A file that fails to open also sits with playWhenReady and no frame: that is the error
            // overlay's business, and a software reload would only reload the failure.
            hasError = controller.playerError != null,
            alreadySoftware = localVideo.loadPrefersSoftware,
        )
        if (!recargar) return
        val pl = playlistRef.value ?: return
        val pos = controller.currentPosition.coerceAtLeast(0L)
        android.util.Log.w(
            "ArkivPlay",
            "decoder watchdog: no frame ${esperaMs}ms with a surface (videoTracks=$pistasDeVideo " +
                "state=${controller.playbackState}) → reloading ${pl.items.getOrNull(currentIndex)?.episodeId} " +
                "in software at ${pos}ms",
        )
        // stop() BEFORE setMediaItems(), or the reload changes nothing -- see reloadInSoftware's KDoc.
        reloadInSoftware(
            controller.asSoftwareReloadPlayer(),
            localMediaItems(pl.items.map { it.copy(preferirSoftware = true) }),
            currentIndex,
            pos,
        )
        markLocalLoad(prefersSoftware = true)
    }

    DisposableEffect(activePlayer) {
        // Snapshot del estado ExoPlayer en el momento en que se monta el listener.
        // Si isExo=true cuando el controller toma el control, STATE_ENDED del reproductor local no debe
        // disparar alTerminarElCapitulo (el ExoPlayer gestiona su propio fin).
        val exoActivoAlMontar = isExo
        android.util.Log.w("ArkivPlay", "DisposableEffect mounted · activePlayer=${activePlayer::class.simpleName} isExo=$exoActivoAlMontar ep=$episodeId")
        espejo.sincronizarTransporte(
            buffereando = activePlayer.playbackState == Player.STATE_BUFFERING,
            reproduciendo = activePlayer.isPlaying,
            quiereReproducir = activePlayer.playWhenReady,
        )
        if (activePlayer.playbackState == Player.STATE_READY && posicionEsDeEstaPantalla()) {
            espejo.leyoElReloj(contentPositionMs(), contentDurationMs())
        }
        currentIndex = controller.currentMediaItemIndex.coerceAtLeast(0)
        val listener = object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                // La IDENTIDAD de lo que suena la manda siempre la playlist local: el CastPlayer
                // tiene un solo ítem cargado y su índice sería siempre 0.
                currentIndex = controller.currentMediaItemIndex
                val epId = playlistRef.value?.items?.getOrNull(controller.currentMediaItemIndex)?.episodeId
                NowPlaying.episodeId = epId
            }

            override fun onPlaybackStateChanged(state: Int) {
                espejo.cambioElBuffering(state == Player.STATE_BUFFERING)
                if (state == Player.STATE_ENDED) {
                    android.util.Log.w("ArkivPlay", "STATE_ENDED · activePlayer=${activePlayer::class.simpleName} exoActiveOnMount=$exoActivoAlMontar pos=${espejo.posicionMs} dur=${espejo.duracionMs} ep=$episodeId")
                    // No disparar auto-avance si había un ExoPlayer activo cuando se montó este
                    // listener: el STATE_ENDED pertenece al reproductor local que no tenía media, no al fin real.
                    if (!exoActivoAlMontar) alTerminarElCapitulo()
                }
            }

            override fun onIsPlayingChanged(playing: Boolean) {
                espejo.cambioElPlaying(playing)
            }

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                espejo.cambioLaIntencion(playWhenReady)
            }

            // Sin esto, un fallo de reproducción no llegaba a NINGUNA parte: el reproductor local lo publica
            // como PlaybackException, pero la pantalla solo pinta `vm.error` —los errores de
            // resolución— así que la película no arrancaba y no aparecía ningún mensaje. Medido el
            // 2026-08-10 en el Fire TV: `EncounteredError` en el log y `error=false` en la UI.
            // The ViewModel decides what to do with this: some failures repair themselves (the
            // historical example was the 404 of a file renamed on archive.org, whose
            // self-repair path was removed along with that source) and others can only be counted.
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                if (isExo) return  // MagisExoPlayer / LiveExoPlayer / DituExoPlayer ya llamaron su onError
                val id = playlistRef.value?.items
                    ?.getOrNull(controller.currentMediaItemIndex)?.episodeId ?: episodeId
                android.util.Log.w("ArkivPlay", "onPlayerError episodeId=$id → ${error.message}")
                vm.onPlaybackFailed(id)
            }
        }
        activePlayer.addListener(listener)
        onDispose { activePlayer.removeListener(listener) }
    }

    // Sondeo: posición/duración (0,5 s) y progreso persistido (5 s).
    // Clave = activePlayer: al conectar/desconectar el cast hay que volver a sondear al que suena.
    LaunchedEffect(activePlayer) {
        var tick = 0
        while (true) {
            delay(500)
            // Fracción buffereada por delante para la barra: % del archivo cacheado por el proxy.
            // Casteando no aplica: lo que bufferea es el receptor, no nosotros — mostrar el buffer
            // local sería una barra que miente.
            espejo.leyoElBuffer(
                when {
                    casting -> 0f
                    else -> {
                        val url = playlistRef.value?.items?.getOrNull(controller.currentMediaItemIndex)?.mediaUrl
                        if (url != null) graph.archiveCacheProxy.bufferedFraction(url) else 0f
                    }
                },
            )
            val ready = activePlayer.playbackState == Player.STATE_READY && posicionEsDeEstaPantalla()
            if (ready) {
                espejo.leyoElReloj(contentPositionMs(), contentDurationMs())
                // The reopen budget is replenished by POSITION, not by `espejo.reproduciendo`.
                // Measured on the Fire TV on 2026-08-14: `espejo.reproduciendo` used to turn true as soon
                // as VLC opened, before the first frame, so a channel that reopened and died at pos=0ms
                // would still replenish all three reopens -- the cap never ran out and the on-screen
                // warning could never appear. Replenishing only once it actually played for a while
                // is what distinguishes "it recovered" from "it reopened and died again".
                if (vivoDeMagis) vm.vivoAndando(espejo.posicionMs)
            }
            estadoPistas.sincronizarSubsOn()
            // "Arranca negro y con sonido": mientras el reproductor ya suelta el audio pero todavía no dio
            // la primera imagen, `playbackState` NO es BUFFERING y la pantalla se quedaba sin
            // spinner y sin imagen. Casteando no aplica: la imagen la pone la TV, no nosotros.
            sinPrimeraImagen = !casting && if (isExo) !exoYaPintoAlgo else localWaitsForFirstFrame()
            if (!isExo && !casting) watchLocalDecoder()
            // Si el video está sonando, un fallo de reproducción anterior ya no describe nada (y
            // encima estaría tapando estos mismos controles). No-op salvo justo después de uno.
            if (ready && activePlayer.isPlaying) vm.onReproduccionViva()
            tick++
            val pos = activePlayer.currentPosition
            val dur = activePlayer.duration
            // mediaId se lee JUNTO a posición y duración: es de quién son esos números. Casteando,
            // el índice local ya apunta al capítulo nuevo apenas se llama a setMediaItems() mientras
            // el receptor sigue con el anterior (CastPlayer.setMediaItemsInternal solo hace
            // queueLoad, no deja seek pendiente: sigue reportando el ítem viejo, listo y
            // reproduciendo). Sin esta comprobación, tocar "siguiente episodio" cerca del final del
            // capítulo N marcaba como VISTO el N+1 antes de que arrancara.
            val mediaId = activePlayer.currentMediaItem?.mediaId
            // ExoPlayer (Magis): playlist local vacía; el episodio lo trae el ítem directamente.
            // Para las demás fuentes lo identifica la playlist LOCAL, no el player activo.
            val epId = when {
                isMagis -> magisItem?.episodeId
                isDitu -> dituPlay?.episodeId
                else -> playlistRef.value?.items?.getOrNull(controller.currentMediaItemIndex)?.episodeId
            }
            // !enVivo (Tarea 14): en vivo no hay "dónde ibas" que guardar -- ni "continuar viendo"
            // ni barra de progreso que reanudar. Sondear/guardar posición en un directo fue justo
            // lo que rompió el VOD de Magis (ver KDoc de LiveController).
            // ExoPlayer no expone mediaId con el episodeId → se omite la comparación para isExo.
            if (!enVivo && tick % 10 == 0 && epId != null &&
                (isExo || mediaId == epId) && ready && activePlayer.isPlaying &&
                dur > 0 && pos in 0 until dur
            ) {
                vm.saveProgress(epId, pos, dur)
                // Cada 600 ticks = 5 min. Magis pinta en TextureView y se captura; Caracol pinta en SurfaceView,
                // así que para él `textureViewDelVideo()` da null y `FrameCapturer.capturar` no hace nada.
                // !casting: casteando, `pos` es la posición del receptor REMOTO, pero el
                // TextureView sigue siendo el LOCAL, que en ese momento no pinta lo que se ve en la
                // tele. Capturarlo guardaría una imagen que no corresponde a esa posición (y se
                // repetiría en cada disparo mientras dure el casteo).
                if (tick % 600 == 0 && !casting) vm.capturarFrame(epId, pos, textureViewDelVideo())
            }
            // Heartbeat while casting: says whether the receiver is REALLY advancing. A position
            // stuck with state=ready means it accepted the media but isn't decoding it.
            if (casting && tick % 6 == 0) {
                android.util.Log.i(
                    "ArkivCast",
                    "heartbeat · pos=${pos}ms dur=${dur}ms state=${activePlayer.playbackState} " +
                        "playing=${activePlayer.isPlaying} item=${activePlayer.currentMediaItem?.mediaId ?: "NONE"}",
                )
            }
        }
    }

    /**
     * Captura el frame al PAUSAR quedándose en el reproductor. Salir tiene su propia captura (en el
     * onDispose de más abajo) y hay otra periódica cada 5 min; esta es la de "pausé para irme a
     * hacer algo", que es justo cuando la miniatura tiene que quedar en lo último que se vio.
     *
     * It hangs off `quiereReproducir` (playWhenReady) and not `reproduciendo` on purpose: that one
     * also drops on every rebuffer (from Magis's CDN, or any network hiccup), so it would capture
     * —half a million pixels,
     * compress and write to disk— on every network stutter. playWhenReady only changes when someone
     * pausa de verdad (el botón, el OK sobre la barra, la sesión de medios, la pérdida de foco de
     * audio).
     *
     * Con la clave en el propio estado corre UNA vez por transición: mientras siga pausado no se
     * relanza, y volver a reproducir tampoco captura (sale por el `return` de arriba).
     *
     * Mismas guardas que los otros dos disparadores: `mediaId == epId` (que el frame no se guarde
     * bajo el id del capítulo equivocado al saltar de episodio), `dur > 0`, `pos in 0 until dur` y
     * `!casting` (casteando la posición es la del receptor remoto y el TextureView local no está
     * pintando eso).
     */
    LaunchedEffect(espejo.quiereReproducir) {
        if (espejo.quiereReproducir || casting) return@LaunchedEffect
        val pos = activePlayer.currentPosition
        val dur = activePlayer.duration
        val mediaId = activePlayer.currentMediaItem?.mediaId
        val epId = when {
            isMagis -> magisItem?.episodeId
            isDitu -> dituPlay?.episodeId
            else -> playlistRef.value?.items?.getOrNull(controller.currentMediaItemIndex)?.episodeId
        }
        if (epId != null && (isExo || mediaId == epId) && dur > 0 && pos in 0 until dur) {
            vm.capturarFrame(epId, pos, textureViewDelVideo())
        }
    }

    // Auto-ocultar los controles mientras reproduce. El timer se reinicia con CUALQUIER tecla
    EfectoDeAutoOcultado(
        estado = controles,
        reproduciendo = espejo.reproduciendo,
        marcando = marcadores.marcando,
        carruselRevelado = estadoCapitulos.revelado,
    )

    // Este BackHandler se agrega ANTES que el de los controles (más abajo), y `OnBackPressedDispatcher`
    // le da prioridad al ÚLTIMO callback agregado: con el overlay visible Y el panel del dato curioso
    // abierto, BACK cierra primero los controles, no el panel. Recién con los controles ya ocultos un
    // segundo BACK cierra el panel. Sigue siendo mejor que nada (sin este handler, BACK con el panel
    // abierto y los controles ocultos saldría del video de una) y no cambia en esta tanda.
    BackHandler(enabled = estadoTrivia.panelAbierto) { estadoTrivia.cerrarPanel() }

    // BACK con el overlay en pantalla lo CIERRA en vez de salir del video; con el overlay ya
    // oculto, este handler queda deshabilitado y BACK sigue de largo a la navegación (= salir),
    // que es el comportamiento de siempre. Sirve igual para el remoto de la TV, el botón del
    // sistema y el gesto de atrás del teléfono.
    // La condición replica la del overlay más abajo (`AnimatedVisibility(visible = ...)`): si solo
    // mirara controles.visible, en modo marcado o con un error en pantalla la variable puede seguir
    // en true sin que se vea nada, y BACK quedaría muerto (ni cierra ni sale). Mantener ambas
    // iguales si se toca una.
    BackHandler(enabled = !enVivo && controles.visible && loadError == null && estadoDlna.activo == null && !marcadores.marcando) {
        controles.ocultar()
    }


    // TV: al mostrarse el overlay, mover el foco de Android desde el video (que hasta ahora
    // atajaba TODAS las teclas con acciones fijas) hacia los controles de Compose, para que el
    // D-pad navegue los botones/la barra como un player real (Netflix/Prime) en vez de mapeos
    // fijos por tecla. Al ocultarse, el foco vuelve al video para el "cualquier tecla = mostrar".
    LaunchedEffect(controles.visible, isTv) {
        if (!isTv) return@LaunchedEffect
        if (controles.visible) {
            runCatching { focos.bar.requestFocus() }
                .onFailure { runCatching { focos.playPause.requestFocus() } }
        } else {
            // Al ocultarse el overlay el carrusel deja de existir: si estadoCapitulos.revelado quedara en
            // true, al reaparecer se mostraría ya abierto pero con el foco en el botón de play.
            estadoCapitulos.ocultar()
            runCatching { videoView?.requestFocus() }
        }
    }

    // TV: al cerrarse el diálogo de audio/subtítulos hay que reubicar el foco a mano. Antes iba al
    // videoView, pero con el overlay todavía visible ese es un punto muerto —su listener descarta
    // las teclas mientras controles.visible es true— y el D-pad dejaba de responder. Vuelve al botón
    // que abrió el diálogo; el video solo tiene sentido si el overlay ya se ocultó.
    // Va en un efecto y no en el onDismiss: cubre cualquier forma en que se cierre el picker
    // (dismiss o el botón Cerrar), sin depender de por dónde salió.
    var subPickerWasOpen by remember { mutableStateOf(false) }
    LaunchedEffect(estadoPistas.pickerAbierto, isTv) {
        if (!isTv) return@LaunchedEffect
        if (estadoPistas.pickerAbierto) {
            subPickerWasOpen = true
            return@LaunchedEffect
        }
        // Solo en la transición abierto→cerrado: sin esta guarda el efecto correría al entrar al
        // player y le robaría el foco inicial a la barra de progreso.
        if (!subPickerWasOpen) return@LaunchedEffect
        subPickerWasOpen = false
        var landed = false
        if (controles.visible) {
            repeat(12) {
                if (landed) return@repeat
                landed = runCatching { focos.subtitles.requestFocus() }.isSuccess
                if (!landed) delay(32)
            }
        }
        if (!landed) runCatching { videoView?.requestFocus() }
    }

    // Lo que antes hacía el listener con el reproductor LOCAL. Al empezar a castear se pausa; al
    // terminar se adelanta hasta donde llegó el receptor antes de reanudar — si no, el sondeo
    // persiste la posición vieja encima de la buena en ≤5s.
    var casteabaAntes by remember { mutableStateOf(false) }
    // `magisItem?.episodeId` in the key, not just `casting`: opening ANOTHER Magis title while the
    // Chromecast is already connected has to push the new one at the receiver. For playlist
    // sources that job belongs to LaunchedEffect(playlist), which Magis never reaches.
    LaunchedEffect(casting, magisItem?.episodeId) {
        if (casting) {
            // Which guard, if any, stops the send. Kept past the Magis fix: every branch below is
            // conditional, and a cast that silently does nothing is the failure mode of this whole
            // screen -- this line is what tells "no session" from "no item" from "already sent".
            android.util.Log.w(
                "ArkivCast",
                "casting=true · pl=${playlistRef.value != null} loaded=$loaded casteado=$casteadoAlReceptor " +
                    "castSession=${castSession != null} magis=${magisItem?.episodeId} " +
                    "live=${liveItem?.episodeId} ditu=${dituPlay?.episodeId}",
            )
            // Conectar el Chromecast con el capítulo YA sonando en el celu es la acción con la que
            // arranca todo el feature, y es este efecto el único que la ve: LaunchedEffect(playlist)
            // no está clavado a `casting` y encima corta con el guard de `loaded`. Sin esto, tocar
            // el botón de cast pausaba el celu, mostraba el cartel… y dejaba la TV en su pantalla de
            // reposo para siempre.
            // La posición sale del reproductor LOCAL, que es donde está parado el usuario — no de
            // pl.startPositionMs, que es donde arrancó el capítulo hace media hora.
            val pl = playlistRef.value
            val idx = pl?.items?.indexOfFirst { it.episodeId == episodeId }?.coerceAtLeast(0)
            val epId = idx?.let { pl.items.getOrNull(it)?.episodeId }
            if (pl != null && idx != null && epId != null && loaded && casteadoAlReceptor != epId && castSession != null) {
                val desde = runCatching { controller.currentPosition }.getOrDefault(0L).coerceAtLeast(0L)
                val req = castRequestFor(pl, idx, desde)
                if (req == null) {
                    android.util.Log.w("ArkivCast", "session open but there's no URL to send the receiver")
                    android.widget.Toast.makeText(
                        context,
                        "No se pudo castear: la TV no puede alcanzar este stream (revisa el WiFi)",
                        android.widget.Toast.LENGTH_SHORT,
                    ).show()
                } else {
                    android.util.Log.w("ArkivCast", "session open → sending the current episode to the receiver from ${desde}ms")
                    castSession.setMedia(req)
                    casteadoAlReceptor = epId
                }
            }

            // MAGIS. Its item never enters `playlist` -- the ViewModel publishes it in `magisItem`
            // and MagisExoPlayer plays it -- so the block above, which reads `playlistRef`, never
            // ran for it: connecting the Chromecast on a Magis title paused the phone, showed the
            // card, and left the TV on its idle screen forever, with no error anywhere. Measured
            // 2026-09-12: `pl=false loaded=false magis=magis:7B66…`, and `castRequestFor` was
            // never even entered.
            //
            // Same synthetic one-item PlaylistData the live channel above already uses, and for
            // the same reason: `castRequestFor` reads nothing from PlaylistData but `items` and
            // the index, so reusing it beats a second copy of the lanUrl/mime/audio logic that
            // could drift from it.
            val mg = magisItem
            // A streaming MPEG-TS is PREPARED before it is cast, not while. Casting the segments
            // and remuxing at the same time means downloading the same title twice at once through
            // one proxy, and measured on 2026-09-12 they starved each other: three live
            // connections to the origin, a broken pipe, the export stalled and the receiver frozen
            // at `state=2`. Reading it once, then casting the result, is the whole point of
            // waiting. The effect below does the preparing; this one stays quiet until it lands.
            val esperandoRemux = mg != null &&
                magisEsTs(mg) &&
                mg.castUrl.orEmpty() !in remuxImposible &&
                graph.tsRemuxer.alreadyDone(mg.castUrl.orEmpty()) == null
            if (esperandoRemux) {
                android.util.Log.w("ArkivCast", "magis ts: preparing the mp4 before casting, nothing sent yet")
            }
            if (mg != null && !esperandoRemux && castSession != null && casteadoAlReceptor != mg.episodeId) {
                // From the LOCAL ExoPlayer, which is where the person actually is. `activePlayer()`
                // is no use here: `casting` is already true, so it answers the receiver.
                val posLocal = runCatching { magisPlayer?.currentPosition }.getOrNull()
                val desde = (posLocal ?: mg.startPositionMs).coerceAtLeast(0L)
                android.util.Log.w(
                    "ArkivCast",
                    "magis → cast · ep=${mg.episodeId} from=${desde}ms " +
                        "(${if (posLocal != null) "live position of the local player" else "no local player yet, using the saved startPosition"})",
                )
                val req = castRequestFor(PlaylistData(listOf(mg), 0, desde, pedido = mg.episodeId), 0, desde)
                if (req == null) {
                    android.util.Log.w("ArkivCast", "magis: no URL the receiver can reach (no LAN ip, or the proxy isn't up)")
                    android.widget.Toast.makeText(
                        context,
                        "No se pudo castear: la TV no puede alcanzar este stream (revisa el WiFi)",
                        android.widget.Toast.LENGTH_SHORT,
                    ).show()
                } else {
                    android.util.Log.w("ArkivCast", "magis → receiver · uri=${req.uri.take(90)} mime=${req.mimeType} start=${req.startPositionMs}ms")
                    castSession.setMedia(req)
                    casteadoAlReceptor = mg.episodeId
                    // Same reason as the live effect: the notification and the remote read this,
                    // and it must not keep pointing at whatever played before.
                    NowPlaying.episodeId = mg.episodeId
                }
            }

            runCatching { controller.pause() }
            // `controller` is the service player; Magis plays on its own ExoPlayer, so pausing the
            // former did nothing for it. That gap was harmless while Magis could not cast at all
            // (it was noted as accepted in the resume branch below) -- now that it does cast,
            // leaving it out means the phone and the TV play the same title at once.
            if (magisPlayer != null) {
                android.util.Log.i("ArkivCast", "pausing the local Magis player so it doesn't play over the cast")
                runCatching { magisPlayer?.pause() }
            }
        } else if (casteabaAntes) {
            casteadoAlReceptor = null
            casteadoComoRemux = null
            // Stop converting what nobody is going to watch. The remux covers the whole title, so
            // a cast that ends after ten minutes would otherwise keep pulling the other hour and
            // fifty down the person's connection.
            magisItem?.castUrl?.takeIf { it.isNotBlank() }?.let { graph.tsRemuxer.stop(it) }
            // Si la sesión terminó porque el usuario pulsó "parar" (botón de la barra), NO hay que
            // reanudar acá: pidió silencio, y el local ya quedó pausado desde que empezó el casteo
            // (rama de arriba) — reanudarlo sería justo lo contrario de lo que pidió ese botón. Se
            // consume una sola vez: la próxima desconexión (la del botón de cast, no la de parar)
            // vuelve a reanudar normal.
            if (graph.castSession?.consumeIntentionalStop() != true) {
                if (enVivo) {
                    // Vivo (Tarea 18): sin "dónde ibas" que reanudar -- sería la posición que
                    // reporta el receptor sobre un HLS en vivo, que no significa nada como offset
                    // dentro del proxy local (ver el KDoc de castRequestFor/CastRequestBuilder).
                    // Vivo vía ExoPlayer (Task 1, poda de light-magis): `livePlayer` nunca se pausó
                    // al empezar a castear (mismo gap ya aceptado para `magisPlayer`, ver el
                    // `controller.pause()` de la rama `if (casting)` de arriba), así que acá no hay
                    // nada que reanudar -- sigue sonando local igual que durante el casteo. Este
                    // `controller.prepare()/play()` es sobre el reproductor local, que no es el que suena en vivo.
                    // `enVivo` sin `liveItem` es un canal de Caracol, que suena en DituExoPlayer: para
                    // él esta rama termina en el mismo `prepare()/play()` del controller que la de VOD
                    // de abajo, que sin playlist (`loadDitu` la deja en null) tampoco reanuda nada.
                    runCatching { controller.prepare() }
                    runCatching { controller.play() }
                    casteabaAntes = casting
                    return@LaunchedEffect
                }
                // MAGIS: resume on ITS player, not on the service one. Same question as VOD below
                // -- "did the receiver report a position for THIS episode?" -- but a seek is enough
                // here: MagisExoPlayer was only paused (see the branch above), never unloaded, so
                // there is nothing to reload.
                val mg = magisItem
                if (mg != null) {
                    val castMediaId = runCatching { castPlayer?.currentMediaItem?.mediaId }.getOrNull()
                    val castPos = if (castMediaId == mg.episodeId) {
                        runCatching { CastProgress.contentPosition(castPlayer?.currentPosition ?: 0L) }
                            .getOrDefault(0L)
                    } else {
                        android.util.Log.w(
                            "ArkivCast",
                            "magis resume: receiver position discarded, it's for '$castMediaId' and we're resuming '${mg.episodeId}'",
                        )
                        0L
                    }
                    android.util.Log.w(
                        "ArkivCast",
                        "magis ← cast · resuming locally at ${castPos}ms (player=${if (magisPlayer != null) "ready" else "gone"})",
                    )
                    if (castPos > 0L) runCatching { magisPlayer?.seekTo(castPos) }
                    runCatching { magisPlayer?.play() }
                    casteabaAntes = casting
                    return@LaunchedEffect
                }
                val pl = playlistRef.value
                val epId = pl?.items?.getOrNull(currentIndex)?.episodeId
                // La posición del receptor solo vale si es de ESTE episodio. El CastPlayer nunca se
                // para (`stop()` no se llama nunca y `setRemoteMediaClient(null)` no resetea nada en
                // media3), así que `currentPosition` sigue devolviendo para siempre la última posición
                // reportada: castear A hasta 45:00, desconectar fuera del reproductor, abrir B y
                // conectar/desconectar dejaría a B saltando a 45:00 — y el sondeo lo persistiría.
                val castMediaId = runCatching { castPlayer?.currentMediaItem?.mediaId }.getOrNull()
                val castPos = if (epId != null && castMediaId == epId) {
                    // Sin transcodificador el receptor cuenta desde el mismo punto que el archivo:
                    // solo hace falta acotar un TIME_UNSET a "no sé" (0).
                    runCatching {
                        CastProgress.contentPosition(castPlayer?.currentPosition ?: 0L)
                    }.getOrDefault(0L)
                } else {
                    android.util.Log.w("ArkivCast", "receiver position discarded: it's for '$castMediaId', we're resuming '$epId'")
                    0L
                }
                // RELOAD, don't seek: `setMediaItems(…, castPos)` puts the local player at the
                // receiver's position whether or not it was ever prepared (the CAST branch loads it
                // without preparing). libVLC needed it because it baked the old start time into its
                // media (the local resumed where casting STARTED); with ExoPlayer it stays correct.
                if (castPos > 0L && pl != null) {
                    runCatching { controller.setMediaItems(localMediaItems(pl.items), currentIndex, castPos) }
                }
                // El local pudo quedar cebado SIN preparar (rama CAST de arriba): recién acá, al reanudar
                // de verdad, se prepara. If it was already prepared (it was playing locally before
                // casting) prepare() is a no-op.
                val recargado = castPos > 0L && pl != null
                val estabaSinPreparar = controller.playbackState == Player.STATE_IDLE
                runCatching { controller.prepare() }
                runCatching { controller.play() }
                // A new first frame comes only if the media was reloaded or prepared just now;
                // otherwise the first-frame spinner and the decoder watchdog would wait for nothing.
                if (recargado || estabaSinPreparar) markLocalLoad(prefersSoftware = false)
            }
        }
        casteabaAntes = casting
    }

    // Al marcar (archive): pausar y ubicar el slider en el valor ya guardado (si existe).
    // El reanudar (play) SOLO aplica al SALIR del modo marcado — no en la composición inicial:
    // si no, al abrir una fuente web nueva este play() reviviría el video anterior (que sigue
    // cargado en el service) por detrás del overlay "Resolviendo…" mientras se resuelve la nueva.
    LaunchedEffect(marcadores.modo) {
        when (marcadores.transicion()) {
            true -> {
                controller.pause()
                val yaGuardado = when (marcadores.modo) {
                    ModoDeMarcado.INTRO -> d?.openingEndMs
                    ModoDeMarcado.OUTRO -> d?.endingStartMs
                    else -> null
                }
                if (yaGuardado != null) {
                    controller.seekTo(yaGuardado)
                    espejo.saltoA(yaGuardado)
                }
            }
            false -> controller.play()
            null -> Unit
        }
    }

    // Orientación / barras del sistema en teléfono.
    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    LaunchedEffect(isLandscape) {
        val window = activity?.window ?: return@LaunchedEffect
        val wic = WindowInsetsControllerCompat(window, window.decorView)
        wic.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (isLandscape) wic.hide(WindowInsetsCompat.Type.systemBars())
        else wic.show(WindowInsetsCompat.Type.systemBars())
    }

    // El player vigente, leído desde efectos de vida larga. NO alcanza con escribir `activePlayer`
    // dentro del onDispose de abajo: con clave `Unit` el remember no se rehace nunca, así que el
    // onDispose que corre es el que se construyó en la PRIMERA composición — cuando todavía no se
    // casteaba y `activePlayer` era, por valor, el controller local. (`casting` sí se ve vivo desde
    // los closures porque es un delegado de MutableState; `activePlayer` es un val capturado.)
    // Tampoco sirve poner `activePlayer` como clave del efecto: eso lo destruiría y recrearía en
    // cada conexión/desconexión de cast, ejecutando su onDispose —y su controller.pause()— a mitad
    // de la sesión. rememberUpdatedState da el valor fresco sin tocar el ciclo de vida del efecto.
    val currentPlayer by rememberUpdatedState(activePlayer)
    // Igual que currentPlayer: el ítem ExoPlayer vigente al salir / ir al fondo, para que onDispose
    // y el observador de ciclo de vida lean el episodeId correcto aunque la pantalla ya esté saliendo.
    val currentMagisItem by rememberUpdatedState(magisItem)
    val currentDituPlay by rememberUpdatedState(dituPlay)
    val currentEnVivo by rememberUpdatedState(enVivo)

    DisposableEffect(Unit) {
        onDispose {
            // Cortar la reproducción local ANTES de guardar la posición: así el audio se calla al
            // instante al salir (evita ~1s de cola). Con Home el composable NO se destruye, así que
            // esto no corre y el audio sigue de fondo; back/swipe sí destruye y pausa (como hoy).
            // La posición sale del player ACTIVO (Chromecast si hay sesión); el pause() en cambio va
            // siempre al local: pausar el Chromecast al salir de la pantalla anularía el casteo.
            // Un salto incremental sin confirmar (ver `seekBy`) ES la posición que el usuario eligió:
            // salir dentro de esos 350 ms no puede guardar la anterior. Solo local: casteando este
            // destino está en tiempo de CONTENIDO y `currentPosition` es la del receptor, que con
            // ventana lleva otro origen — ahí se mantiene lo de siempre.
            val pendiente = seek.pendienteMs?.takeIf { !casting }
            val pos = pendiente ?: currentPlayer.currentPosition
            val dur = currentPlayer.duration
            // De quién son esos números: mismo problema que el sondeo. Salir de la pantalla justo
            // después de saltar de capítulo escribía la posición del capítulo VIEJO (el receptor
            // todavía no había cambiado de ítem) bajo el id del NUEVO, y savePlayback recalcula
            // "visto" con eso. `pos in 0 until dur` también faltaba acá.
            val mediaId = currentPlayer.currentMediaItem?.mediaId
            controller.pause()
            val isExoOnDispose = currentMagisItem != null || currentDituPlay != null
            // ExoPlayer: playlist local vacía; el episodeId lo trae el ítem directamente.
            val epId = when {
                currentMagisItem != null -> currentMagisItem?.episodeId
                currentDituPlay != null -> currentDituPlay?.episodeId
                else -> playlistRef.value?.items?.getOrNull(currentIndex)?.episodeId
            }
            // !enVivo (Tarea 14): salir de un canal en vivo no tiene "posición" que guardar.
            if (!enVivo && epId != null && (isExoOnDispose || mediaId == epId) && dur > 0 && pos in 0 until dur) {
                vm.saveProgress(epId, pos, dur)
                // Captura de SALIDA. Igual que en el sondeo: con Caracol no hay TextureView y no se captura nada.
                // !casting: mismo motivo que en el sondeo periódico — casteando, `pos` es la
                // posición del receptor remoto, pero el TextureView local no está pintando eso.
                if (!casting) vm.capturarFrame(epId, pos, textureViewDelVideo())
            }
            activity?.let {
                it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                WindowCompat.getInsetsController(it.window, it.window.decorView)
                    .show(WindowInsetsCompat.Type.systemBars())
                // Devolver el brillo al control del sistema (el gesto de brillo lo había fijado).
                it.window.let { w ->
                    val lp = w.attributes
                    lp.screenBrightness = android.view.WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                    w.attributes = lp
                }
            }
            estadoDlna.detenerAlSalir()
        }
    }

    // Guarda progreso cuando la app va al fondo (botón Home, notificaciones, etc.). El onDispose
    // de arriba solo corre al DESTRUIR la pantalla (back/swipe); con Home el composable sobrevive,
    // y si el proceso muere después la posición se perdería.
    //
    // Después de guardar, pausa: con Home los ExoPlayer de esta pantalla (Magis, el vivo, Caracol)
    // seguían sonando afuera. Qué se pausa y cómo lo decide [alIrseAlFondo]; `isTv` es el que pasa
    // `ArkivTvRoot`, la raíz que `MainActivity` elige con `DeviceType.isTelevision`. Al volver, un
    // video queda en pausa donde iba y un canal en vivo vuelve al directo: sonando si sonaba, en pausa
    // si estaba en pausa ([alVolverAlDirecto]).
    DisposableEffect(lifecycleOwner) {
        // El directo que se detuvo al irse al fondo y si sonaba en ese momento, para decidir al volver.
        // Solo si al volver sigue siendo el reproductor activo: si mientras tanto se armó otro, ese no
        // se toca.
        var directoDetenido: Player? = null
        var sonabaAlSalir = false
        val obs = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_START) {
                val detenido = directoDetenido
                directoDetenido = null
                if (detenido != null && detenido === currentPlayer) {
                    val alVolver = alVolverAlDirecto(sonabaAlSalir)
                    android.util.Log.w("ArkivPlay", "app back in foreground → the live stream primes at the edge · $alVolver")
                    runCatching {
                        // Detenido no tiene nada cargado: sin `prepare()` quedaría quieto aunque la
                        // persona le diera play.
                        detenido.seekToDefaultPosition()
                        detenido.prepare()
                        if (alVolver == AlVolverAlDirecto.REANUDAR_EN_EL_DIRECTO) detenido.play()
                    }
                }
            }
            if (event == androidx.lifecycle.Lifecycle.Event.ON_STOP) {
                val isExoStop = currentMagisItem != null || currentDituPlay != null
                val epId = when {
                    currentMagisItem != null -> currentMagisItem?.episodeId
                    currentDituPlay != null -> currentDituPlay?.episodeId
                    else -> playlistRef.value?.items?.getOrNull(currentPlayer.currentMediaItemIndex)?.episodeId
                }
                val pos = currentPlayer.currentPosition
                val dur = currentPlayer.duration
                val mediaId = currentPlayer.currentMediaItem?.mediaId
                if (!enVivo && epId != null && (isExoStop || mediaId == epId) && dur > 0 && pos in 0 until dur) {
                    vm.saveProgress(epId, pos, dur)
                    if (!casting) vm.capturarFrame(epId, pos, textureViewDelVideo())
                }

                val jugador = currentPlayer
                // `controller` IS the local player: downloaded files play on the ExoPlayer hosted by
                // PlaybackService, and this screen reaches it only through `controller`. So for a
                // local file `jugador === controller`, `esExoPlayer` is false and the phone gets SEGUIR:
                // it keeps playing in the background, with the media notification. Never route local
                // files to an in-screen player, or this rule starts pausing them. Pinned by
                // PausaAlSalirTest.
                val accion = alIrseAlFondo(
                    esTv = isTv,
                    esExoPlayer = jugador !== controller,
                    casteando = casting,
                    enVivo = currentEnVivo,
                )
                android.util.Log.w(
                    "ArkivPlay",
                    "app to background → $accion · tv=$isTv casting=$casting live=$currentEnVivo " +
                        "player=${jugador::class.simpleName}",
                )
                when (accion) {
                    AlIrseAlFondo.SEGUIR -> Unit
                    AlIrseAlFondo.PAUSAR -> runCatching { jugador.pause() }
                    AlIrseAlFondo.DETENER_EL_DIRECTO -> {
                        // Antes de pausarlo: si la persona ya lo tenía en pausa, al volver sigue así.
                        sonabaAlSalir = jugador.playWhenReady
                        runCatching {
                            jugador.pause()
                            jugador.stop()
                        }
                        directoDetenido = jugador
                    }
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    // Transporte por el player activo (Chromecast si hay sesión, si no el local).
    val seekStepMs = 10_000L

    /**
     * Moves playback to [targetMs] of the CONTENT.
     *
     * While casting, `activePlayer` is already the `CastPlayer`: the seek goes straight to the
     * receiver, with no transcoder in between (removed -- see `castRequestFor`'s KDoc).
     */
    /**
     * Is the thing on the TV a remux being written, and therefore unseekable?
     *
     * It is announced as a live stream -- the only framing that stopped the receiver inventing an
     * end and stalling against it -- and a live stream has no timeline to move along. The seek
     * still had to be BLOCKED rather than simply failing: letting it through sent the receiver a
     * position it could not honour, the bar drew the destination, nothing arrived, and the bar
     * ended up further from the truth than before the attempt. Reported as "the bar goes crazy,
     * and trying to skip forward made it worse".
     */
    fun castEsUnDirecto(): Boolean =
        casting && magisItem != null && magisEsTs(magisItem!!) &&
            magisItem!!.castUrl?.let { graph.tsRemuxer.alreadyDone(it) == null } == true

    fun seekTo(targetMs: Long) {
        if (castEsUnDirecto()) {
            android.util.Log.w("ArkivCast", "seek ignored: the remux is still being written, so it is cast as live")
            android.widget.Toast.makeText(
                context,
                "Mientras se prepara para la TV no se puede adelantar",
                android.widget.Toast.LENGTH_SHORT,
            ).show()
            bump()
            return
        }
        val dur = contentDurationMs()
        val target = targetMs.coerceIn(0L, if (dur > 0) dur else Long.MAX_VALUE)
        activePlayer.seekTo(target)
        espejo.saltoA(target)
        bump()
    }

    /**
     * Salto incremental: NO toca el player todavía, solo mueve el destino y deja que
     * [SEEK_INCREMENTAL_DEBOUNCE_MS] confirme uno solo.
     *
     * Cada pulsación era un `seekTo` real, o sea un Range request y su rebuffer: moverse dos
     * minutos con el D-pad de la TV son doce. En Magis cada rango puede tardar de 0,2 a 20 s, así
     * que la ráfaga de saltos competía contra sí misma. Se acumula sobre el destino anterior (y no
     * sobre la posición del player) para que la cuenta no dependa de si el seek anterior ya aterrizó.
     *
     * Mientras hay uno pendiente se prende `seek.arrastrando`, lo mismo que usa el arrastre del slider:
     * la barra y el reloj se pintan con el destino, así que se ve a dónde vas aunque el video siga
     * en el fotograma viejo. Es el mismo comportamiento que Netflix o Prime en TV.
     */
    fun seekBy(deltaMs: Long) {
        seek.saltar(deltaMs, contentPositionMs(), contentDurationMs())
        bump()
    }

    // Confirma la ráfaga: cada pulsación nueva cambia la clave y cancela este `delay`, así que el
    // `seekTo` sale una sola vez, cuando dejaste de moverte. Al limpiar el pendiente el efecto se
    // relanza con null y corta en la primera línea.
    LaunchedEffect(seek.pendienteMs) {
        val target = seek.pendienteMs ?: return@LaunchedEffect
        delay(SEEK_INCREMENTAL_DEBOUNCE_MS)
        seekTo(target)
        seek.confirmado()
    }

    fun togglePlayPause() {
        if (activePlayer.isPlaying) {
            activePlayer.pause()
            // Guarda posición al pausar: si la app se cierra mientras está en pausa (crash, Fire
            // Stick reinicia), la posición está guardada y no se pierde.
            val epId = when {
                isMagis -> magisItem?.episodeId
                isDitu -> dituPlay?.episodeId
                else -> playlistRef.value?.items?.getOrNull(currentIndex)?.episodeId
            }
            val pos = activePlayer.currentPosition
            val dur = activePlayer.duration
            val mediaId = activePlayer.currentMediaItem?.mediaId
            if (!enVivo && epId != null && (isExo || mediaId == epId) && dur > 0 && pos in 0 until dur) {
                vm.saveProgress(epId, pos, dur)
                if (!casting) vm.capturarFrame(epId, pos, textureViewDelVideo())
            }
        } else {
            activePlayer.play()
        }
        // Vivo no usa bump()/controles.visible (ese overlay entero está oculto -- ver más abajo,
        // "visible = !enVivo && ..."): sin esta guarda, togglePlayPause() (alcanzable desde el
        // centro del D-pad en TV) dejaba controles.visible en true igual, y el BackHandler de abajo
        // (atado a esa misma variable) se comía el primer BACK cerrando un overlay invisible en
        // vez de salir del reproductor.
        if (!enVivo) bump()
    }

    // El `setOnKeyListener` de más abajo se arma UNA sola vez, dentro del `factory` del AndroidView
    // que crea el TextureView del reproductor local, y ese factory no vuelve a correr en la vida de la pantalla. Sin
    // este puente la lambda del listener se queda con el `togglePlayPause`/`seekBy` de la PRIMERA
    // composición, que leen el `activePlayer` de ese momento (el `controller` local, porque
    // `magisPlayer`/`livePlayer`/`dituPlayer` todavía no se habían publicado) y ya no el reproductor
    // que de verdad suena. Mismo patrón que `currentPlayer` más arriba.
    val togglePlayPauseActual by rememberUpdatedState { togglePlayPause() }
    val seekByActual by rememberUpdatedState { deltaMs: Long -> seekBy(deltaMs) }

    val onOpenEpisodesState = rememberUpdatedState(onOpenEpisodes)

    val outerModifier = if (isLandscape) Modifier.fillMaxSize()
    else Modifier.fillMaxSize().systemBarsPadding()

    Box(Modifier.fillMaxSize().background(Color.Black).clipToBounds(), contentAlignment = Alignment.Center) {
        AndroidView(
            modifier = outerModifier,
            factory = { ctx ->
                android.view.TextureView(ctx).also { tv ->
                    // Transparent until it paints: the black Box shows through, and the in-screen
                    // players (Magis, live, Caracol) draw on top of it.
                    tv.isOpaque = false
                    val previo = videoView
                    videoView = tv
                    if (previo != null) {
                        android.util.Log.w(
                            "ArkivVout",
                            "FACTORY #$pantallaId replaces videoView " +
                                "#${Integer.toHexString(System.identityHashCode(previo))} → " +
                                "#${Integer.toHexString(System.identityHashCode(tv))}",
                        )
                    }
                    // Bound on the player itself, not through `controller`: see PlaybackEngine.
                    serviceExo.setVideoTextureView(tv)
                    localVideo.onSurfaceAttached(android.os.SystemClock.elapsedRealtime())
                    android.util.Log.w("ArkivVout", "ATTACH factory#$pantallaId view=#${Integer.toHexString(System.identityHashCode(tv))}")
                    // The activity handles rotation itself (configChanges), so this view is resized in
                    // place and the aspect transform has to follow its new size.
                    tv.addOnLayoutChangeListener { v, l, t, r, b, oldL, oldT, oldR, oldB ->
                        if (r - l != oldR - oldL || b - t != oldB - oldT) {
                            (v as android.view.TextureView).ajustarAlAspecto(localVideo.aspect, gestos.zoomParaExo)
                        }
                    }
                    if (isTv) {
                        tv.isFocusable = true
                        tv.isFocusableInTouchMode = true
                        tv.setOnKeyListener { _, keyCode, event ->
                            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                            if (marcadores.marcando) return@setOnKeyListener false
                            // Con el overlay de controles visible, el foco de Android ya está en
                            // los botones de Compose (ver LaunchedEffect(controles.visible)) y este
                            // listener ni siquiera debería recibir el evento; el fallback existe
                            // solo por si el foco no llegó a moverse a tiempo.
                            if (controles.visible) return@setOnKeyListener false
                            // ARRIBA con los controles ocultos y datos cargados: en vez de abrir el
                            // overlay, despliega el dato curioso que sigue. Va ANTES que el bloque
                            // de vivo porque ahí arriba zapea, y un canal no lleva datos igual.
                            if (!enVivo && keyCode == KeyEvent.KEYCODE_DPAD_UP && TriviaDelPlayer.hayBoton(trivia)) {
                                estadoTrivia.mostrarSiguiente(trivia.size)
                                return@setOnKeyListener true
                            }
                            // Vivo (Tarea 14): Arriba/Abajo zapean en vez de mostrar el overlay de
                            // VOD (que en vivo no existe, ver `visible = !enVivo && ...`), e
                            // Izquierda/Derecha no hacen seek (no hay duración/posición en vivo).
                            // El zapeo y el cajón son del vivo de Magis: en un canal de Caracol las
                            // flechas no hacen nada y el centro sigue siendo play/pausa.
                            if (enVivo) {
                                // El cajón se queda con la flecha izquierda ANTES que nada. Con el
                                // cajón abierto este listener ya no recibe teclas (el foco de
                                // Android está en las filas de Compose), así que acá solo puede
                                // pasar el caso "cerrado + izquierda".
                                if (vivoDeMagis) {
                                    val accionDelCajon =
                                        DrawerDpad.action(keyCode, estadoVivo.cajonAbierto, estadoVivo.focoCajon)
                                    if (accionDelCajon == DrawerAction.OPEN) {
                                        estadoVivo.abrirCajon()
                                        return@setOnKeyListener true
                                    }
                                }
                                return@setOnKeyListener when (keyCode) {
                                    KeyEvent.KEYCODE_DPAD_UP ->
                                        if (vivoDeMagis) { vm.zapAnterior(); estadoVivo.mostrarInfo(); true } else false
                                    KeyEvent.KEYCODE_DPAD_DOWN ->
                                        if (vivoDeMagis) { vm.zapSiguiente(); estadoVivo.mostrarInfo(); true } else false
                                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
                                    KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                                    KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE ->
                                        { togglePlayPauseActual(); true }
                                    else -> false
                                }
                            }
                            when (keyCode) {
                                KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD ->
                                    { seekByActual(seekStepMs); true }
                                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND ->
                                    { seekByActual(-seekStepMs); true }
                                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
                                KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                                KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE ->
                                    { togglePlayPauseActual(); true }
                                // Cualquier otra flecha o MENÚ, con el overlay oculto: solo mostrarlo
                                // (igual que Netflix/Prime) — la navegación real entre botones pasa
                                // a manejarla el foco de Compose una vez visible.
                                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_MENU ->
                                    { bump(); true }
                                else -> false
                            }
                        }
                        tv.post { tv.requestFocus() }
                    }
                }
            },
            // Aspect and zoom through the TextureView transform, same as the in-screen players.
            update = { it.ajustarAlAspecto(localVideo.aspect, gestos.zoomParaExo) },
            // A no-op when the incoming screen already bound its own view: ExoPlayer only clears the
            // view it is using. That's the ordering problem (outgoing release after incoming attach)
            // the libVLC code had to log around.
            onRelease = { tv ->
                android.util.Log.w("ArkivVout", "DETACH onRelease#$pantallaId")
                serviceExo.clearVideoTextureView(tv)
                localVideo.onSurfaceDetached()
            },
        )

        // Embedded subtitles of a downloaded file (cues from the local player, see the controller
        // listener). Mounted only while the local player is the one playing —the in-screen players
        // draw their own— and confined to the picture: with the video letterboxed, cues belong under
        // the image, not at the bottom of the screen.
        if (!isExo) {
            Box(outerModifier, contentAlignment = Alignment.Center) {
                AndroidView(
                    modifier = if (localVideo.aspect > 0f) Modifier.aspectRatio(localVideo.aspect) else Modifier.fillMaxSize(),
                    factory = { ctx ->
                        SubtitleView(ctx).apply {
                            setUserDefaultStyle()
                            setUserDefaultTextSize()
                        }.also { localSubtitles = it }
                    },
                )
            }
        }

        // Magis: ExoPlayer reproduce el stream del proxy local (headers ya inyectados), sin VLC.
        val mItem = magisItem
        if (mItem != null) {
            MagisExoPlayer(
                mediaUrl = mItem.mediaUrl,
                espejo = espejo,
                startPositionMs = mItem.startPositionMs,
                subtitleConfigs = (webExtras?.subtitles ?: emptyList()).toExoSubtitleConfigs(),
                onPlayerReady = { player ->
                    magisPlayer = player
                    estadoPistas.setExoPlayer(player)
                    gestos.setExoPlayer(player)
                },
                onTextureViewReady = { tv -> magisTextureView = tv },
                onError = { msg -> vm.onMagisExoError(msg) },
                // Nobody else watches for the end of a Magis episode: the screen's listener stays
                // quiet while an ExoPlayer is active, on the grounds that its STATE_ENDED belongs
                // to a local player holding nothing. True, but it left the end unhandled entirely.
                onFinDelCapitulo = { alTerminarElCapitulo() },
                onTracksChanged = { tracks -> estadoPistas.actualizarPistasExo(tracks) },
                onPrimeraImagen = { hay -> exoYaPintoAlgo = hay },
                zoom = gestos.zoomParaExo,
            )
        }

        // Caracol: DASH con Widevine, sin proxy local (ver DituExoPlayer). Reanuda desde la misma
        // posición que usaría Magis: `safeStartPosition`, calculada en PlayerViewModel.loadDitu.
        // Dentro de `key(dPlay)`: cada publicación trae una `generacion` nueva, así que una recarga
        // rearma el reproductor aunque Caracol devuelva la misma URL.
        val dPlay = dituPlay
        if (dPlay != null) key(dPlay) {
            DituExoPlayer(
                mediaUrl = dPlay.playable.url,
                drmLicenseUrl = dPlay.playable.drmLicenseUrl,
                drmLicenseHeaders = dPlay.playable.drmLicenseHeaders,
                descargaLocal = dPlay.descargaLocal,
                almacen = graph.almacenDeCaracol,
                espejo = espejo,
                startPositionMs = dPlay.startPositionMs,
                arrancarSolo = dPlay.arrancarSolo,
                onPlayerReady = { player ->
                    dituPlayer = player
                    estadoPistas.setExoPlayer(player)
                    gestos.setExoPlayer(player)
                },
                onError = { codigo, queriaReproducir ->
                    // Desde dónde retomar si el ViewModel pide una URL nueva. Se le pregunta al
                    // player y no al espejo, que se pone al día recién con el sondeo de medio segundo.
                    val pos = dituPlayer?.currentPosition?.coerceAtLeast(0L) ?: dPlay.startPositionMs
                    vm.onDituExoError(codigo, pos, queriaReproducir)
                },
                pedirRepreparado = { vm.dituPuedeRepreparar() },
                onPosicion = { pos, reproduciendo -> vm.dituAvanzo(pos, reproduciendo) },
                onTracksChanged = { tracks -> estadoPistas.actualizarPistasExo(tracks) },
                onPrimeraImagen = { hay -> exoYaPintoAlgo = hay },
                zoom = gestos.zoomParaExo,
            )
        }

        // Canal en vivo (Task 1, poda de light-magis): ExoPlayer reproduce el HLS del proxy local
        // (LiveHlsProxy, headers ya inyectados contra el CDN), sin VLC -- mismo patrón que Magis.
        // Sin subtítulos ni reanudación: un directo no los tiene. El error se manda a
        // `reabrirVivoPorCorte()` -- ver el KDoc de `onLiveExoError` -- en vez de a un cartel, para
        // que un tropiezo pasajero del CDN/proxy no interrumpa la reproducción con un error visible.
        val lItem = liveItem
        if (lItem != null) {
            LiveExoPlayer(
                mediaUrl = lItem.mediaUrl,
                // Ver el KDoc de `key` en LiveExoPlayer: `mediaUrl` NO cambia entre canales (la
                // URL del proxy es fija), así que sin esto zapear no recrearía el player.
                key = lItem.episodeId to generacionVivo,
                espejo = espejo,
                onPlayerReady = { player ->
                    livePlayer = player
                    gestos.setExoPlayer(player)
                },
                onError = { msg -> vm.onLiveExoError(msg) },
                onPrimeraImagen = { hay -> exoYaPintoAlgo = hay },
                zoom = gestos.zoomParaExo,
            )
        }

        // MODO NOCHE: velo negro ENCIMA del video y DEBAJO de los controles, a propósito — así los
        // controles se siguen leyendo a brillo normal, que es justo cuando hacen falta de noche.
        // Sin modificadores de gesto: sin ellos no es blanco de hit-testing, así que la capa de
        // gestos de abajo (tap/seek/volumen/brillo del teléfono) sigue recibiendo todos los toques.
        if (dimNivel > 0) {
            Box(
                Modifier
                    .matchParentSize()
                    .background(Color.Black.copy(alpha = dimNivel.toFloat() / DIM_MAX_LEVEL)),
            )
        }

        // Capa de GESTOS (solo teléfono, ambas fuentes; portada de TorrentPlayerScreen): tap = controles;
        // doble-tap izq/der = ∓10s; mantener presionado = 2× temporal; swipe horizontal = seek;
        // swipe vertical der = volumen / izq = brillo. Para archive, un swipe grande hacia abajo abre
        // la lista de episodios (se mantiene "como hoy").
        if (!isTv) {
            Box(
                Modifier.fillMaxSize()
                    // `casting` va como CLAVE, no solo como condición adentro: pointerInput lanza su
                    // corrutina una vez y se queda con las lambdas de esa composición hasta que
                    // cambia una clave. Sin esto, el doble-tap seguiría llamando al seekBy de la
                    // composición previa al cast, que capturó `activePlayer` (un val) apuntando al
                    // player local — y en teléfono hay que tocar la pantalla para que aparezca el
                    // botón de cast, así que esa lambda es SIEMPRE anterior a la sesión.
                    .pointerInput(casting) {
                        detectTapGestures(
                            onTap = {
                                // Vivo (Tarea 14): el tap muestra/oculta la FICHA de canal, no la
                                // barra de controles de VOD (que en vivo ni se compone -- ver
                                // `visible = !enVivo && ...` más abajo). Mismo par mostrar/ocultar
                                // que controles.visible/bump() de VOD, con su propio estado. La ficha
                                // es del vivo de Magis: en un canal de Caracol el tap no muestra nada.
                                if (enVivo) estadoVivo.alternarInfo()
                                else controles.alternar()
                            },
                            onDoubleTap = { o ->
                                // Sin seek en vivo (no hay duración ni "adelante/atrás" que tengan sentido).
                                if (!enVivo) { if (o.x < size.width / 2) seekBy(-seekStepMs) else seekBy(seekStepMs) }
                            },
                            onLongPress = {
                                // Casteando no: el 2× temporal actúa sobre el reproductor local, que no
                                // es lo que reproduce el Chromecast — el gesto queda inerte. Tampoco en
                                // vivo: un 2x temporal sobre un directo no tiene "adelante" al que volver.
                                if (!enVivo && !casting) {
                                    gestos.empezarAAcelerar()
                                }
                            },
                            onPress = {
                                tryAwaitRelease()
                                // Restaura SIEMPRE, aunque el cast haya arrancado a mitad del
                                // gesto: si no, la velocidad local queda pegada en 2× y al terminar
                                // la sesión de cast la reproducción local resume rápida. Restaurarla
                                // no hace daño mientras castea (el motor local está pausado igual).
                                gestos.terminarDeAcelerar()
                            },
                        )
                    }
                    // `casting` también va como clave acá: el swipe de seek lee `activePlayer` en
                    // onDragStart/onDrag/onDragEnd, y sin reiniciar el detector esas lambdas se
                    // quedan con el player local aunque la sesión de cast ya esté viva.
                    .pointerInput(casting) {
                        var horizontal = false
                        var decided = false
                        var startX = 0f
                        var seekTarget = 0L
                        var totalDx = 0f
                        var totalDy = 0f
                        detectDragGestures(
                            onDragStart = { o ->
                                decided = false; horizontal = false; startX = o.x
                                totalDx = 0f; totalDy = 0f
                                // Casteando, el seek horizontal debe partir/aplicarse sobre el
                                // player activo (Chromecast), no siempre el local.
                                seekTarget = activePlayer.currentPosition.coerceAtLeast(0)
                            },
                            onDragEnd = {
                                // Vivo (Tarea 14): el swipe vertical ES el zapping -- arriba pasa al
                                // siguiente (como si el contenido "subiera", igual que un scroll),
                                // abajo al anterior. UMBRAL_ZAP_PX chico a propósito: es la única
                                // forma de zapear en el teléfono, no compite con nada más (no hay
                                // seek/volumen/brillo en vivo, ver onDrag de abajo). En un canal de
                                // Caracol no hay zapeo (es del vivo de Magis): el swipe no hace nada.
                                if (enVivo) {
                                    if (vivoDeMagis && !horizontal && kotlin.math.abs(totalDy) > UMBRAL_ZAP_PX) {
                                        if (totalDy < 0) vm.zapSiguiente() else vm.zapAnterior()
                                        estadoVivo.mostrarInfo()
                                    }
                                } else if (horizontal) {
                                    activePlayer.seekTo(seekTarget); espejo.saltoA(seekTarget); bump()
                                } else if (totalDy > 240f && totalDy > kotlin.math.abs(totalDx) * 1.5f) {
                                    onOpenEpisodesState.value()
                                }
                                gestos.limpiarHud()
                            },
                            onDrag = { change, drag ->
                                change.consume()
                                totalDx += drag.x; totalDy += drag.y
                                if (!decided) { decided = true; horizontal = kotlin.math.abs(drag.x) >= kotlin.math.abs(drag.y) }
                                // Vivo: nada que dibujar cuadro a cuadro -- el zap se resuelve entero
                                // en onDragEnd, arriba. Sin seek/volumen/brillo, ver su comentario.
                                if (enVivo) return@detectDragGestures
                                if (horizontal) {
                                    val dur = activePlayer.duration.coerceAtLeast(1)
                                    seekTarget = (seekTarget + (drag.x / size.width * 90_000f).toLong()).coerceIn(0L, dur)
                                    gestos.mostrarHud("⏱ ${formatDuration(seekTarget)}")
                                } else if (startX > size.width / 2) {
                                    // Casteando no: el volumen se lee/ajusta sobre el reproductor
                                    // local, que no es lo que suena en el receptor Chromecast —
                                    // gesto inerte.
                                    if (!casting) {
                                        val v = (gestos.volumenActual() - (drag.y / size.height * 150f).toInt()).coerceIn(0, 100)
                                        gestos.ponerVolumen(v); gestos.mostrarHud("🔊 $v%")
                                    }
                                } else {
                                    activity?.window?.let { w ->
                                        val cur = w.attributes.screenBrightness.let { if (it < 0f) 0.5f else it }
                                        val nb = (cur - drag.y / size.height).coerceIn(0.02f, 1f)
                                        w.attributes = w.attributes.apply { screenBrightness = nb }
                                        gestos.mostrarHud("☀ ${(nb * 100).toInt()}%")
                                    }
                                }
                            },
                        )
                    },
            )
        }

        // HUD central del gesto en curso (velocidad/seek/volumen/brillo).
        gestos.hud?.let { hud ->
            Surface(
                modifier = Modifier.align(Alignment.Center),
                color = Color.Black.copy(alpha = 0.6f),
                shape = RoundedCornerShape(12.dp),
            ) {
                Text(
                    hud,
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                )
            }
        }

        // Spinner. Casteando TAMBIÉN se muestra: `espejo.buffereando` sigue al player activo, así que
        // mientras el receptor carga apaga la fila de transporte, y sin spinner la pantalla quedaba
        // con el degradado, la barra superior y el cartel de Chromecast — nada más, ni controles ni
        // una explicación. `esperandoVideo` también se anula casteando: espera a que el reproductor local recupere su
        // salida de video local (hasta 15s tras volver del fondo), que casteando no importa ni va a
        // llegar.
        //
        // MAGIS TAMBIÉN, y antes no: la condición lo excluía (`magisItem == null`) sin explicar
        // por qué, y el efecto era que en cuanto una película de magis cargaba, el spinner dejaba
        // de dibujarse pasara lo que pasara. Como el primer fotograma tarda —medidos 8 s en el Fire
        // Stick— quedaba una pantalla negra muda, que es lo que hacía pensar que la app se había
        // colgado. MagisExoPlayer no dibuja spinner propio, así que no había nada que duplicar.
        if (
            loadError == null && estadoDlna.activo == null &&
            hayQueMostrarElSpinner(
                sinPlaylist = playlist == null && magisItem == null && liveItem == null && dituPlay == null,
                buffereando = espejo.buffereando,
                sinPrimeraImagen = sinPrimeraImagen,
                perdioLaSalidaDeVideo = esperandoVideo,
                casting = casting,
            )
        ) {
            Column(
                modifier = Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                CircularProgressIndicator(color = Color.White, strokeWidth = 3.dp)
                if (resolving) {
                    Text("Resolviendo fuente $fuenteQueResuelve…", color = Color.White.copy(alpha = 0.9f), style = MaterialTheme.typography.labelMedium)
                }
                // Vivo de Magis (Tarea 14): resolver un canal ronda los 3s (dos llamadas al portal,
                // ver KDoc de LiveController) -- sin texto, este mismo spinner se ve idéntico a un
                // cuelgue. Un canal de Caracol no zapea: mientras resuelve lo dice `resolving`, y
                // después cae en el "Cargando video…" de abajo.
                if (vivoDeMagis) {
                    Text("Cambiando de canal…", color = Color.White.copy(alpha = 0.9f), style = MaterialTheme.typography.labelMedium)
                }
                if (esperandoVideo && !casting) {
                    Text("Reanudando video…", color = Color.White.copy(alpha = 0.9f), style = MaterialTheme.typography.labelMedium)
                }
                // El resto de fuentes —magis, archive— no decía NADA mientras cargaba: solo el
                // círculo girando, que es indistinguible de un cuelgue. Se midió una espera de 18 s en
                // el Fire Stick (el CDN rechazó dos rangos y el proxy los reintentó) sin una palabra
                // en pantalla. El texto va solo cuando ningún otro lo cubre, para no amontonar dos
                // renglones diciendo lo mismo.
                if (!resolving && !vivoDeMagis && !esperandoVideo) {
                    Text(
                        if (casting) "Cargando en el receptor…" else "Cargando video…",
                        color = Color.White.copy(alpha = 0.9f),
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
        }

        // Error de resolución.
        loadError?.let { err ->
            Text(
                err,
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.Center)
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color(0xCCB00020))
                    .padding(horizontal = 20.dp, vertical = 14.dp),
                textAlign = TextAlign.Center,
            )
        }

        // Cartel "Dato curioso" arriba y centrado, y el panel que despliega el texto (ver sus KDoc
        // en `TriviaDelPlayer.kt`). En el teléfono el cartel es tocable; en TV se abre con la
        // flecha arriba, ver el listener del video.
        CartelDeTrivia(
            estado = estadoTrivia,
            onTocar = if (isTv) null else ({ estadoTrivia.mostrarSiguiente(trivia.size) }),
        )
        PanelDeTrivia(estadoTrivia, trivia)

        // Casteando a Chromecast.
        if (casting) {
            Row(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    // Tiene que despejar la barra superior, así que comparte su mismo marco de
                    // insets (systemBarsPadding) en vez de un padding fijo medido desde el borde
                    // de pantalla — si no, en equipos con status bar alto se solapan.
                    .systemBarsPadding()
                    .padding(top = 64.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color(0xCC000000))
                    .padding(horizontal = 20.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(Icons.Default.Tv, contentDescription = null, tint = ArkivRed)
                Text("Reproduciendo en Chromecast", color = Color.White)
            }
        }

        // Marcador vigente del capítulo que suena: capítulo o serie, según la precedencia de
        // ChapterMarker.choose (manual-capítulo > manual-serie > auto-capítulo >
        // auto-serie). Reactivo y NO el que quedó horneado en `d` al armar la playlist (Tarea 6
        // pide el automático al gateway en segundo plano y lo guarda DESPUÉS de que este overlay
        // ya se dibujó -- con una lectura estática el botón nunca aparecería para ese capítulo).
        // `remember` lo mantiene con la MISMA identidad de Flow mientras itemId/episodio no
        // cambien: sin esto, cada recomposición (la barra de progreso recompone con cada tick)
        // pediría un Flow nuevo y reiniciaría la consulta a Room todo el tiempo.
        val marcadoresDelCapitulo by remember(d?.itemId, episodioEnCurso) {
            graph.repository.skipMarkerDao().observeForChapter(d?.itemId ?: "", episodioEnCurso)
        }.collectAsStateWithLifecycle(initialValue = emptyList())
        val marcadorVigente = ChapterMarker.choose(
            fromChapter = marcadoresDelCapitulo.firstOrNull { it.episodeId == episodioEnCurso },
            fromSeries = marcadoresDelCapitulo.firstOrNull { it.episodeId.isEmpty() },
        )

        // Botones flotantes de saltar intro/outro. Antes solo salían en el teléfono y fuera de
        // torrents: los marcadores se ponían a mano por serie y solo para archive. Ahora salen de
        // la identidad de la obra (tmdbId + capítulo), así que valen igual en el Fire TV -- que es
        // donde se ve el anime, el caso que motivó todo esto -- y en un capítulo bajado por
        // torrent.
        val accionDelOutro = SaltoDeOutro.decidir(
            indiceActual = currentIndex,
            itemsEnLaPlaylist = playlist?.items?.size ?: 0,
            siguienteCapitulo = cabecera.siguiente,
        )
        // Cuál de los dos botones va, si va alguno. Se calcula acá arriba, lejos de donde se
        // dibuja, por dos motivos: el efecto de foco tiene que ver también el instante en que
        // deja de haber botón (dentro del `if` que lo dibuja se iría de la composición justo
        // entonces y nadie devolvería el foco), y la barra de progreso —que se compone antes—
        // necesita saber si hay botón para mandar su ARRIBA ahí.
        val botonDeSalto = when {
            marcadorVigente == null || marcadores.marcando || estadoDlna.activo != null -> null
            else -> BotonDeSalto.cual(
                enOpening = ChapterMarker.inOpening(marcadorVigente, espejo.posicionMs),
                enEnding = ChapterMarker.inEnding(marcadorVigente, espejo.posicionMs),
                accionDelOutro = accionDelOutro,
                casting = casting,
            )
        }

        // ---- Controles custom (fade in/out) ----
        AnimatedVisibility(
            // Casteando SÍ se muestran: el transporte maneja el Chromecast (ver activePlayer).
            // Los elementos de adentro que solo aplican al reproductor local llevan su propia
            // guarda `!casting`.
            //
            // `!enVivo` (Tarea 14): TODO este bloque es de VOD (slider/seek/"siguiente episodio"/
            // marcadores/carrusel de capítulos) -- en vivo no aplica nada de eso (sin duración, sin
            // seek, sin "siguiente" que no sea zapear). En vez de reescribir cada control de acá
            // adentro con una guarda propia, se corta UNA vez acá arriba (la bandera que aísla el
            // modo vivo, ver KDoc de `enVivo`) y más abajo hay un overlay chico y propio para vivo
            // (badge "EN VIVO" + ficha de canal por 3s).
            visible = !enVivo && controles.visible && loadError == null && estadoDlna.activo == null && !marcadores.marcando,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.fillMaxSize(),
        ) {
            // Velo que hace legibles texto y controles sobre el video. En TV el de arriba se sostiene
            // más abajo (un tramo intermedio en vez de caer de una): el encabezado son dos líneas
            // grandes —la serie y "E131 · nombre del capítulo"— y con la caída del teléfono la
            // segunda quedaba ya sobre el video pelado, ilegible en cualquier fondo claro.
            val velo = if (isTv) {
                arrayOf(
                    0.0f to Color(0xB3000000),
                    0.22f to Color(0x8C000000),
                    0.42f to Color(0x14000000),
                    0.70f to Color(0x14000000),
                    1.0f to Color(0xD9000000),
                )
            } else {
                arrayOf(
                    0.0f to Color(0xB3000000),
                    0.30f to Color(0x14000000),
                    0.70f to Color(0x14000000),
                    1.0f to Color(0xD9000000),
                )
            }
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Brush.verticalGradient(*velo))
                    // Cualquier tecla con el overlay abierto reinicia el timer de auto-ocultado, así
                    // no se desvanece encima mientras navegás botones o miniaturas. Antes solo lo
                    // reiniciaba bump(), que dispara el listener del video — y ese únicamente actúa con
                    // los controles OCULTOS, así que moverse con el D-pad no lo reiniciaba nunca.
                    // Va en el contenedor y como PREVIEW (no onKeyEvent): el preview baja desde la
                    // raíz antes de llegar al control enfocado, así que ve todas las teclas aunque
                    // alguien las consuma — el slider consume izq/der para el seek y la fila consume
                    // ABAJO, que con el burbujeo normal nunca habrían llegado hasta acá.
                    // Devuelve false: solo observa, no altera el despacho.
                    .onPreviewKeyEvent { e ->
                        if (e.type == KeyEventType.KeyDown) controles.sigueVivo()
                        false
                    }
                    // Zona segura del TV. Va DESPUÉS del `background` a propósito: el degradado
                    // sigue pintando de borde a borde (es el velo que hace legibles los controles
                    // sobre el video) y el padding solo mete para adentro el contenido.
                    //
                    // No es gusto: un TV recorta el borde de la imagen (overscan) y cuánto recorta
                    // depende del aparato. Medido en el Fire Stick, el título quedaba a 16 dp del
                    // canto izquierdo, la duración a 15 dp del derecho y la fila de transporte a
                    // 17 dp del borde inferior — o sea, lo primero que un TV con overscan se come.
                    // Se reusan las constantes de la biblioteca del TV para no tener dos números
                    // que signifiquen lo mismo y se desincronicen.
                    .then(if (isTv) Modifier.padding(horizontal = SAFE_H, vertical = SAFE_V) else Modifier),
            ) {
                // Barra superior: atrás (teléfono) + título + marcadores/CC/cast.
                Row(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .fillMaxWidth()
                        .systemBarsPadding()
                        .padding(horizontal = 6.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (!isTv) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Atrás", tint = Color.White)
                        }
                    }
                    // Editor de marcadores (solo archive, teléfono). Oculto: es el único acceso a
                    // setear intro/outro, así que se conserva detrás de la bandera.
                    if (MOSTRAR_MARCADORES_EN_TELEFONO && !isTv && d != null) {
                        Box {
                            IconButton(onClick = { marcadores.abrirMenu() }) {
                                Icon(Icons.Default.Tune, contentDescription = "Marcadores", tint = Color.White)
                            }
                            DropdownMenu(expanded = marcadores.menuAbierto, onDismissRequest = { marcadores.cerrarMenu() }) {
                                DropdownMenuItem(
                                    text = { Text("Setear intro (fin del opening)") },
                                    onClick = { marcadores.marcar(ModoDeMarcado.INTRO) },
                                )
                                DropdownMenuItem(
                                    text = { Text("Setear outro (inicio del ending)") },
                                    onClick = { marcadores.marcar(ModoDeMarcado.OUTRO) },
                                )
                                DropdownMenuItem(
                                    text = { Text("Borrar marcadores") },
                                    onClick = { marcadores.cerrarMenu(); vm.clearMarkers() },
                                )
                            }
                        }
                    }
                    if (!isTv && d != null) {
                        // Qué se está viendo. El título sale de cabecera.info (nombre de la SERIE) y
                        // no de d.title, para que en series no muestre el nombre del capítulo;
                        // debajo, temporada/capítulo. d.title queda de respaldo si cabecera.info
                        // todavía no cargó (se lee de la DB en un LaunchedEffect).
                        Column(modifier = Modifier.weight(1f).padding(horizontal = 8.dp)) {
                            Text(
                                cabecera.titulo(d.title),
                                color = Color.White,
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            cabecera.etiquetaDeEpisodio?.let { ep ->
                                Text(
                                    ep,
                                    color = Color.White.copy(alpha = 0.75f),
                                    style = MaterialTheme.typography.labelMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    } else {
                        Spacer(Modifier.weight(1f))
                    }
                    // Velocidad + zoom (solo teléfono): cíclicos al tocar. Ambas fuentes.
                    // Ocultos: el gesto de mantener presionado sigue dando 2× temporal, así que no se
                    // pierde el control de velocidad del todo.
                    // Casteando no: siguienteVelocidad()/siguienteZoom() actúan sobre el reproductor local, que
                    // no es lo que reproduce el Chromecast.
                    if (MOSTRAR_VELOCIDAD_Y_ZOOM_EN_TELEFONO && !isTv && !casting) {
                        TextButton(onClick = { gestos.siguienteVelocidad() }) {
                            Text(
                                gestos.etiquetaDeVelocidad,
                                color = if (gestos.velocidadEsNormal) Color.White else ArkivRed,
                                style = MaterialTheme.typography.labelLarge,
                            )
                        }
                        TextButton(onClick = { gestos.siguienteZoom() }) {
                            Text(
                                gestos.etiquetaDeZoom,
                                color = if (gestos.zoomEsAjustar) Color.White else ArkivRed,
                                style = MaterialTheme.typography.labelMedium,
                            )
                        }
                    }
                    // (El botón CC/audio del teléfono se movió abajo a la derecha, junto a la fila
                    // de transporte; en TV siempre estuvo en la fila de íconos inferior.)
                    // DLNA + Chromecast (solo teléfono). Botones compartidos con el modo vivo, ver
                    // `DlnaCastButtons`.
                    if (!isTv) {
                        // Nota propia de este Row: como `estadoDlna.activo != null` esconde el overlay
                        // entero de controles (visible = ... && estadoDlna.activo == null más arriba), el
                        // cast se queda sin forma de manejarse desde la app si DLNA está activo. Por
                        // eso el botón de Chromecast de abajo sí queda visible pase lo que pase: es
                        // el único camino para cortar la sesión.
                        DlnaCastButtons(casting = casting, castContext = castContext, onDiscoverDlna = estadoDlna::descubrir)
                    }
                }

                // TV: qué se está viendo (serie o película) y, si es serie, temporada/capítulo.
                // Va siempre que esté la interfaz, no solo en pausa: es justo cuando uno la abre
                // para saber en qué capítulo va. Como el overlay ya se auto-oculta a los 4.5s, no
                // compite con el video en reproducción.
                if (isTv) {
                    cabecera.info?.let { info ->
                        Column(
                            modifier = Modifier
                                .align(Alignment.TopStart)
                                .systemBarsPadding()
                                // Sin `top`: antes reservaba 56 dp para no pisar la barra superior
                                // de iconos, pero esa barra está entera detrás de `!isTv` — en TV
                                // no dibuja nada. Con la zona segura del contenedor (SAFE_V) esos
                                // 56 dp se sumaban y el título quedaba hundido a ~100 dp del canto.
                                .padding(start = 16.dp, end = 16.dp)
                                // Techo de ancho para que el `Ellipsis` de abajo llegue a aplicarse:
                                // el Column está alineado en un Box a pantalla completa, así que sin
                                // esto se estira con el texto y un nombre largo cruzaría la pantalla
                                // entera por encima del video en vez de cortarse.
                                .fillMaxWidth(0.6f),
                        ) {
                            Text(
                                info.itemTitle,
                                color = Color.White,
                                // headlineMedium (28sp) escalado 1.5×: el nombre de la serie es lo
                                // primero que se lee al pausar y con 28 competía con la línea del
                                // capítulo. El lineHeight va escalado igual (36→54) para que la caja
                                // no le recorte las tildes ni las mayúsculas acentuadas.
                                style = MaterialTheme.typography.headlineMedium.copy(
                                    fontSize = 42.sp,
                                    lineHeight = 54.sp,
                                ),
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Spacer(Modifier.height(6.dp))
                            // "E130 · El oponente de Goku es… ¿Goku?": el número solo no dice de qué
                            // es el capítulo, que es justo lo que uno mira al pausar. El nombre sale
                            // de la misma tabla (`episode_still`) que ya usa el carrusel de abajo, así
                            // que no cuesta una consulta nueva — y cuando no lo tenemos (aún no llegó
                            // del gateway, o es una fuente sin nombres) queda el número solo, como antes.
                            info.episodeLabel?.let { ep ->
                                val nombre = estadoCapitulos.titulos[episodioEnCurso]?.takeIf { it.isNotBlank() }
                                Text(
                                    if (nombre != null) "$ep · $nombre" else ep,
                                    color = Color.White.copy(alpha = 0.75f),
                                    style = MaterialTheme.typography.titleLarge,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }

                Column(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .systemBarsPadding()
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                    ) {
                        // Tiempo + slider + duración.
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                formatDuration(seek.posicionAMostrar(espejo.posicionMs)),
                                color = Color.White, style = MaterialTheme.typography.labelMedium,
                            )
                            Slider(
                                value = seek.valorDeLaBarra(espejo.posicionMs),
                                // Agarrar la barra descarta cualquier salto incremental pendiente: si
                                // no, el debounce de `seekBy` dispararía DESPUÉS de soltar y te
                                // devolvería al destino de las flechas, pisando el arrastre.
                                onValueChange = { v ->
                                    seek.arrastrarA(v)
                                    bump()
                                },
                                onValueChangeFinished = { seekTo(seek.soltar()) },
                                valueRange = 0f..(if (espejo.duracionMs > 0) espejo.duracionMs.toFloat() else 1f),
                                colors = SliderDefaults.colors(
                                    thumbColor = ArkivRed,
                                    activeTrackColor = ArkivRed,
                                    inactiveTrackColor = Color.White.copy(alpha = 0.3f),
                                ),
                                // Track custom con 3 capas: fondo (tenue) + buffer descargado (gris
                                // claro) + reproducido (rojo). Así se ve el buffer por delante del playhead.
                                track = { _ ->
                                    val dur = if (espejo.duracionMs > 0) espejo.duracionMs.toFloat() else 1f
                                    val posFrac = (seek.valorDeLaBarra(espejo.posicionMs) / dur)
                                        .coerceIn(0f, 1f)
                                    val bufFrac = espejo.fraccionBuffereada.coerceIn(0f, 1f)
                                    // El grosor del track ES el indicador de foco (el stroke se deriva
                                    // de la altura del Canvas, así que engrosar la altura engrosa las
                                    // tres capas de una). Animado para que el salto no se sienta brusco.
                                    val trackHeight by animateDpAsState(
                                        targetValue = if (seek.barraEnfocada) 8.dp else 4.dp,
                                        label = "grosorBarraProgreso",
                                    )
                                    Canvas(Modifier.fillMaxWidth().height(trackHeight)) {
                                        val y = size.height / 2f
                                        val sw = size.height
                                        drawLine(Color.White.copy(alpha = 0.25f), Offset(0f, y), Offset(size.width, y), sw, StrokeCap.Round)
                                        if (bufFrac > 0f) drawLine(Color.White.copy(alpha = 0.5f), Offset(0f, y), Offset(size.width * bufFrac, y), sw, StrokeCap.Round)
                                        if (posFrac > 0f) drawLine(ArkivRed, Offset(0f, y), Offset(size.width * posFrac, y), sw, StrokeCap.Round)
                                    }
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(horizontal = 10.dp)
                                    .then(
                                        if (!isTv) Modifier else Modifier
                                            .focusRequester(focos.bar)
                                            .onFocusChanged { seek.cambioElFoco(it.isFocused) }
                                            // ARRIBA se queda en la barra: es el tope del overlay y
                                            // los botones están DEBAJO, así que mandar `up` ahí era
                                            // un salto al revés (poco visible antes, porque el foco
                                            // no entraba acá; ahora es el primer control enfocado).
                                            // ARRIBA se queda en la barra cuando no hay nada
                                            // arriba, pero si el botón de saltar está en pantalla
                                            // sí hay: queda justo encima de la barra (ver su
                                            // padding), así que ese es el camino de vuelta para
                                            // quien se fue del botón y se arrepintió.
                                            .focusProperties {
                                                down = focos.playPause
                                                up = if (botonDeSalto != null) focos.skip else focos.bar
                                                left = focos.bar
                                                right = focos.bar
                                            }
                                            .onKeyEvent { e ->
                                                if (e.type != KeyEventType.KeyDown) return@onKeyEvent false
                                                when (e.key) {
                                                    Key.DirectionRight -> { seekBy(seekStepMs); true }
                                                    Key.DirectionLeft -> { seekBy(-seekStepMs); true }
                                                    // OK sobre la barra alterna play/pausa. Con el foco acá el
                                                    // centro no hacía nada, y pausar es lo más frecuente: obligaba
                                                    // a bajar al botón y volver a subir. Se llama al MISMO
                                                    // `togglePlayPause` que el botón para que no puedan divergir.
                                                    // Se aceptan las dos teclas porque no todos los controles
                                                    // remotos mandan lo mismo: los de Android TV suelen mandar
                                                    // DPAD_CENTER y algunos (y el emulador) mandan ENTER.
                                                    Key.DirectionCenter, Key.Enter -> { togglePlayPause(); true }
                                                    else -> false
                                                }
                                            },
                                    ),
                            )
                            Text(formatDuration(espejo.duracionMs), color = Color.White, style = MaterialTheme.typography.labelMedium)
                        }
                        Spacer(Modifier.height(8.dp))

                        // PORTRAIT DOES NOT FIT. Transport is five buttons and the secondary
                        // controls are up to five more; at 48dp each that is ~430dp of icon before
                        // a single gap, against roughly 379dp of usable width on a phone held
                        // upright. They were not merely cramped, they ran off the screen. So in
                        // portrait they go on a SECOND row and the transport centres itself;
                        // landscape has the room and keeps the single row it always had.
                        val esVertical = LocalConfiguration.current.orientation ==
                            Configuration.ORIENTATION_PORTRAIT
                        // Casting, tracks are chosen on the LOCAL player, so these hide entirely.
                        val haySecundarios = !isTv && !casting
                        val iconosSecundarios: @Composable () -> Unit = {
                            if (hayMarcadoresQueCorregir) {
                                MenuDeMarcadoresDelCapitulo(
                                    estado = marcadores,
                                    isTv = false,
                                    onFinDelOpening = { marcarTiempo(ModoDeMarcado.INTRO) },
                                    onInicioDelEnding = { marcarTiempo(ModoDeMarcado.OUTRO) },
                                    onQuitar = { quitarLosMarcadoresDelCapitulo() },
                                )
                            }
                            IconButton(onClick = { estadoPistas.abrirPicker() }) {
                                Icon(
                                    if (estadoPistas.haySubtitulo) Icons.Default.ClosedCaption else Icons.Default.ClosedCaptionOff,
                                    contentDescription = "Subtítulos y audio",
                                    tint = if (estadoPistas.haySubtitulo) ArkivRed else Color.White,
                                )
                            }
                            // MODO NOCHE (los mismos dos botones que en TV; acá el gesto de
                            // brillo del borde izquierdo sigue existiendo y es independiente:
                            // ese baja el backlight real, estos ponen el velo sobre el video).
                            IconButton(onClick = { gestos.pasoDeBrillo(+1, dimNivel) }) {
                                Icon(
                                    Icons.Default.Brightness2,
                                    contentDescription = "Bajar brillo",
                                    tint = if (dimNivel > 0) ArkivRed else Color.White,
                                )
                            }
                            IconButton(onClick = { gestos.pasoDeBrillo(-1, dimNivel) }) {
                                Icon(
                                    Icons.Default.BrightnessHigh,
                                    contentDescription = "Subir brillo",
                                    tint = if (dimNivel > 0) ArkivRed else Color.White,
                                )
                            }
                            if (TriviaDelPlayer.hayBoton(trivia)) {
                                IconButton(onClick = { estadoTrivia.mostrarSiguiente(trivia.size) }) {
                                    Icon(
                                        Icons.Default.Info,
                                        contentDescription = "Dato curioso",
                                        tint = Color.White,
                                    )
                                }
                            }
                        }

                        // Capítulo anterior / retroceder / play-pausa / adelantar / capítulo
                        // siguiente / subtítulos (TV) — todo en una sola fila, izq/der navegable
                        // con D-pad. Los dos saltos de capítulo van en los extremos del transporte,
                        // como en cualquier reproductor: |< << ▶ >> >|.
                        Row(
                            modifier = Modifier
                                // En teléfono la fila ocupa todo el ancho para poder empujar el
                                // botón de subtítulos contra el borde derecho (ver el final del Row).
                                .then(if (isTv) Modifier else Modifier.fillMaxWidth())
                                .then(
                                // Un paso más de ABAJO desde esta fila revela el carrusel de
                                // capítulos (aún no existe en el árbol hasta que estadoCapitulos.revelado
                                // es true, así que no se puede resolver con un focusProperties.down
                                // normal — se intercepta la tecla acá y se dispara la revelación).
                                if (!isTv || !estadoCapitulos.hayCarrusel) Modifier else Modifier.onKeyEvent { e ->
                                    if (e.type == KeyEventType.KeyDown && e.key == Key.DirectionDown) {
                                        // Cerrado: revelarlo (el LaunchedEffect mueve el foco al chip).
                                        // Ya abierto: bajar el foco al carrusel — antes caía en el
                                        // `down` del botón y no había forma de volver a entrar.
                                        if (!estadoCapitulos.revelado) estadoCapitulos.revelar()
                                        else runCatching { estadoCapitulos.focusRequester.requestFocus() }
                                        true
                                    } else {
                                        false
                                    }
                                },
                            ),
                            // Portrait spreads them across the whole width; landscape keeps the
                            // fixed gaps, because there the spacer below pushes the secondary icons
                            // to the right end of this same row and even spacing would fight it.
                            //
                            // `SpaceEvenly` and not a centred cluster: this row and the one under it
                            // then span the same width and share the same rhythm, which is what
                            // makes them read as one block of controls instead of two leftovers.
                            // It also never overflows -- at 48dp a side, eight icons is the point
                            // where 384dp of phone runs out, and the most this player ever shows
                            // is five.
                            horizontalArrangement = if (esVertical) {
                                Arrangement.SpaceEvenly
                            } else {
                                Arrangement.spacedBy(20.dp)
                            },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            // Los saltos de capítulo dependen SOLO de que el vecino exista, nunca del
                            // estado de transporte. Colgarlos de `reproduciendo` (como estaba el de
                            // siguiente) los hacía parpadear: esa se cae a false en cada
                            // rebuffer y en cada seek —el controller reporta STATE_BUFFERING directo,
                            // sin ningún traductor de por medio—, así que el botón aparecía al
                            // adelantar y se iba solo al volver a READY. Ver el KDoc de `espejo.quiereReproducir`.
                            // Se calculan ANTES de los botones para poder armar el grafo de foco
                            // completo (cada dirección explícita; dejar alguna sin definir hace que
                            // la búsqueda espacial por defecto de Compose falle y el foco "se pierda").
                            val prev = cabecera.anterior
                            val next = cabecera.siguiente
                            val showPrev = prev != null
                            val showNext = next != null
                            val forwardRight = if (showNext) focos.nextEpisode else if (isTv) focos.subtitles else focos.forward
                            val nextRight = if (isTv) focos.subtitles else focos.nextEpisode
                            // Primero de la fila cuando existe: su `left` apunta a sí mismo (tope).
                            if (showPrev) {
                                TvTransportButton(
                                    icon = Icons.Default.SkipPrevious,
                                    contentDescription = "Capítulo anterior",
                                    onClick = { onNextEpisode(prev) },
                                    modifier = if (!isTv) Modifier else Modifier
                                        .focusRequester(focos.previousEpisode)
                                        .focusProperties { left = focos.previousEpisode; right = focos.rewind; up = focos.bar; down = focos.previousEpisode },
                                )
                            }
                            // `down` apunta al propio botón (se queda) y NO al slider: bajar desde acá
                            // llevaba el foco a la barra de progreso, donde izq/der hacen seek — de ahí
                            // el "a veces cambia de botón, a veces adelanta/retrocede". Cuando hay
                            // capítulos, ABAJO lo intercepta el onKeyEvent de la fila (arriba).
                            TvTransportButton(
                                icon = Icons.Default.Replay10,
                                contentDescription = "Atrasar 10s",
                                onClick = { seekBy(-seekStepMs) },
                                modifier = if (!isTv) Modifier else Modifier
                                    .focusRequester(focos.rewind)
                                    .focusProperties {
                                        left = if (showPrev) focos.previousEpisode else focos.rewind
                                        right = focos.playPause
                                        up = focos.bar
                                        down = focos.rewind
                                    },
                            )
                            TvTransportButton(
                                icon = if (espejo.reproduciendo) Icons.Default.Pause else Icons.Default.PlayArrow,
                                contentDescription = if (espejo.reproduciendo) "Pausar" else "Reproducir",
                                onClick = { togglePlayPause() },
                                iconSize = 34.dp,
                                modifier = if (!isTv) Modifier else Modifier
                                    .focusRequester(focos.playPause)
                                    .focusProperties { left = focos.rewind; right = focos.forward; up = focos.bar; down = focos.playPause },
                            )
                            TvTransportButton(
                                icon = Icons.Default.Forward10,
                                contentDescription = "Adelantar 10s",
                                onClick = { seekBy(seekStepMs) },
                                modifier = if (!isTv) Modifier else Modifier
                                    .focusRequester(focos.forward)
                                    .focusProperties { left = focos.playPause; right = forwardRight; up = focos.bar; down = focos.forward },
                            )
                            // Casteando TAMBIÉN se muestra: el capítulo nuevo ahora SIGUE al cast
                            // (la carga se bifurca por `casting` y lo manda al receptor), que es de
                            // lo que se trata este feature. El carrusel de capítulos de la TV nunca
                            // tuvo esta guarda, así que además dejan de contradecirse.
                            if (showNext) {
                                TvTransportButton(
                                    icon = Icons.Default.SkipNext,
                                    contentDescription = "Siguiente episodio",
                                    onClick = { onNextEpisode(next) },
                                    modifier = if (!isTv) Modifier else Modifier
                                        .focusRequester(focos.nextEpisode)
                                        .focusProperties { left = focos.forward; right = nextRight; up = focos.bar; down = focos.nextEpisode },
                                )
                            }
                            if (isTv) {
                                Box(
                                    Modifier
                                        .padding(horizontal = 4.dp)
                                        .width(1.dp)
                                        .height(24.dp)
                                        .background(Color.White.copy(alpha = 0.3f)),
                                )
                                // Enfocado se invierte como los demás (ícono negro sobre blanco); que
                                // los subtítulos estén activos se sigue distinguiendo por el ícono
                                // (ClosedCaption vs ClosedCaptionOff), no solo por el tinte rojo.
                                TvTransportButton(
                                    icon = if (estadoPistas.haySubtitulo) Icons.Default.ClosedCaption else Icons.Default.ClosedCaptionOff,
                                    contentDescription = "Subtítulos y audio",
                                    onClick = { estadoPistas.abrirPicker() },
                                    iconSize = 24.dp,
                                    tint = if (estadoPistas.haySubtitulo) ArkivRed else Color.White,
                                    modifier = Modifier
                                        .focusRequester(focos.subtitles)
                                        .focusProperties {
                                            left = if (showNext) focos.nextEpisode else focos.forward
                                            right = focos.dimDown
                                            up = focos.bar
                                            down = focos.subtitles
                                        },
                                )
                                // MODO NOCHE, dos botones: bajar (luna) a la izquierda y subir (sol)
                                // a la derecha, o sea menos → más como se lee. Nunca se deshabilitan
                                // en los topes: en TV un botón deshabilitado no recibe foco y
                                // rompería la cadena del D-pad justo al llegar al extremo.
                                TvTransportButton(
                                    icon = Icons.Default.Brightness2,
                                    contentDescription = "Bajar brillo",
                                    onClick = { gestos.pasoDeBrillo(+1, dimNivel) },
                                    iconSize = 24.dp,
                                    tint = if (dimNivel > 0) ArkivRed else Color.White,
                                    modifier = Modifier
                                        .focusRequester(focos.dimDown)
                                        .focusProperties {
                                            left = focos.subtitles
                                            right = focos.dimUp
                                            up = focos.bar
                                            down = focos.dimDown
                                        },
                                )
                                TvTransportButton(
                                    icon = Icons.Default.BrightnessHigh,
                                    contentDescription = "Subir brillo",
                                    onClick = { gestos.pasoDeBrillo(-1, dimNivel) },
                                    iconSize = 24.dp,
                                    tint = if (dimNivel > 0) ArkivRed else Color.White,
                                    modifier = Modifier
                                        .focusRequester(focos.dimUp)
                                        .focusProperties {
                                            left = focos.dimDown
                                            right = when {
                                                TriviaDelPlayer.hayBoton(trivia) -> focos.trivia
                                                hayMarcadoresQueCorregir -> focos.markers
                                                else -> focos.dimUp
                                            }
                                            up = focos.bar
                                            down = focos.dimUp
                                        },
                                )
                                // Datos curiosos: solo existe si hay datos. Va al FINAL de la fila a
                                // propósito -- insertarlo en el medio obligaría a reescribir varios
                                // eslabones de esta cadena de foco. El `right` del botón de arriba
                                // ya lo tiene previsto.
                                if (TriviaDelPlayer.hayBoton(trivia)) {
                                    TvTransportButton(
                                        icon = Icons.Default.Info,
                                        contentDescription = "Dato curioso",
                                        onClick = { estadoTrivia.mostrarSiguiente(trivia.size) },
                                        iconSize = 24.dp,
                                        tint = Color.White,
                                        // Último de la fila: su `right` apunta a sí mismo (tope derecho).
                                        modifier = Modifier
                                            .focusRequester(focos.trivia)
                                            .focusProperties {
                                                left = focos.dimUp
                                                right = if (hayMarcadoresQueCorregir) focos.markers else focos.trivia
                                                up = focos.bar
                                                down = focos.trivia
                                            },
                                    )
                                }
                                // Corregir los tiempos del capítulo en curso. Va al final de la
                                // fila -- meterlo en el medio obliga a reescribir eslabones de la
                                // cadena de foco.
                                if (hayMarcadoresQueCorregir) {
                                    MenuDeMarcadoresDelCapitulo(
                                        estado = marcadores,
                                        isTv = true,
                                        onFinDelOpening = { marcarTiempo(ModoDeMarcado.INTRO) },
                                        onInicioDelEnding = { marcarTiempo(ModoDeMarcado.OUTRO) },
                                        onQuitar = { quitarLosMarcadoresDelCapitulo() },
                                        modifier = Modifier
                                            .focusRequester(focos.markers)
                                            .focusProperties {
                                                left = if (TriviaDelPlayer.hayBoton(trivia)) focos.trivia else focos.dimUp
                                                right = focos.markers
                                                up = focos.bar
                                                down = focos.markers
                                            },
                                    )
                                }
                            }
                            // LANDSCAPE: they ride at the right end of this same row, pushed
                            // there by the spacer. In portrait they do not fit and go below --
                            // see `esVertical` above.
                            if (haySecundarios && !esVertical) {
                                Spacer(Modifier.weight(1f))
                                iconosSecundarios()
                            }
                        }

                        // PORTRAIT: the secondary controls, on their own row, spread across the
                        // full width on the same rhythm as the transport row above. They were
                        // right-aligned first and it looked like what it was -- leftovers shoved
                        // into a corner, two thirds of the row empty, and the two rows not even
                        // sharing an axis. They keep the order they have in landscape, so the same
                        // icon is in the same place whichever way the phone is held.
                        if (haySecundarios && esVertical) {
                            Spacer(Modifier.height(4.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceEvenly,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                iconosSecundarios()
                            }
                        }
                        // Carrusel de capítulos (TV, series con más de 1 episodio): un paso más
                        // abajo desde la fila de íconos. Todos los episodios en scroll horizontal,
                        // con el actual resaltado y centrado al aparecer.
                        if (isTv && estadoCapitulos.hayCarrusel) {
                            CarruselDeCapitulos(
                                estado = estadoCapitulos,
                                episodioEnCurso = episodioEnCurso,
                                focoDeArriba = focos.playPause,
                                onElegirEpisodio = onNextEpisode,
                            )
                        }
                    }
            }
        }

        // ---- Modo vivo (Tarea 14): overlay propio, chico -- reemplaza TODO el bloque de arriba.
        // Las tres piezas viven en `PlayerVivo.kt`. ----
        if (enVivo) {
            FranjaEnVivo(isTv = isTv, onBack = onBack) {
                // Tarea 18: los MISMOS botones que VOD (`estadoDlna` es uno solo para toda la
                // pantalla), solo que colgados de ESTA franja porque el bloque VOD está oculto acá
                // (visible=!enVivo). Se ofrece DESDE EL REPRODUCTOR, no en el diálogo previo de
                // LiveScreen: recién con el canal sonando hay audio real que leerle a
                // CastAudioSupport (ver el KDoc de castRequestFor) -- antes de reproducir no hay de
                // dónde sacar esa lectura, ni para vivo ni para VOD (VOD tampoco ofrece cast en su
                // propio diálogo de destino, por el mismo motivo).
                DlnaCastButtons(casting = casting, castContext = castContext, onDiscoverDlna = estadoDlna::descubrir)
            }
            // La ficha es del vivo de Magis: su canal y su EPG. Caracol no la tiene.
            if (vivoDeMagis) FichaDelCanal(estado = estadoVivo, canal = liveCanal, liveApi = graph.catalogoDeVivo)
        }

        // Que el botón TENÍA el foco. Es un pestillo y no la lectura viva de `isFocused`: cuando
        // el botón se va de la composición, Compose ya avisó `isFocused = false` antes de que
        // corra el efecto de abajo, y entonces nadie devolvería el foco y el mando quedaría
        // muerto. Se baja a mano en los dos sitios donde el foco sale del botón de verdad: al
        // devolverlo acá abajo, y cuando la persona se va con una flecha.
        var saltoTeniaElFoco by remember { mutableStateOf(false) }
        // Y si lo tiene AHORA. Es la señal con la que el reintento sabe si el foco llegó: pedirlo
        // no informa de nada (ver `insistirConElFoco`), así que lo único fiable es que el propio
        // botón avise por `onFocusChanged`.
        var saltoEnfocado by remember { mutableStateOf(false) }
        val focoDelSalto = remember { FocoDelSalto() }
        LaunchedEffect(botonDeSalto, isTv) {
            if (!isTv) return@LaunchedEffect
            when (focoDelSalto.alCambiar(botonDeSalto, saltoTeniaElFoco, controles.visible)) {
                // El botón acaba de aparecer y se lleva el foco: con el capítulo sonando, un solo
                // OK salta el opening. Sin esto, OK caía en el transporte y PAUSABA el video.
                // Se insiste un rato corto (~320 ms) porque el nodo puede no estar colocado
                // todavía en el frame en que aparece, y porque el foco hay que quitárselo al
                // `videoView` por la interop de Compose. Ver `insistirConElFoco`: la señal de
                // éxito es que el botón avise que lo tiene, NO que pedirlo no haya lanzado.
                FocoDelSalto.Accion.PEDIR -> insistirConElFoco(
                    yaEstaEnfocado = { saltoEnfocado },
                    esperar = { delay(ESPERA_ENTRE_INTENTOS_DE_FOCO_MS) },
                    pedir = { focos.skip.requestFocus() },
                )
                FocoDelSalto.Accion.DEVOLVER_A_LOS_CONTROLES -> {
                    saltoTeniaElFoco = false
                    runCatching { focos.bar.requestFocus() }
                }
                FocoDelSalto.Accion.DEVOLVER_AL_VIDEO -> {
                    saltoTeniaElFoco = false
                    runCatching { videoView?.requestFocus() }
                }
                FocoDelSalto.Accion.NADA -> Unit
            }
        }

        if (botonDeSalto != null && marcadorVigente != null) {
            Row(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .systemBarsPadding()
                    // Los 88 dp de siempre son del TELÉFONO. En el Fire TV el overlay es mucho más
                    // alto y el botón quedaba montado sobre la barra de progreso: medido en el
                    // aparato (1920x1080 a 320 dpi = 960x540 dp), el botón ocupaba 404-452 dp de
                    // alto y la barra 384-428 dp -- se pisaban, y con el foco encima no se
                    // entendía a cuál de los dos le llegaba el OK. Con 180 dp el botón termina en
                    // 360 dp, o sea 24 dp de aire por encima de la barra. El `end` también sube al
                    // margen de zona segura de la TV: 20 dp del canto derecho es justo lo que se
                    // come el overscan (el resto del overlay ya usa SAFE_H por lo mismo).
                    .padding(
                        end = if (isTv) SAFE_H else 20.dp,
                        bottom = if (isTv) 180.dp else 88.dp,
                    ),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SkipButton(
                    text = if (botonDeSalto == BotonDeSalto.INTRO) "Saltar intro" else "Saltar outro",
                    icon = botonDeSalto == BotonDeSalto.OUTRO,
                    modifier = Modifier
                        .focusRequester(focos.skip)
                        .onFocusChanged {
                            saltoEnfocado = it.isFocused
                            if (it.isFocused) saltoTeniaElFoco = true
                        }
                        .then(
                            if (!isTv) Modifier else Modifier.onKeyEvent { e ->
                                if (e.type != KeyEventType.KeyDown) return@onKeyEvent false
                                when (e.key) {
                                    // Ignorar el botón: cualquier flecha lleva el foco a los
                                    // controles y el botón NO se lo vuelve a robar mientras siga
                                    // en pantalla (`FocoDelSalto` solo actúa cuando cambia cuál
                                    // botón hay). Hay que interceptarlas: con el foco en Compose,
                                    // el listener del video —el que abre el overlay con cualquier
                                    // tecla— ya no recibe nada, así que sin esto las flechas no
                                    // harían absolutamente nada y el botón sería una trampa.
                                    Key.DirectionUp, Key.DirectionDown,
                                    Key.DirectionLeft, Key.DirectionRight,
                                    -> {
                                        saltoTeniaElFoco = false
                                        if (controles.visible) runCatching { focos.bar.requestFocus() } else bump()
                                        true
                                    }
                                    // Por lo mismo: los mandos con botón de play propio dejarían
                                    // de pausar mientras el botón tiene el foco.
                                    Key.MediaPlayPause, Key.MediaPlay, Key.MediaPause -> {
                                        togglePlayPause()
                                        true
                                    }
                                    else -> false
                                }
                            },
                        ),
                ) {
                    when (botonDeSalto) {
                        BotonDeSalto.INTRO -> marcadorVigente.openingEndMs?.let { activePlayer.seekTo(it) }
                        // A dónde salta lo decide `SaltoDeOutro` (ver su KDoc):
                        // `seekToNextMediaItem()` on its own only worked on archive.org (source
                        // removed in this branch's pruning), the only multi-item source, and on
                        // magis/Ditu/local -and the legacy web/torrent/NUC- which publish ONE item,
                        // the button still showed up and did NOTHING.
                        BotonDeSalto.OUTRO -> when (accionDelOutro) {
                            SaltoDeOutro.Accion.AVANZAR_EN_LA_PLAYLIST -> controller.seekToNextMediaItem()
                            // El mismo camino que `alTerminarElCapitulo()`: navegar a la ruta del
                            // capítulo nuevo es lo que re-arranca la resolución de la fuente.
                            else -> cabecera.siguiente?.let(onNextEpisode)
                        }
                    }
                }
            }
        }

        // Panel-editor de marcado con slider (solo archive).
        if (d != null && marcadores.marcando) {
            MarkerEditor(
                mode = marcadores.modo!!,
                positionMs = espejo.posicionMs,
                durationMs = espejo.duracionMs,
                onSeek = { p -> seekTo(p) },
                onCancel = { marcadores.terminar() },
                onSave = {
                    val label = if (marcadores.modo == ModoDeMarcado.INTRO) {
                        vm.setOpeningEnd(espejo.posicionMs); "Intro"
                    } else {
                        vm.setEndingStart(espejo.posicionMs); "Outro"
                    }
                    android.widget.Toast.makeText(
                        context,
                        "$label guardado en ${formatDuration(espejo.posicionMs)}",
                        android.widget.Toast.LENGTH_SHORT,
                    ).show()
                    marcadores.terminar()
                },
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }

        // Barra "Reproduciendo en <TV>" (DLNA activo).
        BarraDlnaActiva(estadoDlna)

        // Va ÚLTIMO dentro del Box para quedar por encima del resto de overlays.
        if (isTv && vivoDeMagis && estadoVivo.cajonAbierto) {
            CajonDeCanalesDelVivo(
                estado = estadoVivo,
                canalActual = liveCanal?.code,
                onElegirCanal = { lista, canal -> vm.irACanal(lista, canal) },
            )
        }
    }

    // Al cerrarse el cajón hay que devolverle el foco al video: si no, queda en una fila que ya no
    // existe y el control deja de responder -- ni zapping ni Atrás. El `videoView` es quien tiene
    // el `setOnKeyListener` del vivo.
    LaunchedEffect(estadoVivo.cajonAbierto) {
        if (!estadoVivo.cajonAbierto) {
            repeat(10) {
                if (videoView?.requestFocus() == true) return@LaunchedEffect
                delay(50)
            }
        }
    }

    // Diálogo de dispositivos DLNA. El armado de la URL que se le manda al renderer vive en
    // `mandarAlRenderer`; acá solo queda de qué ítem sale y qué hacer si el renderer la rechaza.
    DialogoDispositivosDlna(estadoDlna) { device ->
        // Vivo vía ExoPlayer (Task 1, poda de light-magis) ya no está en `playlist`: cae a
        // `liveItem`, que trae el mismo `kind = SourceKind.LIVE` que `mandarAlRenderer` necesita
        // para resolver la URL de LAN del proxy (no usa `ep.mediaUrl`/`castUrl` para vivo).
        val ep = playlistRef.value?.items?.getOrNull(currentIndex) ?: liveItem
        controller.pause()
        scope.launch {
            val ok = mandarAlRenderer(dlna, device, ep, { graph.lanIp() }, graph.liveHlsProxy)
            if (ok) {
                estadoDlna.marcarActivo(device)
            } else {
                android.widget.Toast.makeText(
                    context,
                    "No se pudo castear (revisa el WiFi)",
                    android.widget.Toast.LENGTH_SHORT,
                ).show()
            }
        }
    }

    // Diálogo de audio y subtítulos (las pistas que trae el archivo/stream, vía ExoPlayer).
    // Los dos datos que recibe son solo para etiquetar las pistas que magis entrega sin idioma; ver
    // `etiquetaDeSpu`.
    DialogoDeAudioYSubtitulos(
        estado = estadoPistas,
        esMagis = PlayerSource.kindFor(episodeId) == SourceKind.MAGIS,
        idiomasDeclarados = webExtras?.subtitles?.map { it.lang }.orEmpty(),
    )
}

@Composable
private fun MarkerEditor(
    mode: ModoDeMarcado,
    positionMs: Long,
    durationMs: Long,
    onSeek: (Long) -> Unit,
    onCancel: () -> Unit,
    onSave: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth().systemBarsPadding().padding(16.dp),
        color = ArkivSurface.copy(alpha = 0.96f),
        shape = MaterialTheme.shapes.large,
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                if (mode == ModoDeMarcado.INTRO) "Marca el FIN del intro" else "Marca el INICIO del outro",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                "Muévete con el slider hasta la posición exacta y guarda.",
                style = MaterialTheme.typography.bodyMedium,
                color = ArkivTextSecondary,
                modifier = Modifier.padding(top = 2.dp, bottom = 8.dp),
            )
            // Mismo diferido que la barra del reproductor: mientras arrastrás solo se mueve esta
            // fracción local (y el reloj de acá arriba, que si no se quedaba en la posición vieja),
            // y el seek sale UNA vez al soltar. Antes cada paso del dedo era un seek real, que es
            // justo lo que hace inusable marcar sobre una fuente que va por red.
            var arrastre by remember { mutableStateOf<Float?>(null) }
            val posicionMostrada = arrastre?.let { (it * durationMs).toLong() } ?: positionMs
            Text(
                "${formatDuration(posicionMostrada)} / ${formatDuration(durationMs)}",
                style = MaterialTheme.typography.titleLarge,
                color = ArkivRed,
            )
            Slider(
                value = arrastre ?: (if (durationMs > 0) positionMs.toFloat() / durationMs else 0f),
                onValueChange = { v -> arrastre = v },
                onValueChangeFinished = {
                    arrastre?.let { onSeek((it * durationMs).toLong()) }
                    arrastre = null
                },
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onCancel) { Text("Cancelar") }
                Button(onClick = onSave, modifier = Modifier.padding(start = 8.dp)) { Text("Guardar") }
            }
        }
    }
}

/**
 * Botón de la fila de transporte del overlay. Al recibir el foco se invierte (círculo blanco +
 * ícono negro, igual que [SkipButton]) para que desde el sillón se vea de un golpe cuál está
 * seleccionado: el único indicador que había era el ripple de Material, invisible a 3 metros — sin
 * saber dónde estaba el foco, la navegación con D-pad parecía errática.
 * En teléfono nada toma foco en modo táctil, así que se ve igual que antes.
 */
@Composable
private fun TvTransportButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    iconSize: Dp = 32.dp,
    tint: Color = Color.White,
) {
    var focused by remember { mutableStateOf(false) }
    IconButton(
        onClick = onClick,
        modifier = modifier
            .size(48.dp)
            .onFocusChanged { focused = it.isFocused }
            .background(if (focused) Color.White else Color.Transparent, CircleShape),
    ) {
        Icon(
            icon,
            contentDescription = contentDescription,
            tint = if (focused) Color.Black else tint,
            modifier = Modifier.size(iconSize),
        )
    }
}

/**
 * Botón + menú para corregir a mano los tiempos del capítulo en curso.
 *
 * Es la ÚNICA entrada alcanzable en el televisor: vive en la fila de íconos del overlay, o sea
 * dentro del sistema de foco de la pantalla (`PlayerFoco.kt`), y se usa con el D-pad sin salir de
 * la reproducción. En el teléfono va en la misma fila de abajo, al lado de subtítulos.
 */
@Composable
private fun MenuDeMarcadoresDelCapitulo(
    estado: EstadoDeMarcadores,
    isTv: Boolean,
    onFinDelOpening: () -> Unit,
    onInicioDelEnding: () -> Unit,
    onQuitar: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box {
        if (isTv) {
            TvTransportButton(
                icon = Icons.Default.Tune,
                contentDescription = "Corregir intro y outro",
                onClick = { estado.abrirMenuDeCapitulo() },
                iconSize = 24.dp,
                tint = Color.White,
                modifier = modifier,
            )
        } else {
            IconButton(onClick = { estado.abrirMenuDeCapitulo() }, modifier = modifier) {
                Icon(Icons.Default.Tune, contentDescription = "Corregir intro y outro", tint = Color.White)
            }
        }
        DropdownMenu(
            expanded = estado.menuDeCapituloAbierto,
            onDismissRequest = { estado.cerrarMenuDeCapitulo() },
        ) {
            DropdownMenuItem(text = { Text("El opening termina aquí") }, onClick = onFinDelOpening)
            DropdownMenuItem(text = { Text("El ending empieza aquí") }, onClick = onInicioDelEnding)
            DropdownMenuItem(text = { Text("Este capítulo no tiene") }, onClick = onQuitar)
        }
    }
}

@Composable
private fun SkipButton(
    text: String,
    icon: Boolean = false,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    // En TV este botón nace enfocado (ver FocoDelSalto), así que tiene que VERSE enfocado: si no,
    // el borde rojo del resto de los controles desaparece de la pantalla y no se entiende a quién
    // le va a llegar el OK.
    var enfocado by remember { mutableStateOf(false) }
    Button(
        onClick = onClick,
        modifier = modifier.onFocusChanged { enfocado = it.isFocused },
        border = if (enfocado) BorderStroke(2.dp, ArkivRed) else null,
        colors = ButtonDefaults.buttonColors(
            containerColor = if (enfocado) Color.White else Color.White.copy(alpha = 0.92f),
            contentColor = Color.Black,
        ),
    ) {
        Text(text)
        if (icon) Icon(Icons.Default.SkipNext, contentDescription = null)
    }
}
