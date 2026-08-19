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
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.AlertDialog
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
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
import com.arkiv.player.ui.live.AccionDelDrawer
import com.arkiv.player.ui.live.DpadDelDrawer
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
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.compose.ui.text.font.FontWeight
import com.arkiv.player.cast.CastProgress
import com.arkiv.player.ui.tv.library.SAFE_H
import com.arkiv.player.ui.tv.library.SAFE_V
import com.arkiv.player.playback.AutoAvance
import com.arkiv.player.playback.LoadedMedia
import com.arkiv.player.playback.MediaReusePolicy
import com.arkiv.player.playback.NowPlaying
import com.arkiv.player.playback.PlaybackEngine
import com.arkiv.player.playback.PlaybackService
import com.arkiv.player.playback.PlayerSource
import com.arkiv.player.playback.PlayerSourceTag
import com.arkiv.player.playback.SourceKind
import com.arkiv.player.playback.VideoAttachPolicy
import com.arkiv.player.playback.VlcPlayer
import com.arkiv.player.playback.setPlayerSourceTag
import com.arkiv.player.torrent.TorrentProgress
import com.arkiv.player.torrent.TorrentServingService
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
import org.videolan.libvlc.util.VLCVideoLayout

private fun Context.findActivity(): Activity? {
    var ctx: Context? = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}

/** Pasos de velocidad de reproducción (portado de TorrentPlayerScreen). */

/** Pasos de zoom nativo de VLC: 0 = ajustar a pantalla; >0 = crop que recorta las barras negras. */

/** Swipe vertical mínimo (px) para que el modo vivo (Tarea 14) lo tome como zapping en el teléfono. */
private const val UMBRAL_ZAP_PX = 80f

// Controles ocultos en la barra superior del TELÉFONO: llegó a tener 8 elementos y se veían
// amontonados. El código se conserva —no se borra— para poder reactivarlos con un solo cambio acá.
// En TV ninguno de los tres existía. Los subtítulos no se ocultan: se movieron abajo a la derecha.
private const val MOSTRAR_MARCADORES_EN_TELEFONO = false
private const val MOSTRAR_VELOCIDAD_Y_ZOOM_EN_TELEFONO = false

// Modo noche: el velo negro va ENCIMA del video, con opacidad nivel/DIM_MAX_LEVEL — 0 = brillo
// normal (sin velo), DIM_MAX_LEVEL = negro total. No se usa el brillo real de la pantalla porque
// en el Fire TV Stick es no-op (el brillo lo manda el televisor, no Android), y libVLC 3.x no
// expone el filtro `adjust`. Cambiar la finura del paso = cambiar solo esta línea.

/** Cada cuánto y cuántas veces reintentar leer las pistas si al conectar el cast no había ninguna. */
private const val RECHEQUEO_MS = 500L
private const val RECHEQUEO_INTENTOS = 40

/**
 * Cuánto se espera, sin tocar nada, antes de confirmar una ráfaga de saltos incrementales (ver
 * `seekBy`). Corto a propósito: una pulsación suelta sigue sintiéndose inmediata y solo se fusionan
 * las ráfagas, que es donde estaba el costo — un Range request y su rebuffer por cada pulsación.
 */
private const val SEEK_INCREMENTAL_DEBOUNCE_MS = 350L

private enum class MarkingMode { INTRO, OUTRO }

/** Construye los MediaItem locales para el controller, propagando el tag de fuente/marcadores. */
private fun localMediaItems(items: List<PlayerData>): List<MediaItem> = items.map { d ->
    MediaItem.Builder()
        .setUri(d.mediaUrl)
        .setMediaId(d.episodeId)
        // La URI en localConfiguration se PIERDE al cruzar MediaController→MediaSession; la
        // guardamos también en requestMetadata (que sí sobrevive el IPC) para que
        // PlaybackService.MediaItemResolverCallback.onAddMediaItems la reconstruya en la sesión.
        // El TAG (kind/referer/etc.) se PIERDE al cruzar controller→session igual que la URI; lo
        // guardamos en extras (que SÍ sobreviven el IPC) para reconstruirlo en PlaybackService.
        .setRequestMetadata(
            MediaItem.RequestMetadata.Builder().setMediaUri(Uri.parse(d.mediaUrl))
                .setExtras(android.os.Bundle().apply {
                    putString("kind", d.kind.name)
                    d.referer?.let { putString("referer", it) }
                    d.userAgent?.let { putString("userAgent", it) }
                    d.castUrl?.let { putString("castUrl", it) }
                    d.proxyUrl?.let { putString("proxyUrl", it) }
                    d.openingStartMs?.let { putLong("openingStartMs", it) }
                    d.openingEndMs?.let { putLong("openingEndMs", it) }
                    d.endingStartMs?.let { putLong("endingStartMs", it) }
                    // Si no viaja acá, el tag reconstruido del otro lado del IPC pierde la duración
                    // sondeada y el player vuelve a quedarse sin ella (barra llena, sin seek).
                    if (d.knownDurationMs > 0) putLong("knownDurationMs", d.knownDurationMs)
                    if (d.preferirSoftware) putBoolean("preferirSoftware", true)
                    if (d.contenedorDeLaFuente.isNotEmpty()) {
                        putString("contenedorDeLaFuente", d.contenedorDeLaFuente)
                    }
                })
                .build(),
        )
        .setPlayerSourceTag(
            PlayerSourceTag(
                kind = d.kind,
                openingStartMs = d.openingStartMs,
                openingEndMs = d.openingEndMs,
                endingStartMs = d.endingStartMs,
                castUrl = d.castUrl,
                referer = d.referer,
                userAgent = d.userAgent,
                proxyUrl = d.proxyUrl,
                knownDurationMs = d.knownDurationMs,
                preferirSoftware = d.preferirSoftware,
                contenedorDeLaFuente = d.contenedorDeLaFuente,
            ),
        )
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(d.title).setArtist(d.subtitle)
                .apply { if (d.artworkUrl.isNotEmpty()) setArtworkUri(Uri.parse(d.artworkUrl)) }
                .build(),
        )
        .build()
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
    // El VlcPlayer vivo lo expone el service; se necesita para el render (VLCVideoLayout) y las
    // pistas (audio/subtítulos VLC). Al conectar el controller el service ya está creado.
    val vlc = PlaybackEngine.vlc
    if (controller == null || vlc == null) {
        Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = Color.White)
        }
        return
    }
    PlayerContent(episodeId, onBack, onOpenEpisodes, onNextEpisode, controller, vlc, isTv)
}

@OptIn(UnstableApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun PlayerContent(
    episodeId: String,
    onBack: () -> Unit,
    onOpenEpisodes: () -> Unit,
    onNextEpisode: (String) -> Unit,
    controller: MediaController,
    vlc: VlcPlayer,
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
                    graph.repository, graph.settings, graph.torrentEngine, graph.archiveCacheProxy,
                    graph.webResolverApi, graph.arkivOfflineApi, graph.playbackPreferenceStore,
                    graph.localLibrary, graph.localFileServer, graph.deviceAuth, graph.frameCapturer,
                    graph.liveController, graph.database.liveRecentDao(),
                    esTelevision = isTv,
                    httpGateway = graph.httpGateway,
                    personToken = { graph.sesionDePersona.token() },
                )
            }
        },
    )
    val playlist by vm.playlist.collectAsStateWithLifecycle()
    val generacionVivo by vm.generacionVivo.collectAsStateWithLifecycle()
    val cortesEnVivo by vlc.cortesEnVivo.collectAsStateWithLifecycle()
    val loadError by vm.error.collectAsStateWithLifecycle()
    // Progreso de la fase de pre-buffer (antes de tener playlist; solo torrent). null al terminar.
    val prepProgress by vm.prepProgress.collectAsStateWithLifecycle()
    // Fuente web: mientras el resolver de blog snifea el stream, y los subtítulos sniffeados a adjuntar.
    val resolving by vm.resolving.collectAsStateWithLifecycle()
    val webExtras by vm.webExtras.collectAsStateWithLifecycle()
    // Task 11: serie con capítulo bajado a la NUC sin preferencia todavía preguntada -> diálogo.
    val askPlaybackSource by vm.askPlaybackSource.collectAsStateWithLifecycle()
    val trivia by vm.trivia.collectAsStateWithLifecycle()
    // Adjunta como pistas externas los subtítulos que sniffeó el resolver (cuando ya hay media).
    LaunchedEffect(playlist, webExtras) {
        // Los idiomas que declara la fuente. Va PRIMERO, antes de cualquier return y antes del delay
        // de abajo: son la única forma de saber el idioma de las pistas EMBEBIDAS del MPEG-TS de magis
        // (llegan sin idioma en ningún campo) y la decisión de subtítulos corre a los 400 ms de
        // Playing, así que llegar tarde acá es no llegar. Se asigna SIEMPRE —vacío incluido— porque
        // este es el único punto que limpia lo del ítem anterior: hacerlo en VlcPlayer.loadMedia
        // competía con esta misma asignación y a veces la pisaba.
        //
        // SOLO MAGIS, y la distinción importa: ahí la lista del portal describe las pistas EMBEBIDAS
        // y el cruce por posición es legítimo. En una fuente WEB los subtítulos declarados son los
        // que se adjuntan acá abajo como pistas EXTERNAS —que ya llevan su idioma por el mapa de
        // URI—, así que cruzarlos por posición etiquetaría las pistas embebidas del video con
        // idiomas ajenos: un subtítulo francés sin etiqueta quedaría marcado "es" y se prendería
        // como si fuera español.
        vlc.idiomasSpuDeLaFuente =
            if (PlayerSource.kindFor(episodeId) == SourceKind.MAGIS) {
                webExtras?.subtitles?.map { it.lang }.orEmpty()
            } else {
                emptyList()
            }
        val extras = webExtras ?: return@LaunchedEffect
        if (playlist == null) return@LaunchedEffect
        // MAGIS NO: engancharle a su MPEG-TS un subtítulo externo le tumba TODAS las pistas al
        // demuxer de libVLC, y no es cosa del momento en que se haga — medido en device las dos
        // formas fallan. En marcha: `Vout 1` y 465 ms después `Vout 0` con `pistas=v0/a0`. Desde el
        // arranque (adjuntándolo al media): nunca llega a tener pistas, se queda en `buffering 0%`
        // y se traga 365 MB en 59 s. Sin el subtítulo, ese mismo stream arranca limpio con `v2/a3`,
        // por hardware y por software. Hasta saber por qué, magis va sin subtítulo automático.
        // Esto era veneno para magis mientras su MPEG-TS lo demuxeaba el módulo `ts` nativo: el
        // subtítulo entraba como grupo 0, libVLC cambiaba a ese el programa activo y al soltar el
        // programa 1 del TS se llevaba puestos sus tres PIDs (video 256 + audios 257/258). Quedaba
        // `pistas=v0/a0` — negro, mudo y con el reloj disparado. Medido con libVLC en -vv:
        //   input: loading spu-es slave: …srt (forced: 1)
        //   input: unselecting program id=1
        //   input: selecting program id=0
        // Fallaban las tres variantes (en marcha, al abrir el media, y con select=false) y daba
        // igual http o file://. Lo que lo resolvió fue cambiarle el demuxer a magis: ver
        // VlcPlayer.loadMedia. Sin programas no hay programa que perder.
        kotlinx.coroutines.delay(800) // dar tiempo a que VLC cargue el media antes del slave
        // byUser=false: es un adjunto automático (el resolver los sniffeó), no una elección del
        // usuario — igual que los .srt del torrent, así no le tapa la decisión de idioma al player.
        // El idioma va aparte porque estas URLs son opacas (`…/9f8a7b.vtt`): sin pasarlo, la pista
        // quedaría sin idioma y no habría forma de elegirla.
        extras.subtitles.forEach { s ->
            runCatching { vlc.addSubtitleSlave(Uri.parse(s.url), byUser = false, lang = s.lang) }
        }
    }

    // La fuente se conoce por el episodeId aunque todavía no haya playlist (para el overlay/servicio).
    val sourceIsTorrent = remember(episodeId) { PlayerSource.kindFor(episodeId) == SourceKind.TORRENT }
    // Cómo se nombra la fuente en el cartel de "Resolviendo…". `vm.resolving` lo prenden LAS DOS
    // cargas que resuelven contra la red —`loadWeb` y `loadMagis`—, pero el texto daba por sentado
    // que era web: darle play a un capítulo de Magis anunciaba una fuente web que en ese camino no
    // existe. Ninguna otra fuente prende esa bandera (archive y torrent tienen sus propios carteles).
    val fuenteQueResuelve = remember(episodeId) {
        if (PlayerSource.kindFor(episodeId) == SourceKind.MAGIS) "de Magis" else "web"
    }
    // Modo vivo (Tarea 14): aísla TODO el comportamiento distinto de VOD (sin barra de progreso ni
    // seek, overlay propio, zapping) detrás de esta bandera calculada UNA vez del episodeId con el
    // que se compuso la pantalla. Zapear cambia el canal DENTRO del playlist del ViewModel; nunca
    // navega a un episodeId nuevo (ver el LaunchedEffect(playlist) más abajo), así que esta bandera
    // no puede quedar obsoleta durante la sesión de vivo.
    val enVivo = remember(episodeId) { PlayerSource.kindFor(episodeId) == SourceKind.LIVE }

    // Índice del ítem que suena DENTRO de la playlist del ViewModel. Vive acá arriba —y no con el
    // resto del estado de transporte, más abajo— porque `episodioEnCurso` lo necesita.
    var currentIndex by remember { mutableIntStateOf(0) }

    /**
     * El capítulo que está sonando AHORA, que no siempre es el `episodeId` con el que se abrió la
     * pantalla: archive.org carga la sección entera como playlist (ver `loadArchive`, la única
     * fuente multi-ítem), así que al terminar un capítulo el player avanza al siguiente por dentro
     * —o lo hace "Saltar outro" con su `seekToNextMediaItem()`— sin navegar a una ruta nueva. El
     * argumento de navegación se queda con el capítulo viejo para siempre.
     *
     * Colgar los vecinos y el encabezado de ese argumento tenía consecuencias visibles: tras el
     * auto-avance, "Siguiente episodio" llevaba al capítulo que YA se estaba viendo, "Capítulo
     * anterior" al que acababa de terminar, el encabezado seguía nombrando al viejo y el carrusel
     * resaltaba el chip equivocado. El resto de la pantalla (guardar progreso, capturar el frame)
     * ya se identificaba así, por la playlist y no por el argumento.
     */
    val episodioEnCurso = playlist?.items?.getOrNull(currentIndex)?.episodeId ?: episodeId

    // Episodios vecinos (si los hay) para los botones "Capítulo anterior"/"Siguiente episodio" de
    // los controles. Ambos son null en películas (una sola sección, ver EpisodeNavigation) y cada
    // uno lo es en su extremo: el primero de la temporada no tiene anterior, el último no tiene
    // siguiente. Esa nulidad es la ÚNICA condición para mostrarlos (ver `showPrev`/`showNext`).
    var prevEpisodeId by remember { mutableStateOf<String?>(null) }
    var nextEpisodeId by remember { mutableStateOf<String?>(null) }
    // Título del ítem + nombre del episodio (solo series) para el encabezado del overlay de pausa.
    var headerInfo by remember { mutableStateOf<com.arkiv.player.data.ArkivRepository.PlayerHeaderInfo?>(null) }
    LaunchedEffect(episodioEnCurso) {
        prevEpisodeId = graph.repository.previousEpisode(episodioEnCurso)?.id
        nextEpisodeId = graph.repository.nextEpisode(episodioEnCurso)?.id
        headerInfo = graph.repository.headerInfo(episodioEnCurso)
    }

    // Foco D-pad (TV) de los controles del overlay de pausa: los once puntos de aterrizaje viven
    // juntos en `PlayerFoco.kt`, ver su KDoc.
    val focos = rememberFocosDelOverlay()
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

    var isBuffering by remember { mutableStateOf(true) }
    var positionMs by remember { mutableLongStateOf(0L) }

    // --- Datos curiosos ---
    // El índice sale de la posición, no de un temporizador: adelantar o retroceder mueve el dato
    // igual que mueve el video, y no hay un reloj propio que se desincronice al pausar.
    val indiceTrivia = TriviaDelPlayer.indiceEn(positionMs, trivia.size)
    var triviaAbierta by remember { mutableStateOf(false) }
    // El aviso ("!") es un overlay PROPIO, como el de en vivo: los controles arrancan ocultos y se
    // auto-ocultan, así que un aviso colgado de la barra no lo vería nadie.
    var avisoTriviaVisible by remember { mutableStateOf(false) }
    var indiceAnunciado by remember { mutableIntStateOf(-1) }
    if (indiceTrivia >= 0 && indiceTrivia != indiceAnunciado) {
        indiceAnunciado = indiceTrivia
        avisoTriviaVisible = true
    }
    // Se va solo a los 5 s. Se relanza con cada dato nuevo, igual que el overlay de canal.
    LaunchedEffect(indiceAnunciado, avisoTriviaVisible) {
        if (!avisoTriviaVisible) return@LaunchedEffect
        delay(5000)
        avisoTriviaVisible = false
    }
    var durationMs by remember { mutableLongStateOf(0L) }
    var isPlaying by remember { mutableStateOf(false) }
    /**
     * La INTENCIÓN de reproducir (`playWhenReady`), que no es lo mismo que `isPlaying`: isPlaying
     * también se cae en cada rebuffer. Se sigue aparte porque es lo que distingue "el usuario
     * pausó" de "el torrent se quedó sin datos un segundo" (ver el efecto de captura al pausar).
     */
    var quiereReproducir by remember { mutableStateOf(false) }
    var loaded by remember { mutableStateOf(false) }
    // Episodio que esta pantalla ya mandó al receptor. Coordina los dos caminos que castean (la
    // carga de playlist y el salto local→cast de LaunchedEffect(casting)): si el usuario conecta
    // justo en el frame en que llega la playlist, ambos efectos corren y sin esto el receptor
    // recargaría dos veces lo mismo. Se limpia al desconectar.
    var casteadoAlReceptor by remember { mutableStateOf<String?>(null) }

    // El player que estamos manejando ahora mismo: el del Chromecast mientras haya sesión, el
    // local si no. Ambos implementan Player, así que los controles no necesitan saber cuál es.
    // El `?: controller` cubre el caso sin Google Play Services (castContext y castPlayer nulos).
    val activePlayer: Player = if (casting) castPlayer ?: controller else controller

    /**
     * Posición y duración DEL CONTENIDO, que casteando no son las que reporta el receptor.
     *
     * Con el audio transcodificado el stream ya arranca en el punto pedido, así que el receptor
     * cuenta desde cero, y al salir en vivo manda `TIME_UNSET` como duración. Leerlo crudo deja la
     * barra vacía y hace que el local reanude en el lugar equivocado al desconectar. La traducción
     * vive en CastProgress (con tests) para que no haya dos copias divergiendo.
     */
    fun contentPositionMs(): Long = CastProgress.contentPosition(
        receiverPosMs = activePlayer.currentPosition,
        baseOffsetMs = if (casting) graph.castSession?.baseOffsetMs ?: 0L else 0L,
    )

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
        loaded || runCatching { controller.currentMediaItem?.mediaId }.getOrNull() == episodeId

    fun contentDurationMs(): Long = CastProgress.contentDuration(
        receiverDurMs = activePlayer.duration,
        knownDurationMs = if (casting) graph.castSession?.knownDurationMs ?: 0L else 0L,
    )

    // Controles custom (estilo torrent): visibles al tocar, se auto-ocultan mientras reproduce.
    // Arranca OCULTO: al abrir se ve el spinner de carga y luego el video limpio, sin el overlay de
    // pausa/barra encima. El usuario toca la pantalla para mostrar los controles.
    var controlsVisible by remember { mutableStateOf(false) }
    var interactionTick by remember { mutableIntStateOf(0) }

    // Modo vivo (Tarea 14): overlay PROPIO, no reusa controlsVisible/interactionTick -- esos
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

    // Marcadores intro/outro (solo archive).
    var markingMode by remember { mutableStateOf<MarkingMode?>(null) }
    var markersMenu by remember { mutableStateOf(false) }

    // Selector de audio/subtítulos (ambas fuentes, vía la API VLC del player vivo). Todo el bloque
    // vive en `PlayerPistas.kt`; de acá solo se consulta `haySubtitulo`, para el ícono de CC.
    val estadoPistas = rememberEstadoDePistas(vlc, graph, context)

    // Estado de descarga (overlay solo para torrent).
    var progress by remember { mutableStateOf<TorrentProgress?>(null) }
    // Fracción [0..1] ya descargada/buffereada por delante (para el tramo gris claro de la barra).
    // Torrent: % de descarga del engine; archive: % del archivo cacheado por el proxy.
    var bufferedFraction by remember { mutableFloatStateOf(0f) }

    // Modo noche: nivel del velo negro sobre el video, 0..DIM_MAX_LEVEL. Persistido en
    // SettingsStore (sobrevive a cerrar la app). Se acota al leerlo por si quedó un valor viejo
    // fuera de rango guardado.
    val dimLevel by graph.settings.dimLevel.collectAsStateWithLifecycle()
    val dimNivel = dimLevel.coerceIn(0, DIM_MAX_LEVEL)
    // Moverse por la barra -- arrastre del slider y saltos incrementales -- vive en
    // `PlayerSeek.kt`. Quien dispara el seek de verdad se queda aca: depende del player activo
    // y de si el cast esta transcodificando.
    val seek = rememberEstadoDeSeek()

    // Ref al layout de video (para devolverle el foco en TV al cerrar un diálogo).
    var videoView by remember { mutableStateOf<VLCVideoLayout?>(null) }

    /**
     * El TextureView donde se está pintando el video, para las capturas de frame.
     *
     * Primero se le pregunta al player y recién después se cae al layout de ESTA pantalla: al
     * salir, el `onRelease` del AndroidView le suelta el layout al player (`detachVideo`, que lo
     * pone en null para no retener la Activity) y no hay garantía de que corra después del
     * `onDispose` que captura el frame de salida. El layout sigue vivo acá, así que el respaldo es
     * lo que hace que salir del reproductor capture de verdad.
     */
    fun textureViewDelVideo(): android.view.TextureView? =
        vlc.textureViewActual() ?: vlc.textureViewDe(videoView)

    // Re-enganchar el video al volver de otra app: al irse al fondo Android destruye la Surface y
    // libVLC tumba su salida de video (evento `Vout 0`); sin un attachViews nuevo la salida no se
    // reconstruye y queda la pantalla NEGRA con el audio sonando. Ver VideoAttachPolicy.
    // VLC reconstruye el vout recién en el siguiente keyframe (segundos en HLS): sin avisar, ese rato
    // se ve un negro que parece un cuelgue. Solo aplica si ANTES había video, para no dejar el spinner
    // colgado en contenido de solo audio (que nunca tiene vout).
    var esperandoVideo by remember { mutableStateOf(false) }

    // Distinto de [esperandoVideo], que es "HABÍA imagen y se perdió al volver del fondo". Esto es
    // "todavía no hubo ninguna": el arranque negro con sonido. Lo decide VlcPlayer, que es quien
    // sabe de pistas y de vout; acá solo se sondea. Ver VlcPlayer.esperandoPrimeraImagen.
    var sinPrimeraImagen by remember { mutableStateOf(false) }

    // Identidad de ESTA composición del reproductor. Al recrearse la pantalla (volver del segundo
    // plano, navegación) llegan a convivir dos, cada una con su layout y su observador de ciclo de
    // vida; sin poder nombrarlas, en el log se ven como la misma y no hay manera de saber cuál
    // engancha el video y cuál lo suelta.
    val pantallaId = remember { PANTALLA_SEQ.incrementAndGet() }
    DisposableEffect(Unit) {
        android.util.Log.w("ArkivVout", "PANTALLA #$pantallaId entra")
        onDispose { android.util.Log.w("ArkivVout", "PANTALLA #$pantallaId sale (dispose)") }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, vlc) {
        var habiaVideo = false
        val policy = VideoAttachPolicy(
            attach = {
                val v = videoView
                if (v == null) {
                    android.util.Log.w("ArkivVout", "ON_START #$pantallaId PERO videoView=null → no engancha nada")
                } else {
                    vlc.attachVideo(v, "ON_START#$pantallaId")
                }
                esperandoVideo = habiaVideo
            },
            detach = {
                habiaVideo = vlc.hasVideoOutput()
                vlc.detachVideo("ON_STOP#$pantallaId")
            },
        )
        // Los eventos crudos se loguean aparte de lo que decide la política: la política ignora a
        // propósito el primer ON_START (ver VideoAttachPolicy), así que "llegó el evento" y "hubo
        // reenganche" son dos hechos distintos y hay que poder verlos por separado.
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> {
                    android.util.Log.w("ArkivVout", "CICLO #$pantallaId ON_START (dueño=${lifecycleOwner.hashCode()})")
                    policy.onStart()
                }
                Lifecycle.Event.ON_STOP -> {
                    android.util.Log.w("ArkivVout", "CICLO #$pantallaId ON_STOP (dueño=${lifecycleOwner.hashCode()})")
                    policy.onStop()
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            android.util.Log.w("ArkivVout", "CICLO #$pantallaId observador removido")
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    // Sondea hasta que VLC vuelva a pintar. El timeout es un seguro: si el vout no vuelve (fuente sin
    // video, error), el spinner se quita igual en vez de quedarse colgado para siempre.
    LaunchedEffect(esperandoVideo) {
        if (!esperandoVideo) return@LaunchedEffect
        withTimeoutOrNull(15_000) {
            while (!vlc.hasVideoOutput()) delay(150)
        }
        esperandoVideo = false
    }

    // Estado DLNA: vive entero en `PlayerDlna.kt` (estado, acciones y sus tres piezas de UI). De
    // todo eso, esta pantalla solo consulta `activo`, porque tener un renderer andando esconde los
    // controles locales.
    val estadoDlna = rememberEstadoDlna(dlna)

    val d = playlist?.items?.getOrNull(currentIndex)
    val isTorrent = d?.kind == SourceKind.TORRENT
    // Task 11: solo tiene sentido ofrecer "volver a en vivo" cuando lo que suena vino de la NUC —
    // no hay a qué otra fuente "volver" desde WEB/ARCHIVE/TORRENT en esta iteración.
    val showLiveOverride = d?.kind == SourceKind.NUC
    val playlistRef = rememberUpdatedState(playlist)

    /**
     * Arma lo que hay que mandarle al receptor para el ítem [idx] de [pl], arrancando en
     * [startPositionMs]. Un solo lugar a propósito: lo usan los DOS caminos que castean —abrir un
     * capítulo estando ya casteando, y conectar el Chromecast con el capítulo ya sonando en el
     * celu—. Si divergieran, lo que llega a la TV dependería de por dónde entraste.
     */
    fun castRequestFor(pl: PlaylistData, idx: Int, startPositionMs: Long): com.arkiv.player.cast.CastRequest? {
        val item = pl.items.getOrNull(idx) ?: return null
        val esVivo = item.kind == SourceKind.LIVE
        // Qué audio lleva esto y si el receptor puede con él. Se lee del player LOCAL, que es el que
        // ya parseó el archivo. Un "no lo decodifica" acá explica el video mudo que antes no dejaba
        // ni un rastro: AC-3 (Avatar) y DTS (Naruto), los dos con H.264, por eso se veía la imagen.
        // Vivo (Tarea 18) usa EXACTAMENTE el mismo portero: se lee el audio que YA está sonando en
        // el celu -el canal está reproduciéndose cuando se llega hasta acá, nunca antes- así que no
        // hace falta ninguna lista de canales permitidos ni adivinar por nombre/categoría.
        val audio = vlc.currentAudioFormat()
        val decodable = com.arkiv.player.cast.CastAudioSupport.receiverDecodes(
            fourcc = audio?.fourcc ?: 0,
            channels = audio?.channels ?: 0,
        )
        android.util.Log.i(
            "ArkivCast",
            "audio del origen · codec=${com.arkiv.player.cast.CastAudioSupport.fourccToString(audio?.fourcc ?: 0)} " +
                "canales=${audio?.channels ?: 0} → ${if (decodable) "va directo" else "hay que transcodificar"}",
        )

        // La URL alcanzable por el receptor: la del server HTTP propio, LAN, sea el del torrent o
        // la del proxy de vivo (LiveHlsProxy) -- mismo motivo en los dos casos, ver el KDoc de
        // CastRequestBuilder. `lanIp()` es un helper genérico de TorrentEngine (IP del celu en la
        // LAN), no algo específico de torrent -- el resto de este método ya lo usa así más abajo.
        val lanUrl = when (item.kind) {
            SourceKind.TORRENT -> graph.torrentEngine.lanStreamUrl()
            SourceKind.LIVE -> graph.torrentEngine.lanIp()?.let { graph.liveHlsProxy.lanUrl(it) }
            else -> null
        }

        val directo = com.arkiv.player.cast.CastRequestBuilder.build(
            episodeId = item.episodeId,
            title = item.title,
            subtitle = item.subtitle,
            artworkUrl = item.artworkUrl,
            mediaUrl = item.mediaUrl,
            castUrl = item.castUrl,
            isTorrent = item.kind == SourceKind.TORRENT,
            lanUrl = lanUrl,
            lanMime = graph.torrentEngine.streamMime(),
            startPositionMs = startPositionMs,
            isLive = esVivo,
        )
        if (decodable || directo == null) {
            // Puede venir de un capítulo que sí lo necesitaba: soltar el puerto y la CPU.
            graph.castTranscoder.stop()
            return directo
        }

        // Hay que convertirle el audio. El origen es el loopback cuando hay un servidor propio
        // (torrent, o el proxy de vivo) -no sale a la red- y la misma URL que se hubiera casteado
        // en el resto de los casos.
        val origen = when (item.kind) {
            SourceKind.TORRENT -> graph.torrentEngine.localStreamUrl() ?: directo.uri
            SourceKind.LIVE -> item.mediaUrl // http://127.0.0.1:.../live.m3u8, ver abrirCanalActual
            else -> directo.uri
        }
        val lanIp = graph.torrentEngine.lanIp()
        // Vivo no tiene "dónde ibas": start-time busca una posición DENTRO del archivo, y un HLS en
        // vivo no tiene ese eje (ver KDoc de CastSoutChain.mediaOptions) -- forzar 0 sea cual sea la
        // posición local (el tiempo que lleva ABIERTO el canal, no un punto para retomar).
        val arranqueMs = if (esVivo) 0L else startPositionMs
        val transcodificada = lanIp?.let {
            graph.castTranscoder.start(
                sourceUrl = origen,
                lanIp = it,
                startAtMs = arranqueMs,
                audioTrackIndex = audio?.index,
            )
        }
        if (transcodificada == null) {
            // Sin IP en la LAN o sin poder arrancar: mejor mandar el original (se verá mudo, como
            // antes) que no mandar nada, pero que quede dicho por qué.
            android.util.Log.w("ArkivCast", "no se pudo transcodificar (lanIp=$lanIp): va el original y probablemente no suene")
            return directo
        }
        // El stream ya arranca en el punto pedido, así que para el receptor empieza en cero; el
        // desfase real lo guarda el transcodificador en baseOffsetMs.
        return directo.copy(
            uri = transcodificada,
            mimeType = com.arkiv.player.cast.CastSoutChain.MIME,
            startPositionMs = 0,
            baseOffsetMs = arranqueMs,
            // El receptor no puede saber la duración de un stream en vivo, pero el celu sí: sin
            // esto, castear transcodificado no guardaría progreso nunca. Un canal en vivo no tiene
            // duración NUNCA (ni local ni remota): 0 ("no sé"), sin ir a preguntarle al controller.
            // `coerceAtLeast(0)` no es cosmético: media3 devuelve C.TIME_UNSET (muy negativo) cuando
            // no la sabe, y eso hay que traducirlo a "no sé" (0), no dejarlo pasar como duración.
            knownDurationMs = if (esVivo) 0L else runCatching { controller.duration }.getOrDefault(0L).coerceAtLeast(0L)
                .also { android.util.Log.i("ArkivCast", "duración local para el cast: ${it}ms (cruda=${runCatching { controller.duration }.getOrDefault(0L)})") },
        )
    }

    /**
     * Reevaluar el códec si al conectar todavía no se conocía.
     *
     * El portero decide con las pistas que el player local ya parseó; si conectás apenas se abre el
     * video, no hay ninguna, y la decisión conservadora ("no sé → mandalo directo", que es lo que
     * protege a archive.org) manda el original sin transcodificar. En AC-3 eso es justo el fallo que
     * vinimos a eliminar: se ve y no suena. Medido en device: `codec=desconocido → va directo`, y
     * recién 29 s más tarde se corrigió de pura casualidad.
     *
     * Vivo (Tarea 18) agrega `liveCanal?.code` a la clave: `currentIndex` NUNCA cambia en vivo (el
     * playlist siempre tiene un solo ítem en el índice 0, zapees lo que zapees), así que sin esto
     * el recheque solo correría una vez -tras conectar el Chromecast- y nunca volvería a correr en
     * los zaps siguientes. La primera lectura de audio al zapear con el Chromecast ya conectado es
     * necesariamente la del canal ANTERIOR (setMediaItems() del canal nuevo recién dispara después,
     * ver LaunchedEffect(playlist)); este recheque es lo que corrige esa foto vieja apenas VLC
     * parsea las pistas del canal nuevo.
     */
    LaunchedEffect(casting, currentIndex, liveCanal?.code) {
        if (!casting) return@LaunchedEffect
        repeat(RECHEQUEO_INTENTOS) {
            delay(RECHEQUEO_MS)
            // El chequeo va ACÁ DENTRO, no solo al entrar: cuando el efecto arranca (al volverse
            // true `casting`) el transcodificador todavía no se levantó, así que mirarlo una sola vez
            // daba siempre null. Medido en device: recasteaba aunque la primera decisión ya hubiera
            // sido la correcta, o sea DOS transcodes por casteo, y el segundo obligaba al receptor a
            // buffear de nuevo.
            if (graph.castTranscoder.activeUrl != null) return@LaunchedEffect
            val audio = vlc.currentAudioFormat() ?: return@repeat
            if (com.arkiv.player.cast.CastAudioSupport.receiverDecodes(audio.fourcc, audio.channels)) {
                return@LaunchedEffect // el camino directo era el correcto
            }
            val pl = playlistRef.value ?: return@LaunchedEffect
            android.util.Log.w(
                "ArkivCast",
                "las pistas aparecieron tarde (codec=${com.arkiv.player.cast.CastAudioSupport.fourccToString(audio.fourcc)}): " +
                    "recasteo transcodificando",
            )
            castRequestFor(pl, currentIndex, contentPositionMs())?.let { graph.castSession?.setMedia(it) }
            return@LaunchedEffect
        }
    }

    fun bump() { controlsVisible = true; interactionTick++ }

    // Velocidad, zoom, modo noche y el HUD central: todo en `PlayerGestos.kt`. El `bump()` que
    // recibe es lo único que los ata a esta pantalla — cada ajuste cuenta como actividad y
    // reinicia el auto-ocultado de los controles. Va acá abajo, y no con el resto del estado,
    // porque necesita que `bump` ya esté declarado.
    val gestos = rememberEstadoDeGestos(vlc, graph.settings) { bump() }
    EfectoDelHudDeBrillo(gestos)


    // El corte de un directo, por el contador de [VlcPlayer.cortesEnVivo] y NO por STATE_ENDED:
    // ese estado viaja por el MediaController y se pierde cuando VLC manda Stopped a los pocos ms
    // de EndReached -- medido el 2026-08-14, dos de cinco cortes no llegaron y el canal quedó
    // pausado sin que la reapertura disparara. Un contador que solo sube no se puede perder.
    var cortesAtendidos by remember(episodeId) { mutableStateOf(-1) }
    LaunchedEffect(cortesEnVivo, enVivo) {
        if (!enVivo) return@LaunchedEffect
        // La primera lectura solo toma nota: el contador es del reproductor, que sobrevive a esta
        // pantalla, así que al entrar ya puede venir con cortes de un canal anterior.
        if (cortesAtendidos < 0) { cortesAtendidos = cortesEnVivo; return@LaunchedEffect }
        if (cortesEnVivo > cortesAtendidos) {
            cortesAtendidos = cortesEnVivo
            vm.reabrirVivoPorCorte()
        }
    }


    LaunchedEffect(controlsVisible, isBuffering, casting, estadoDlna.activo, markingMode, loadError) {
        android.util.Log.i(
            "ArkivCast",
            "UI barra · controlsVisible=$controlsVisible isBuffering=$isBuffering casting=$casting " +
                "dlna=${estadoDlna.activo != null} marcando=${markingMode != null} error=${loadError != null} " +
                "→ overlay=${controlsVisible && loadError == null && estadoDlna.activo == null && markingMode == null}",
        )
    }

    // El SPINNER DE CARGA, que es otra cosa que el overlay de controles de arriba (ese log dice
    // `overlay=` y es la barra de transporte; confundirlos cuesta una ronda de medición).
    //
    // Se loguea aparte porque el fallo que interesa es invisible desde afuera: el arranque negro con
    // sonido es exactamente el instante en que `isBuffering` ya es false y todavía no hay imagen, o
    // sea que ninguna de las señales viejas lo delata. Con `sinImagen` se ve si el spinner tapó ese
    // hueco o si la pantalla se quedó en negro.
    LaunchedEffect(playlist == null, isBuffering, sinPrimeraImagen, esperandoVideo, casting) {
        android.util.Log.w(
            "ArkivVlc",
            "spinner=${playlist == null || isBuffering || sinPrimeraImagen || (esperandoVideo && !casting)} " +
                "· sinPlaylist=${playlist == null} buffering=$isBuffering sinImagen=$sinPrimeraImagen " +
                "perdioVideo=$esperandoVideo",
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
        android.util.Log.w("ArkivPlay", "PlayerScreen enter episodeId=$episodeId kind=$kind yaEnController=$yaCargado loaded=$loaded loadedIds=$loadedIds")
        // WEB: el stream resuelto es EFÍMERO (el token del proxy/host expira y cambia en cada resolve),
        // así que reproducir el mismo episodio web = re-resolver + recargar SIEMPRE, aunque el item viejo
        // siga en el controller. Para torrent/archive la URL es estable → conservar el reuso de buffer.
        if (!yaCargado || kind == SourceKind.WEB) {
            // stop() corta el video viejo; el setMediaItems de abajo reemplaza la playlist cuando la
            // fuente nueva termina de resolver.
            android.util.Log.w("ArkivPlay", "stop() + loaded=false (${if (kind == SourceKind.WEB) "WEB efímero" else "episodeId nuevo"})")
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
        val pl = playlist ?: run { android.util.Log.w("ArkivPlay", "playlist=null (aún resolviendo o descartada)"); return@LaunchedEffect }
        if (enVivo) {
            // Vivo (Tarea 14): SIEMPRE reemplaza el media -- no hay "mismo episodio" que reusar,
            // cada zap es un canal distinto -- sin recrear el reproductor: el controller/vlc siguen
            // siendo los mismos de siempre (los del PlaybackService), solo se les cambia el ítem.
            // No pasa por MediaReusePolicy (pensada para reusar buffer entre capítulos de la MISMA
            // serie/torrent, un concepto que en vivo no existe).
            android.util.Log.w("ArkivPlay", "playlist lista (vivo) → setMediaItems (${pl.items.firstOrNull()?.episodeId})")
            loaded = true
            currentIndex = 0
            positionMs = 0L
            durationMs = 0L
            val epId = pl.items.firstOrNull()?.episodeId
            // Tarea 18: con el Chromecast YA conectado, cada zap tiene que empujarle el canal nuevo
            // al receptor -- si no, la TV se queda pegada mirando el canal viejo mientras el celu ya
            // cambió. Mismo patrón que el salto de capítulo de VOD más abajo (rama `casting &&
            // castSession != null`), solo que sin startPositionMs: un directo no tiene "dónde ibas".
            if (casting && castSession != null) {
                val req = castRequestFor(pl, 0, 0L)
                if (req != null) {
                    controller.setMediaItems(localMediaItems(pl.items), 0, 0L)
                    // El local NO arranca: mientras el Chromecast reproduce el canal, competir por
                    // el mismo stream en el celu es puro gasto de batería/red (mismo criterio que
                    // el salto de capítulo de VOD).
                    controller.playWhenReady = false
                    castSession.setMedia(req)
                    casteadoAlReceptor = epId
                    NowPlaying.episodeId = epId
                    return@LaunchedEffect
                }
                android.util.Log.w("ArkivCast", "zap con Chromecast conectado: sin URL que el receptor pueda alcanzar → se reproduce en el celu")
                android.widget.Toast.makeText(
                    context,
                    "No se pudo castear: la TV no puede alcanzar este stream (revisa el WiFi)",
                    android.widget.Toast.LENGTH_SHORT,
                ).show()
            }
            controller.setMediaItems(localMediaItems(pl.items), 0, 0L)
            controller.playWhenReady = true
            controller.prepare()
            NowPlaying.episodeId = epId
            return@LaunchedEffect
        }
        // WEB: URL efímera (token que expira en cada resolve) → NUNCA reusar el media viejo; siempre
        // recargar con la URL fresca. El guard "una sola vez" y el reuso de buffer (play()/seekTo) solo
        // valen para fuentes de URL estable (archive/torrent).
        val isWeb = pl.items.getOrNull(pl.startIndex)?.kind == SourceKind.WEB
        // ¿Esto es lo que pidió ESTA pantalla, o todavía es la playlist del capítulo anterior? El
        // ViewModel sobrevive a la navegación entre capítulos, así que al entrar al siguiente lo
        // publicado sigue siendo lo de antes durante todo el resolve (~4 s en magis). Se pregunta
        // ANTES de tocar `loaded`, la posición o el cast: darla por buena era reproducir el capítulo
        // anterior desde el principio y —peor— dejar `loaded=true`, con lo que la playlist buena ya
        // no entraba nunca. Ver el KDoc de PlaylistData.pedido y MediaReusePolicy.decide.
        val decision = MediaReusePolicy.decide(
            episodeId = episodeId,
            // Qué hay cargado, con su URI. La URI se lee de requestMetadata y NO de localConfiguration:
            // este lado es el controller, y localConfiguration se pierde al cruzar el IPC (ver
            // PlaybackService.MediaItemResolverCallback). Sin la URI, "es el mismo episodio" era la única
            // señal para reusar — y para torrent eso es falso: el puerto del servidor local cambia.
            cargado = (0 until controller.mediaItemCount).map { i ->
                val mi = controller.getMediaItemAt(i)
                LoadedMedia(mi.mediaId, mi.requestMetadata.mediaUri?.toString().orEmpty())
            },
            actualMediaId = controller.currentMediaItem?.mediaId,
            fresco = pl.items.map { LoadedMedia(it.episodeId, it.mediaUrl) },
            isWeb = isWeb,
            pedido = pl.pedido,
            // ¿Volvimos sobre una pantalla NUEVA? Reusar el media con una superficie nueva mata al
            // decodificador (ver MediaReusePolicy.decide para los números medidos).
            pantallaNueva = vlc.superficieDistintaALaDelVideo(),
        )
        if (decision == MediaReusePolicy.Decision.ESPERAR) {
            android.util.Log.w("ArkivPlay", "playlist de OTRO capítulo (pedido=${pl.pedido} ≠ $episodeId) → esperar la mía")
            return@LaunchedEffect
        }
        if (loaded && !isWeb) {
            android.util.Log.w("ArkivPlay", "playlist lista pero loaded=true (no-WEB) → NO recarga (guard). items=${pl.items.map { it.episodeId }}")
            return@LaunchedEffect
        }
        loaded = true
        positionMs = pl.startPositionMs
        android.util.Log.w("ArkivPlay", "playlist lista → cargar. isWeb=$isWeb decision=$decision startPos=${pl.startPositionMs}")
        if (casting && castSession != null) {
            val idx = pl.items.indexOfFirst { it.episodeId == episodeId }.coerceAtLeast(0)
            val req = castRequestFor(pl, idx, pl.startPositionMs)
            if (req != null) {
                // La pantalla avanzó a este episodio: NowPlaying es lo que lee la notificación y el
                // remoto entre dispositivos, no bookkeeping local — no puede quedar apuntando al
                // capítulo anterior.
                NowPlaying.episodeId = episodeId
                android.util.Log.w("ArkivPlay", "rama=CAST → el capítulo va al Chromecast, el local queda cebado en pausa")
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
            android.util.Log.w("ArkivCast", "casteando pero no hay URL que mandarle al receptor → se reproduce en el celu")
            android.widget.Toast.makeText(
                context,
                "No se pudo castear: la TV no puede alcanzar este stream (revisa el WiFi)",
                android.widget.Toast.LENGTH_SHORT,
            ).show()
        }
        when (decision) {
            // Inalcanzable: se corta arriba, apenas se calcula la decisión. La rama existe porque el
            // `when` sobre Decision es exhaustivo.
            MediaReusePolicy.Decision.ESPERAR -> Unit
            // Mismo episodio ya en curso Y con la misma URL: re-enganchar (aprovecha el buffer). Solo no-WEB.
            MediaReusePolicy.Decision.REUSAR_ACTUAL -> {
                android.util.Log.w("ArkivPlay", "rama=REUSAR_ACTUAL → controller.play() (NO recarga media)")
                currentIndex = controller.currentMediaItemIndex
                // El controller puede llegar acá cebado-pero-no-preparado: la rama CAST de arriba lo
                // carga con setMediaItems() sin prepare(), y si el cast se desconectó ESTANDO AFUERA del
                // reproductor (botón de cast / notificación) la pantalla se destruyó de por medio — el
                // prepare() de LaunchedEffect(casting) nunca llegó a correr porque casteabaAntes es un
                // `remember` de esa composición, no del player. play() sobre un player IDLE no arranca
                // nada; prepararlo primero es inofensivo si ya estaba preparado.
                if (controller.playbackState == Player.STATE_IDLE) controller.prepare()
                controller.play()
            }
            // Misma sección ya cargada (mismas URLs), otro episodio: saltar dentro de la playlist. Solo no-WEB.
            MediaReusePolicy.Decision.SALTAR_EN_PLAYLIST -> {
                android.util.Log.w("ArkivPlay", "rama=SALTAR_EN_PLAYLIST → seekTo dentro de la playlist (NO recarga media)")
                val idx = pl.items.indexOfFirst { it.episodeId == episodeId }.coerceAtLeast(0)
                currentIndex = idx
                controller.seekTo(idx, pl.startPositionMs)
                // Mismo caso que REUSAR_ACTUAL de arriba: puede llegar cebado-pero-no-preparado.
                if (controller.playbackState == Player.STATE_IDLE) controller.prepare()
                controller.playWhenReady = true
            }
            // Contenido nuevo, WEB re-entrante, o la URL cambió bajo el mismo episodeId (torrent
            // re-servido en otro puerto): cargar la playlist con la URL fresca.
            MediaReusePolicy.Decision.RECARGAR -> {
                // WEB re-entrante: el item viejo (token muerto) puede seguir en el controller con el mismo
                // mediaId → cortarlo antes de setMediaItems para que VlcPlayer cargue la URL nueva.
                if (isWeb && controller.mediaItemCount > 0) {
                    android.util.Log.w("ArkivPlay", "WEB re-entrante → stop() del item viejo antes de recargar")
                    controller.stop()
                }
                android.util.Log.w("ArkivPlay", "rama=nuevo → setMediaItems + prepare (abre VLC con la URL fresca)")
                currentIndex = pl.startIndex
                controller.setMediaItems(localMediaItems(pl.items), pl.startIndex, pl.startPositionMs)
                controller.playWhenReady = true
                controller.prepare()
            }
        }
        NowPlaying.episodeId =
            controller.currentMediaItem?.mediaId ?: pl.items.getOrNull(currentIndex)?.episodeId
    }

    // Capítulo cuyo final YA se atendió, para no encadenar dos avances por el mismo final: VLC puede
    // repetir el EndReached y, casteando, el CastPlayer emite además el suyo.
    var finAtendido by remember { mutableStateOf<String?>(null) }

    /**
     * Fin del capítulo → seguir con el siguiente.
     *
     * Hasta ahora esto no existía y solo avanzaba archive, de rebote: es la única fuente multi-ítem
     * (carga la sección entera como playlist, ver `loadArchive`), así que el avance lo hacía media3
     * solo, por dentro. Las demás —magis, web, torrent, local— publican UN ítem: al terminar, el
     * player se quedaba en STATE_ENDED con la barra llena y no pasaba nada más.
     *
     * Va por el mismo camino que el botón "Siguiente episodio" del transporte ([onNextEpisode]):
     * navegar a la ruta del capítulo nuevo, que es lo que re-arranca la resolución de la fuente.
     */
    fun alTerminarElCapitulo() {
        // Un directo no termina: su EndReached es el stream que se cortó, y ahí no hay "siguiente
        // capítulo" que valga (el único siguiente del modo vivo es el zapping). Reabrirlo NO se
        // engancha acá: este camino depende de que el STATE_ENDED cruce el MediaController, y se
        // pierde cuando VLC manda Stopped a los pocos ms (ver [VlcPlayer.cortesEnVivo], que es de
        // donde sale la señal buena).
        if (enVivo) return
        val actual = playlistRef.value?.items?.getOrNull(controller.currentMediaItemIndex)?.episodeId
            ?: episodeId
        if (finAtendido == actual) return
        // Un stream cortado avisa igual que un capítulo terminado: ver AutoAvance.
        if (!AutoAvance.esFinDeCapitulo(positionMs, durationMs)) {
            android.util.Log.w("ArkivPlay", "fin en pos=$positionMs de $durationMs → no es el final, no avanza")
            return
        }
        finAtendido = actual
        val siguiente = nextEpisodeId
        android.util.Log.w("ArkivPlay", "fin de $actual → siguiente=$siguiente")
        if (siguiente != null) onNextEpisode(siguiente)
    }

    // Índice/buffering/estado del transporte. Sigue al player activo: al conectar o desconectar
    // el cast, el efecto se relanza solo y el listener se re-engancha al que corresponda.
    DisposableEffect(activePlayer) {
        isBuffering = activePlayer.playbackState == Player.STATE_BUFFERING
        isPlaying = activePlayer.isPlaying
        quiereReproducir = activePlayer.playWhenReady
        if (activePlayer.playbackState == Player.STATE_READY && posicionEsDeEstaPantalla()) {
            positionMs = contentPositionMs()
            contentDurationMs().let { if (it > 0) durationMs = it }
        }
        currentIndex = controller.currentMediaItemIndex.coerceAtLeast(0)
        val listener = object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                // La IDENTIDAD de lo que suena la manda siempre la playlist local: el CastPlayer
                // tiene un solo ítem cargado y su índice sería siempre 0.
                currentIndex = controller.currentMediaItemIndex
                NowPlaying.episodeId =
                    playlistRef.value?.items?.getOrNull(controller.currentMediaItemIndex)?.episodeId
            }

            override fun onPlaybackStateChanged(state: Int) {
                isBuffering = state == Player.STATE_BUFFERING
                if (state == Player.STATE_ENDED) alTerminarElCapitulo()
            }

            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
            }

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                quiereReproducir = playWhenReady
            }

            // Sin esto, un fallo de reproducción no llegaba a NINGUNA parte: VlcPlayer lo publicaba
            // como PlaybackException, pero la pantalla solo pinta `vm.error` —los errores de
            // resolución— así que la película no arrancaba y no aparecía ningún mensaje. Medido el
            // 2026-08-10 en el Fire TV: `EncounteredError` en el log y `error=false` en la UI.
            // El ViewModel decide qué hacer con esto: hay fallos que se reparan solos (el 404 de un
            // archivo renombrado en archive.org) y otros que solo se pueden contar.
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                val id = playlistRef.value?.items
                    ?.getOrNull(controller.currentMediaItemIndex)?.episodeId ?: episodeId
                android.util.Log.w("ArkivPlay", "onPlayerError episodeId=$id → ${error.message}")
                vm.onPlaybackFailed(id)
            }
        }
        activePlayer.addListener(listener)
        onDispose { activePlayer.removeListener(listener) }
    }

    // Sondeo: posición/duración (0,5 s), estado de descarga (torrent) y progreso persistido (5 s).
    // Clave = activePlayer: al conectar/desconectar el cast hay que volver a sondear al que suena.
    LaunchedEffect(activePlayer) {
        var tick = 0
        while (true) {
            delay(500)
            if (isTorrent) progress = graph.torrentEngine.streamStatus()
            // Fracción buffereada por delante para la barra: torrent = % de descarga; archive = % cacheado.
            // Casteando no aplica: lo que bufferea es el receptor, no nosotros — mostrar el buffer
            // local sería una barra que miente.
            bufferedFraction = when {
                casting -> 0f
                isTorrent -> progress?.progress ?: 0f
                else -> {
                    val url = playlistRef.value?.items?.getOrNull(controller.currentMediaItemIndex)?.mediaUrl
                    if (url != null) graph.archiveCacheProxy.bufferedFraction(url) else 0f
                }
            }
            val ready = activePlayer.playbackState == Player.STATE_READY && posicionEsDeEstaPantalla()
            if (ready) {
                positionMs = contentPositionMs()
                contentDurationMs().let { if (it > 0) durationMs = it }
                // El presupuesto de reaperturas se repone con la POSICIÓN, no con `isPlaying`.
                // Medido en el Fire TV el 2026-08-14: `isPlaying` se pone en true apenas VLC abre,
                // antes del primer fotograma, así que un canal que reabría y moría en pos=0ms
                // reponía igual las tres reaperturas — el tope no se agotaba nunca y el aviso en
                // pantalla no podía aparecer. Reponer solo cuando de verdad se reprodujo un rato
                // es lo que distingue "se recuperó" de "reabrió y se cayó de nuevo".
                if (enVivo) vm.vivoAndando(positionMs)
            }
            estadoPistas.sincronizarSubsOn()
            // "Arranca negro y con sonido": mientras libVLC ya suelta el audio pero todavía no dio
            // la primera imagen, `playbackState` NO es BUFFERING y la pantalla se quedaba sin
            // spinner y sin imagen. Casteando no aplica: la imagen la pone la TV, no nosotros.
            sinPrimeraImagen = !casting && vlc.esperandoPrimeraImagen()
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
            // El episodio lo identifica la playlist LOCAL, no el player activo.
            val epId = playlistRef.value?.items?.getOrNull(controller.currentMediaItemIndex)?.episodeId
            // !enVivo (Tarea 14): en vivo no hay "dónde ibas" que guardar -- ni "continuar viendo"
            // ni barra de progreso que reanudar. Sondear/guardar posición en un directo fue justo
            // lo que rompió el VOD de Magis (ver KDoc de LiveController).
            if (!enVivo && tick % 10 == 0 && epId != null && mediaId == epId && ready && activePlayer.isPlaying &&
                dur > 0 && pos in 0 until dur
            ) {
                vm.saveProgress(epId, pos, dur)
                // Cada 600 ticks = 5 min. Va acá adentro para heredar las mismas guardas que el
                // progreso: sin `mediaId == epId` se capturaría el frame del capítulo viejo bajo
                // el id del nuevo.
                // !casting: casteando, `pos` es la posición del receptor REMOTO, pero el
                // TextureView sigue siendo el LOCAL, que en ese momento no pinta lo que se ve en la
                // tele. Capturarlo guardaría una imagen que no corresponde a esa posición (y se
                // repetiría en cada disparo mientras dure el casteo).
                if (tick % 600 == 0 && !casting) vm.capturarFrame(epId, pos, textureViewDelVideo())
            }
            // Latido mientras se castea: dice si el receptor AVANZA de verdad. Una posición
            // clavada con estado=listo significa que aceptó el medio pero no lo está decodificando.
            if (casting && tick % 6 == 0) {
                android.util.Log.i(
                    "ArkivCast",
                    "latido · pos=${pos}ms dur=${dur}ms estado=${activePlayer.playbackState} reproduciendo=${activePlayer.isPlaying}",
                )
            }
        }
    }

    /**
     * Captura el frame al PAUSAR quedándose en el reproductor. Salir tiene su propia captura (en el
     * onDispose de más abajo) y hay otra periódica cada 5 min; esta es la de "pausé para irme a
     * hacer algo", que es justo cuando la miniatura tiene que quedar en lo último que se vio.
     *
     * Va colgada de `quiereReproducir` (playWhenReady) y no de `isPlaying` a propósito: isPlaying
     * también se cae en cada rebuffer del torrent, así que capturaría —medio millón de píxeles,
     * comprimir y escribir a disco— en cada tirón de red. playWhenReady solo cambia cuando alguien
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
    LaunchedEffect(quiereReproducir) {
        if (quiereReproducir || casting) return@LaunchedEffect
        val pos = activePlayer.currentPosition
        val dur = activePlayer.duration
        val mediaId = activePlayer.currentMediaItem?.mediaId
        val epId = playlistRef.value?.items?.getOrNull(controller.currentMediaItemIndex)?.episodeId
        if (epId != null && mediaId == epId && dur > 0 && pos in 0 until dur) {
            vm.capturarFrame(epId, pos, textureViewDelVideo())
        }
    }

    // Auto-ocultar los controles mientras reproduce. El timer se reinicia con CUALQUIER tecla
    // mientras el overlay está abierto (ver el onPreviewKeyEvent del contenedor), así que no se
    // desvanece en plena navegación de botones o miniaturas — solo tras ~4.5s de inactividad real.
    // No se bloquea del todo a propósito: el auto-ocultado es la única salida cuando el video está
    // en pausa (con BACK ahí abajo cerrando el overlay, pero solo mientras se esté viendo).
    // estadoCapitulos.revelado va como key para que abrir/cerrar el carrusel arranque un timer fresco.
    LaunchedEffect(interactionTick, isPlaying, controlsVisible, estadoCapitulos.revelado) {
        if (controlsVisible && isPlaying && markingMode == null) {
            delay(4500)
            controlsVisible = false
        }
    }

    // BACK con el overlay en pantalla lo CIERRA en vez de salir del video; con el overlay ya
    // oculto, este handler queda deshabilitado y BACK sigue de largo a la navegación (= salir),
    // que es el comportamiento de siempre. Sirve igual para el remoto de la TV, el botón del
    // sistema y el gesto de atrás del teléfono.
    // La condición replica la del overlay más abajo (`AnimatedVisibility(visible = ...)`): si solo
    // mirara controlsVisible, en modo marcado o con un error en pantalla la variable puede seguir
    // en true sin que se vea nada, y BACK quedaría muerto (ni cierra ni sale). Mantener ambas
    // iguales si se toca una.
    BackHandler(enabled = !enVivo && controlsVisible && loadError == null && estadoDlna.activo == null && markingMode == null) {
        controlsVisible = false
    }


    // TV: al mostrarse el overlay, mover el foco de Android desde el video (que hasta ahora
    // atajaba TODAS las teclas con acciones fijas) hacia los controles de Compose, para que el
    // D-pad navegue los botones/la barra como un player real (Netflix/Prime) en vez de mapeos
    // fijos por tecla. Al ocultarse, el foco vuelve al video para el "cualquier tecla = mostrar".
    LaunchedEffect(controlsVisible, isTv) {
        if (!isTv) return@LaunchedEffect
        if (controlsVisible) {
            runCatching { focos.barra.requestFocus() }
                .onFailure { runCatching { focos.playPausa.requestFocus() } }
        } else {
            // Al ocultarse el overlay el carrusel deja de existir: si estadoCapitulos.revelado quedara en
            // true, al reaparecer se mostraría ya abierto pero con el foco en el botón de play.
            estadoCapitulos.ocultar()
            runCatching { videoView?.requestFocus() }
        }
    }

    // TV: al cerrarse el diálogo de audio/subtítulos hay que reubicar el foco a mano. Antes iba al
    // videoView, pero con el overlay todavía visible ese es un punto muerto —su listener descarta
    // las teclas mientras controlsVisible es true— y el D-pad dejaba de responder. Vuelve al botón
    // que abrió el diálogo; el video solo tiene sentido si el overlay ya se ocultó.
    // Va en un efecto y no en el onDismiss para cubrir las DOS salidas: descartar el diálogo y
    // elegir una pista (`aplicarSubtituloOnline` también cierra el picker, y ahí nadie toca el foco).
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
        if (controlsVisible) {
            repeat(12) {
                if (landed) return@repeat
                landed = runCatching { focos.subtitulos.requestFocus() }.isSuccess
                if (!landed) delay(32)
            }
        }
        if (!landed) runCatching { videoView?.requestFocus() }
    }

    // Lo que antes hacía el listener con el reproductor LOCAL. Al empezar a castear se pausa; al
    // terminar se adelanta hasta donde llegó el receptor antes de reanudar — si no, el sondeo
    // persiste la posición vieja encima de la buena en ≤5s.
    var casteabaAntes by remember { mutableStateOf(false) }
    LaunchedEffect(casting) {
        if (casting) {
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
                    android.util.Log.w("ArkivCast", "sesión abierta pero no hay URL que mandarle al receptor")
                    android.widget.Toast.makeText(
                        context,
                        "No se pudo castear: la TV no puede alcanzar este stream (revisa el WiFi)",
                        android.widget.Toast.LENGTH_SHORT,
                    ).show()
                } else {
                    android.util.Log.w("ArkivCast", "sesión abierta → mando el capítulo en curso al receptor desde ${desde}ms")
                    castSession.setMedia(req)
                    casteadoAlReceptor = epId
                }
            }
            runCatching { controller.pause() }
        } else if (casteabaAntes) {
            casteadoAlReceptor = null
            // Si la sesión terminó porque el usuario pulsó "parar" (botón de la barra), NO hay que
            // reanudar acá: pidió silencio, y el local ya quedó pausado desde que empezó el casteo
            // (rama de arriba) — reanudarlo sería justo lo contrario de lo que pidió ese botón. Se
            // consume una sola vez: la próxima desconexión (la del botón de cast, no la de parar)
            // vuelve a reanudar normal.
            if (graph.castSession?.consumirParadaIntencional() != true) {
                if (enVivo) {
                    // Vivo (Tarea 18): sin "dónde ibas" que reanudar -- sería la posición que
                    // reporta el receptor sobre un HLS en vivo, que no significa nada como offset
                    // dentro del proxy local (ver el KDoc de castRequestFor/CastRequestBuilder). El
                    // controller ya quedó cebado con el canal vigente (LaunchedEffect(playlist), sea
                    // por la conexión inicial o por el último zap), solo hay que prepararlo y
                    // arrancarlo desde el vivo actual.
                    runCatching { controller.prepare() }
                    runCatching { controller.play() }
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
                    // Con el audio transcodificado el receptor cuenta desde cero: hay que sumarle el
                    // punto donde arrancó el stream, o desconectar tira la reproducción hacia atrás
                    // hasta donde empezó el casteo.
                    runCatching {
                        CastProgress.contentPosition(
                            receiverPosMs = castPlayer?.currentPosition ?: 0L,
                            baseOffsetMs = graph.castSession?.baseOffsetMs ?: 0L,
                        )
                    }.getOrDefault(0L)
                } else {
                    android.util.Log.w("ArkivCast", "posición del receptor descartada: es de '$castMediaId', reanudamos '$epId'")
                    0L
                }
                // RECARGAR, no hacer seek. El local quedó cebado con `setMediaItems(…, startPositionMs)`
                // y sin input abierto (playWhenReady=false), y eso ya horneó `:start-time=<esa posición>`
                // en el Media de libVLC: el seekTo() es un no-op sin input, prepare() tampoco recarga
                // (VlcPlayer.handlePrepare() solo actúa si mediaPlayer.media es null) y el play() abría
                // el input respetando el start-time VIEJO. Resultado: el local reanudaba donde EMPEZÓ el
                // casteo y el sondeo pisaba la posición buena a los segundos. Volver a llamar a
                // setMediaItems() re-hornea el start-time en la posición del receptor.
                if (castPos > 0L && pl != null) {
                    runCatching { controller.setMediaItems(localMediaItems(pl.items), currentIndex, castPos) }
                }
                // El local pudo quedar cebado SIN preparar (rama CAST de arriba): recién acá, al reanudar
                // de verdad, se prepara. Si ya estaba preparado (se venía reproduciendo en local antes de
                // castear) esto es un no-op: VlcPlayer.handlePrepare() solo recarga si mediaPlayer.media
                // sigue nulo.
                runCatching { controller.prepare() }
                runCatching { controller.play() }
            }
        }
        casteabaAntes = casting
    }

    // Al marcar (archive): pausar y ubicar el slider en el valor ya guardado (si existe).
    // El reanudar (play) SOLO aplica al SALIR del modo marcado — no en la composición inicial:
    // si no, al abrir una fuente web nueva este play() reviviría el video anterior (que sigue
    // cargado en el service) por detrás del overlay "Resolviendo…" mientras se resuelve la nueva.
    var wasMarking by remember { mutableStateOf(false) }
    LaunchedEffect(markingMode) {
        if (markingMode != null) {
            wasMarking = true
            controller.pause()
            val existing = when (markingMode) {
                MarkingMode.INTRO -> d?.openingEndMs
                MarkingMode.OUTRO -> d?.endingStartMs
                else -> null
            }
            if (existing != null) {
                controller.seekTo(existing)
                positionMs = existing
            }
        } else if (wasMarking) {
            wasMarking = false
            controller.play()
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
            val epId = playlistRef.value?.items?.getOrNull(currentIndex)?.episodeId
            // !enVivo (Tarea 14): salir de un canal en vivo no tiene "posición" que guardar.
            if (!enVivo && epId != null && mediaId == epId && dur > 0 && pos in 0 until dur) {
                vm.saveProgress(epId, pos, dur)
                // Captura de SALIDA, y solo de salida: el `controller.pause()` de acá arriba es el
                // que da esta misma función al irse, así que este bloque NO cubre al que aprieta
                // pausa y se queda mirando la pantalla quieta. Esa la captura el efecto de
                // `quiereReproducir` (más arriba), y por eso existen las dos.
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
    // de arriba solo corre al DESTRUIR la pantalla (back/swipe); con Home el composable sobrevive
    // y el audio sigue, pero si el proceso muere después la posición se pierde.
    DisposableEffect(lifecycleOwner) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_STOP) {
                val epId = playlistRef.value?.items?.getOrNull(currentPlayer.currentMediaItemIndex)?.episodeId
                val pos = currentPlayer.currentPosition
                val dur = currentPlayer.duration
                val mediaId = currentPlayer.currentMediaItem?.mediaId
                if (!enVivo && epId != null && mediaId == epId && dur > 0 && pos in 0 until dur) {
                    vm.saveProgress(epId, pos, dur)
                    if (!casting) vm.capturarFrame(epId, pos, textureViewDelVideo())
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    // Servicio en primer plano (solo torrent): mantiene vivo el proceso (sesión + server local)
    // mientras el reproductor está abierto, para que backgroundear/castear no lo mate.
    DisposableEffect(sourceIsTorrent) {
        if (sourceIsTorrent) TorrentServingService.start(context)
        onDispose {
            // Casteando NO se para: es el único servicio en primer plano de la app y el receptor
            // está jalando bytes justamente del server LAN de este proceso. Pararlo al salir de la
            // pantalla congelaba la TV, que es lo contrario de lo que este servicio existe para
            // evitar. Se lee el flujo directo (no el `casting` de Compose): el colector del estado
            // ya se soltó cuando corre este onDispose.
            val casteando = castSession?.casting?.value == true
            if (sourceIsTorrent && !casteando) TorrentServingService.stop(context)
        }
    }

    // Transporte por el player activo (Chromecast si hay sesión, si no el local).
    val seekStepMs = 10_000L

    /**
     * Mueve la reproducción a [targetMs] DEL CONTENIDO.
     *
     * Casteando transcodificado no se puede "buscar": lo que sale es un stream en vivo, sin duración
     * ni Range. Moverse significa rearrancar el transcode en el punto nuevo y recargar el receptor
     * —lo mismo que hace Jellyfin cuando no usa HLS—, y eso ya lo sabe hacer `castRequestFor`.
     */
    fun seekTo(targetMs: Long) {
        val dur = contentDurationMs()
        val target = targetMs.coerceIn(0L, if (dur > 0) dur else Long.MAX_VALUE)
        val transcodificando = casting && graph.castTranscoder.activeUrl != null
        if (transcodificando) {
            val pl = playlistRef.value
            val req = pl?.let { castRequestFor(it, currentIndex, target) }
            if (req != null) {
                android.util.Log.i("ArkivCast", "seek casteando: rearranco el transcode en ${target}ms")
                graph.castSession?.setMedia(req)
            }
        } else {
            activePlayer.seekTo(target)
        }
        positionMs = target
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
            val epId = playlistRef.value?.items?.getOrNull(currentIndex)?.episodeId
            val pos = activePlayer.currentPosition
            val dur = activePlayer.duration
            val mediaId = activePlayer.currentMediaItem?.mediaId
            if (!enVivo && epId != null && mediaId == epId && dur > 0 && pos in 0 until dur) {
                vm.saveProgress(epId, pos, dur)
                if (!casting) vm.capturarFrame(epId, pos, textureViewDelVideo())
            }
        } else {
            activePlayer.play()
        }
        // Vivo no usa bump()/controlsVisible (ese overlay entero está oculto -- ver más abajo,
        // "visible = !enVivo && ..."): sin esta guarda, togglePlayPause() (alcanzable desde el
        // centro del D-pad en TV) dejaba controlsVisible en true igual, y el BackHandler de abajo
        // (atado a esa misma variable) se comía el primer BACK cerrando un overlay invisible en
        // vez de salir del reproductor.
        if (!enVivo) bump()
    }

    // Búsqueda automática de subtítulos online para el idioma preferido (ver `EstadoDePistas`).
    LaunchedEffect(episodeId) {
        estadoPistas.buscarOnline(episodeId, sourceIsTorrent)
    }

    // Subtítulos EMBEBIDOS en el torrent (.srt/.ass junto al video): el engine los prioriza (son KB, bajan
    // al instante); acá los cargamos como pista externa apenas existan en disco. Aparecen en el menú CC
    // junto a los del contenedor. Sondeo unos segundos porque bajan en paralelo con el arranque.
    LaunchedEffect(episodeId) {
        if (!sourceIsTorrent) return@LaunchedEffect
        val loaded = mutableSetOf<String>()
        repeat(20) {
            withContext(Dispatchers.IO) { graph.torrentEngine.embeddedSubtitleFiles() }.forEach { f ->
                if (loaded.add(f.absolutePath)) runCatching { vlc.addSubtitleSlave(Uri.fromFile(f), byUser = false) }
            }
            delay(1000)
        }
    }

    val onOpenEpisodesState = rememberUpdatedState(onOpenEpisodes)

    val outerModifier = if (isLandscape) Modifier.fillMaxSize()
    else Modifier.fillMaxSize().systemBarsPadding()

    Box(Modifier.fillMaxSize().background(Color.Black).clipToBounds(), contentAlignment = Alignment.Center) {
        AndroidView(
            modifier = outerModifier,
            factory = { ctx ->
                VLCVideoLayout(ctx).also { layout ->
                    val previo = videoView
                    videoView = layout
                    if (previo != null) {
                        android.util.Log.w(
                            "ArkivVout",
                            "FACTORY #$pantallaId pisa videoView " +
                                "#${Integer.toHexString(System.identityHashCode(previo))} → " +
                                "#${Integer.toHexString(System.identityHashCode(layout))}",
                        )
                    }
                    vlc.attachVideo(layout, "factory#$pantallaId")
                    if (isTv) {
                        layout.isFocusable = true
                        layout.isFocusableInTouchMode = true
                        layout.setOnKeyListener { _, keyCode, event ->
                            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                            if (markingMode != null) return@setOnKeyListener false
                            // Con el overlay de controles visible, el foco de Android ya está en
                            // los botones de Compose (ver LaunchedEffect(controlsVisible)) y este
                            // listener ni siquiera debería recibir el evento; el fallback existe
                            // solo por si el foco no llegó a moverse a tiempo.
                            if (controlsVisible) return@setOnKeyListener false
                            // Vivo (Tarea 14): Arriba/Abajo zapean en vez de mostrar el overlay de
                            // VOD (que en vivo no existe, ver `visible = !enVivo && ...`), e
                            // Izquierda/Derecha no hacen seek (no hay duración/posición en vivo).
                            if (enVivo) {
                                // El cajón se queda con la flecha izquierda ANTES que nada. Con el
                                // cajón abierto este listener ya no recibe teclas (el foco de
                                // Android está en las filas de Compose), así que acá solo puede
                                // pasar el caso "cerrado + izquierda".
                                val accionDelCajon =
                                    DpadDelDrawer.accion(keyCode, estadoVivo.cajonAbierto, estadoVivo.focoCajon)
                                if (accionDelCajon == AccionDelDrawer.ABRIR) {
                                    estadoVivo.abrirCajon()
                                    return@setOnKeyListener true
                                }
                                return@setOnKeyListener when (keyCode) {
                                    KeyEvent.KEYCODE_DPAD_UP -> { vm.zapAnterior(); estadoVivo.mostrarInfo(); true }
                                    KeyEvent.KEYCODE_DPAD_DOWN -> { vm.zapSiguiente(); estadoVivo.mostrarInfo(); true }
                                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
                                    KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                                    KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE ->
                                        { togglePlayPause(); true }
                                    else -> false
                                }
                            }
                            when (keyCode) {
                                KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD ->
                                    { seekBy(seekStepMs); true }
                                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND ->
                                    { seekBy(-seekStepMs); true }
                                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
                                KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                                KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE ->
                                    { togglePlayPause(); true }
                                // Cualquier otra flecha o MENÚ, con el overlay oculto: solo mostrarlo
                                // (igual que Netflix/Prime) — la navegación real entre botones pasa
                                // a manejarla el foco de Compose una vez visible.
                                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_MENU ->
                                    { bump(); true }
                                else -> false
                            }
                        }
                        layout.post { layout.requestFocus() }
                    }
                }
            },
            // El orden entre este onRelease y el factory de la pantalla entrante es justo lo que
            // hay que ver: si suelta DESPUÉS del attach nuevo, le desarma el video a la que acaba
            // de engancharlo y quedás en Vout 0 con el audio sonando.
            onRelease = { vlc.detachVideo("onRelease#$pantallaId") },
        )

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
                                // que controlsVisible/bump() de VOD, con su propio estado.
                                if (enVivo) estadoVivo.alternarInfo()
                                else if (controlsVisible) controlsVisible = false else bump()
                            },
                            onDoubleTap = { o ->
                                // Sin seek en vivo (no hay duración ni "adelante/atrás" que tengan sentido).
                                if (!enVivo) { if (o.x < size.width / 2) seekBy(-seekStepMs) else seekBy(seekStepMs) }
                            },
                            onLongPress = {
                                // Casteando no: el 2× temporal actúa sobre el VlcPlayer local, que no
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
                    .pointerInput(isTorrent, casting) {
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
                                // seek/volumen/brillo en vivo, ver onDrag de abajo).
                                if (enVivo) {
                                    if (!horizontal && kotlin.math.abs(totalDy) > UMBRAL_ZAP_PX) {
                                        if (totalDy < 0) vm.zapSiguiente() else vm.zapAnterior()
                                        estadoVivo.mostrarInfo()
                                    }
                                } else if (horizontal) {
                                    activePlayer.seekTo(seekTarget); positionMs = seekTarget; bump()
                                } else if (!isTorrent && totalDy > 240f && totalDy > kotlin.math.abs(totalDx) * 1.5f) {
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
                                    // Casteando no: el volumen se lee/ajusta sobre el VlcPlayer local,
                                    // que no es lo que suena en el receptor Chromecast — gesto inerte.
                                    if (!casting) {
                                        val v = (vlc.vlcVolume() - (drag.y / size.height * 150f).toInt()).coerceIn(0, 100)
                                        vlc.setVlcVolume(v); gestos.mostrarHud("🔊 $v%")
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

        // Spinner / overlay de descarga: para torrent muestra %, velocidad y peers. En la fase de
        // pre-buffer (aún sin playlist) usa prepProgress del VM ("Buscando peers…/Cargando inicio…");
        // ya reproduciendo pero buffereando usa el streamStatus polled.
        // Casteando TAMBIÉN se muestra: `isBuffering` sigue al player activo, así que mientras el
        // receptor carga apaga la fila de transporte, y sin spinner la pantalla quedaba con el
        // degradado, la barra superior y el cartel de Chromecast — nada más, ni controles ni una
        // explicación. Lo que sí se sigue ocultando al castear es el detalle de descarga del
        // torrent (abajo): es del motor local, que está pausado, y no describe lo que carga la TV.
        // `esperandoVideo` también se anula casteando: espera a que VLC recupere su salida de video
        // local (hasta 15s tras volver del fondo), que casteando no importa ni va a llegar.
        if (loadError == null && estadoDlna.activo == null &&
            (playlist == null || isBuffering || sinPrimeraImagen || (esperandoVideo && !casting))
        ) {
            val preBuffer = playlist == null && sourceIsTorrent
            val p = if (preBuffer) prepProgress else progress
            Column(
                modifier = Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                CircularProgressIndicator(color = if (sourceIsTorrent) ArkivRed else Color.White, strokeWidth = 3.dp)
                if (resolving) {
                    Text("Resolviendo fuente $fuenteQueResuelve…", color = Color.White.copy(alpha = 0.9f), style = MaterialTheme.typography.labelMedium)
                }
                // Vivo (Tarea 14): resolver un canal ronda los 3s (dos llamadas al portal, ver
                // KDoc de LiveController) -- sin texto, este mismo spinner se ve idéntico a un
                // cuelgue.
                if (enVivo) {
                    Text("Cambiando de canal…", color = Color.White.copy(alpha = 0.9f), style = MaterialTheme.typography.labelMedium)
                }
                if (esperandoVideo && !casting) {
                    Text("Reanudando video…", color = Color.White.copy(alpha = 0.9f), style = MaterialTheme.typography.labelMedium)
                }
                if (sourceIsTorrent && !casting) {
                    Text(
                        when {
                            p == null -> if (preBuffer) "Preparando el torrent…" else "Preparando…"
                            p.peers == 0 -> "Buscando peers…"
                            preBuffer -> "Cargando inicio · ${(p.progress * 100).toInt()}% · ${p.peers} peers · ${fmtRate(p.downloadKbps)}"
                            else -> "Descargando · ${(p.progress * 100).toInt()}% · ${fmtRate(p.downloadKbps)} · ${p.peers} peers"
                        },
                        color = Color.White.copy(alpha = 0.9f),
                        style = MaterialTheme.typography.labelMedium,
                    )
                    if (p != null && p.peers > 0) {
                        LinearProgressIndicator(
                            progress = { p.progress },
                            color = ArkivRed,
                            trackColor = Color.White.copy(alpha = 0.25f),
                            modifier = Modifier.width(220.dp),
                        )
                    }
                    // Sin peers: reanunciar YA (además del reannounce automático del engine).
                    if (p != null && p.peers == 0) {
                        TextButton(onClick = { graph.torrentEngine.retryPeers() }) {
                            Text("Reintentar", color = ArkivRed)
                        }
                    }
                }
            }
        }

        // Error de resolución (torrent sin peers, .torrent ilegible, etc.).
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

        // Indicador persistente de descarga (torrent; aunque reproduzca y con controles ocultos).
        run {
            val p = progress
            if (isTorrent && !isTv && loadError == null && !casting && estadoDlna.activo == null && !isBuffering &&
                !controlsVisible && p != null && p.progress in 0f..0.999f
            ) {
                Row(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .systemBarsPadding()
                        .padding(12.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color(0x99000000))
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(Icons.Default.Download, contentDescription = null, tint = ArkivRed, modifier = Modifier.size(16.dp))
                    Text(
                        "${(p.progress * 100).toInt()}% · ${fmtRate(p.downloadKbps)}",
                        color = Color.White,
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
        }

        // Indicador "reproduciendo desde la NUC" (Task 11, sub-tarea de seguimiento). Antes la
        // única señal de que el capítulo venía de la NUC en vez de la fuente en vivo era la mera
        // PRESENCIA del botón "Reproducir en vivo" (más abajo, dentro del overlay de controles) —
        // había que darse cuenta de qué significaba que estuviera ahí. Este chip lo dice directo.
        // Vive AFUERA del AnimatedVisibility de los controles (como el cartel de Chromecast y el
        // indicador de descarga de torrent de arriba) a propósito: es informativo, no un control,
        // así que se mantiene visible aunque los controles se hayan desvanecido por inactividad.
        // Aviso de dato curioso nuevo: chiquito, arriba a la derecha, y se va solo a los 5 s (ver
        // el LaunchedEffect de `avisoTriviaVisible`). Va acá y no colgado del botón porque los
        // controles arrancan ocultos y se auto-ocultan: en la barra no lo vería nadie. Si el aviso
        // de "reproducir en vivo" está puesto, este baja para no taparlo.
        AnimatedVisibility(
            visible = avisoTriviaVisible && !triviaAbierta,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.TopEnd)
                .systemBarsPadding()
                .padding(top = if (showLiveOverride) 108.dp else 64.dp, end = 12.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(ArkivRed.copy(alpha = 0.85f)),
                contentAlignment = Alignment.Center,
            ) {
                Text("!", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
        }

        // TopEnd + top=64dp para no pisar el back/título de la barra superior (que ocupa la franja
        // 0–56dp) ni, en TV en pausa, el título/nombre de episodio de headerInfo (TopStart).
        if (showLiveOverride) {
            Row(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .systemBarsPadding()
                    .padding(top = 64.dp, end = 12.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0x99000000))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(Icons.Default.LiveTv, contentDescription = null, tint = ArkivRed, modifier = Modifier.size(16.dp))
                Text("Desde tu NUC", color = Color.White, style = MaterialTheme.typography.labelMedium)
            }
        }

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
            visible = !enVivo && controlsVisible && loadError == null && estadoDlna.activo == null && markingMode == null,
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
                    // reiniciaba bump(), que dispara el listener de VLC — y ese únicamente actúa con
                    // los controles OCULTOS, así que moverse con el D-pad no lo reiniciaba nunca.
                    // Va en el contenedor y como PREVIEW (no onKeyEvent): el preview baja desde la
                    // raíz antes de llegar al control enfocado, así que ve todas las teclas aunque
                    // alguien las consuma — el slider consume izq/der para el seek y la fila consume
                    // ABAJO, que con el burbujeo normal nunca habrían llegado hasta acá.
                    // Devuelve false: solo observa, no altera el despacho.
                    .onPreviewKeyEvent { e ->
                        if (e.type == KeyEventType.KeyDown) interactionTick++
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
                    if (MOSTRAR_MARCADORES_EN_TELEFONO && !isTv && !isTorrent && d != null) {
                        Box {
                            IconButton(onClick = { markersMenu = true }) {
                                Icon(Icons.Default.Tune, contentDescription = "Marcadores", tint = Color.White)
                            }
                            DropdownMenu(expanded = markersMenu, onDismissRequest = { markersMenu = false }) {
                                DropdownMenuItem(
                                    text = { Text("Setear intro (fin del opening)") },
                                    onClick = { markersMenu = false; markingMode = MarkingMode.INTRO },
                                )
                                DropdownMenuItem(
                                    text = { Text("Setear outro (inicio del ending)") },
                                    onClick = { markersMenu = false; markingMode = MarkingMode.OUTRO },
                                )
                                DropdownMenuItem(
                                    text = { Text("Borrar marcadores") },
                                    onClick = { markersMenu = false; vm.clearMarkers() },
                                )
                            }
                        }
                    }
                    if (!isTv && d != null) {
                        // Qué se está viendo. El título sale de headerInfo (nombre de la SERIE) y
                        // no de d.title, para que en series no muestre el nombre del capítulo;
                        // debajo, temporada/capítulo. d.title queda de respaldo si headerInfo
                        // todavía no cargó (se lee de la DB en un LaunchedEffect).
                        Column(modifier = Modifier.weight(1f).padding(horizontal = 8.dp)) {
                            Text(
                                headerInfo?.itemTitle ?: d.title,
                                color = Color.White,
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            headerInfo?.episodeLabel?.let { ep ->
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
                    // Velocidad + zoom nativo de VLC (solo teléfono): cíclicos al tocar. Ambas fuentes.
                    // Ocultos: el gesto de mantener presionado sigue dando 2× temporal, así que no se
                    // pierde el control de velocidad del todo.
                    // Casteando no: siguienteVelocidad()/siguienteZoom() actúan sobre el VlcPlayer local, que
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
                    headerInfo?.let { info ->
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
                                formatDuration(seek.posicionAMostrar(positionMs)),
                                color = Color.White, style = MaterialTheme.typography.labelMedium,
                            )
                            Slider(
                                value = seek.valorDeLaBarra(positionMs),
                                // Agarrar la barra descarta cualquier salto incremental pendiente: si
                                // no, el debounce de `seekBy` dispararía DESPUÉS de soltar y te
                                // devolvería al destino de las flechas, pisando el arrastre.
                                onValueChange = { v ->
                                    seek.arrastrarA(v)
                                    bump()
                                },
                                onValueChangeFinished = { seekTo(seek.soltar()) },
                                valueRange = 0f..(if (durationMs > 0) durationMs.toFloat() else 1f),
                                colors = SliderDefaults.colors(
                                    thumbColor = ArkivRed,
                                    activeTrackColor = ArkivRed,
                                    inactiveTrackColor = Color.White.copy(alpha = 0.3f),
                                ),
                                // Track custom con 3 capas: fondo (tenue) + buffer descargado (gris
                                // claro) + reproducido (rojo). Así se ve el buffer por delante del playhead.
                                track = { _ ->
                                    val dur = if (durationMs > 0) durationMs.toFloat() else 1f
                                    val posFrac = (seek.valorDeLaBarra(positionMs) / dur)
                                        .coerceIn(0f, 1f)
                                    val bufFrac = bufferedFraction.coerceIn(0f, 1f)
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
                                            .focusRequester(focos.barra)
                                            .onFocusChanged { seek.cambioElFoco(it.isFocused) }
                                            // ARRIBA se queda en la barra: es el tope del overlay y
                                            // los botones están DEBAJO, así que mandar `up` ahí era
                                            // un salto al revés (poco visible antes, porque el foco
                                            // no entraba acá; ahora es el primer control enfocado).
                                            .focusProperties { down = focos.playPausa; up = focos.barra; left = focos.barra; right = focos.barra }
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
                            Text(formatDuration(durationMs), color = Color.White, style = MaterialTheme.typography.labelMedium)
                        }
                        Spacer(Modifier.height(8.dp))
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
                            horizontalArrangement = Arrangement.spacedBy(20.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            // Los saltos de capítulo dependen SOLO de que el vecino exista, nunca del
                            // estado de transporte. Colgarlos de `isPlaying` (como estaba el de
                            // siguiente) los hacía parpadear: isPlaying se cae a false en cada
                            // rebuffer y en cada seek —VLC emite Buffering y VlcPlaybackState lo
                            // traduce a STATE_BUFFERING—, así que el botón aparecía al adelantar y
                            // se iba solo al volver a READY. Ver el KDoc de `quiereReproducir`.
                            // Se calculan ANTES de los botones para poder armar el grafo de foco
                            // completo (cada dirección explícita; dejar alguna sin definir hace que
                            // la búsqueda espacial por defecto de Compose falle y el foco "se pierda").
                            val prev = prevEpisodeId
                            val next = nextEpisodeId
                            val showPrev = prev != null
                            val showNext = next != null
                            val forwardRight = if (showNext) focos.episodioSiguiente else if (isTv) focos.subtitulos else focos.adelantar
                            val nextRight = if (isTv) focos.subtitulos else focos.episodioSiguiente
                            // Primero de la fila cuando existe: su `left` apunta a sí mismo (tope).
                            if (showPrev) {
                                TvTransportButton(
                                    icon = Icons.Default.SkipPrevious,
                                    contentDescription = "Capítulo anterior",
                                    onClick = { onNextEpisode(prev) },
                                    modifier = if (!isTv) Modifier else Modifier
                                        .focusRequester(focos.episodioAnterior)
                                        .focusProperties { left = focos.episodioAnterior; right = focos.retroceder; up = focos.barra; down = focos.episodioAnterior },
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
                                    .focusRequester(focos.retroceder)
                                    .focusProperties {
                                        left = if (showPrev) focos.episodioAnterior else focos.retroceder
                                        right = focos.playPausa
                                        up = focos.barra
                                        down = focos.retroceder
                                    },
                            )
                            TvTransportButton(
                                icon = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                contentDescription = if (isPlaying) "Pausar" else "Reproducir",
                                onClick = { togglePlayPause() },
                                iconSize = 34.dp,
                                modifier = if (!isTv) Modifier else Modifier
                                    .focusRequester(focos.playPausa)
                                    .focusProperties { left = focos.retroceder; right = focos.adelantar; up = focos.barra; down = focos.playPausa },
                            )
                            TvTransportButton(
                                icon = Icons.Default.Forward10,
                                contentDescription = "Adelantar 10s",
                                onClick = { seekBy(seekStepMs) },
                                modifier = if (!isTv) Modifier else Modifier
                                    .focusRequester(focos.adelantar)
                                    .focusProperties { left = focos.playPausa; right = forwardRight; up = focos.barra; down = focos.adelantar },
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
                                        .focusRequester(focos.episodioSiguiente)
                                        .focusProperties { left = focos.adelantar; right = nextRight; up = focos.barra; down = focos.episodioSiguiente },
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
                                        .focusRequester(focos.subtitulos)
                                        .focusProperties {
                                            left = if (showNext) focos.episodioSiguiente else focos.adelantar
                                            right = if (showLiveOverride) focos.verEnVivo else focos.bajarBrillo
                                            up = focos.barra
                                            down = focos.subtitulos
                                        },
                                )
                                // Override manual (Task 11): solo reproduciendo desde la NUC. Salta la
                                // preferencia guardada de la serie SOLO para este capítulo (no llama a
                                // PlaybackPreferenceStore.remember -- ver PlayerViewModel.forcePlayLive).
                                if (showLiveOverride) {
                                    TvTransportButton(
                                        icon = Icons.Default.LiveTv,
                                        contentDescription = "Reproducir en vivo",
                                        onClick = { vm.forcePlayLive(episodeId) },
                                        iconSize = 24.dp,
                                        tint = Color.White,
                                        modifier = Modifier
                                            .focusRequester(focos.verEnVivo)
                                            .focusProperties {
                                                left = focos.subtitulos
                                                right = focos.bajarBrillo
                                                up = focos.barra
                                                down = focos.verEnVivo
                                            },
                                    )
                                }
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
                                        .focusRequester(focos.bajarBrillo)
                                        .focusProperties {
                                            left = if (showLiveOverride) focos.verEnVivo else focos.subtitulos
                                            right = focos.subirBrillo
                                            up = focos.barra
                                            down = focos.bajarBrillo
                                        },
                                )
                                // Último de la fila: su `right` apunta a sí mismo (tope derecho).
                                TvTransportButton(
                                    icon = Icons.Default.BrightnessHigh,
                                    contentDescription = "Subir brillo",
                                    onClick = { gestos.pasoDeBrillo(-1, dimNivel) },
                                    iconSize = 24.dp,
                                    tint = if (dimNivel > 0) ArkivRed else Color.White,
                                    modifier = Modifier
                                        .focusRequester(focos.subirBrillo)
                                        .focusProperties {
                                            left = focos.bajarBrillo
                                            right = if (TriviaDelPlayer.hayBoton(trivia)) focos.trivia else focos.subirBrillo
                                            up = focos.barra
                                            down = focos.subirBrillo
                                        },
                                )
                            }
                            // TELÉFONO: subtítulos + override "en vivo" contra el borde derecho. El
                            // Spacer se come el ancho sobrante, así que los controles de transporte
                            // quedan a la izquierda y estos solo en la esquina — antes competía por
                            // espacio arriba con otros siete elementos.
                            // Casteando no: las pistas se eligen sobre PlaybackEngine.vlc, el
                            // reproductor local. El receptor de Chromecast maneja las suyas.
                            if (!isTv && !casting) {
                                Spacer(Modifier.weight(1f))
                                // Override manual (Task 11): solo reproduciendo desde la NUC (ver el
                                // mismo botón de TV arriba para el porqué).
                                if (showLiveOverride) {
                                    IconButton(onClick = { vm.forcePlayLive(episodeId) }) {
                                        Icon(Icons.Default.LiveTv, contentDescription = "Reproducir en vivo", tint = Color.White)
                                    // Datos curiosos: solo existe si el gateway devolvió algo. Va al
                                // FINAL de la fila a propósito -- insertarlo en el medio obligaría
                                // a reescribir varios eslabones de esta cadena de foco, y una
                                // equivocación ahí se siente como un control remoto roto.
                                if (TriviaDelPlayer.hayBoton(trivia)) {
                                    TvTransportButton(
                                        icon = Icons.Default.Info,
                                        contentDescription = "Dato curioso",
                                        onClick = { triviaAbierta = true; avisoTriviaVisible = false },
                                        iconSize = 24.dp,
                                        tint = Color.White,
                                        modifier = Modifier
                                            .focusRequester(focos.trivia)
                                            .focusProperties {
                                                left = focos.subirBrillo
                                                right = focos.trivia
                                                up = focos.barra
                                                down = focos.trivia
                                            },
                                    )
                                }
                                }
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
                            }
                        }
                        // Carrusel de capítulos (TV, series con más de 1 episodio): un paso más
                        // abajo desde la fila de íconos. Todos los episodios en scroll horizontal,
                        // con el actual resaltado y centrado al aparecer.
                        if (isTv && estadoCapitulos.hayCarrusel) {
                            CarruselDeCapitulos(
                                estado = estadoCapitulos,
                                episodioEnCurso = episodioEnCurso,
                                focoDeArriba = focos.playPausa,
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
            FichaDelCanal(estado = estadoVivo, canal = liveCanal, liveApi = graph.liveApi)
        }

        // Botones flotantes de saltar intro/outro (solo archive, teléfono). Casteando SÍ se
        // muestran: "Saltar intro" es un seekTo simple que el CastPlayer soporta igual; la guarda
        // real está en el botón "Saltar outro" de abajo (ese sí depende del ítem siguiente LOCAL).
        if (d != null && !isTorrent && markingMode == null && !isTv && estadoDlna.activo == null) {
            val inOpening = d.openingEndMs != null &&
                positionMs in (d.openingStartMs ?: 0L)..d.openingEndMs
            val inEnding = d.endingStartMs != null && positionMs >= d.endingStartMs
            Row(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .systemBarsPadding()
                    .padding(end = 20.dp, bottom = 88.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (inOpening) SkipButton("Saltar intro") { activePlayer.seekTo(d.openingEndMs!!) }
                // "Saltar intro" hace seekTo y funciona casteando; "saltar outro" salta al ítem
                // siguiente, y el cast tiene uno solo cargado — se oculta.
                if (inEnding && !casting) SkipButton("Saltar outro", icon = true) { controller.seekToNextMediaItem() }
            }
        }

        // Panel-editor de marcado con slider (solo archive).
        if (d != null && markingMode != null) {
            MarkerEditor(
                mode = markingMode!!,
                positionMs = positionMs,
                durationMs = durationMs,
                onSeek = { p -> seekTo(p) },
                onCancel = { markingMode = null },
                onSave = {
                    val label = if (markingMode == MarkingMode.INTRO) {
                        vm.setOpeningEnd(positionMs); "Intro"
                    } else {
                        vm.setEndingStart(positionMs); "Outro"
                    }
                    android.widget.Toast.makeText(
                        context,
                        "$label guardado en ${formatDuration(positionMs)}",
                        android.widget.Toast.LENGTH_SHORT,
                    ).show()
                    markingMode = null
                },
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }

        // Barra "Reproduciendo en <TV>" (DLNA activo).
        BarraDlnaActiva(estadoDlna)

        // Va ÚLTIMO dentro del Box para quedar por encima del resto de overlays.
        if (isTv && enVivo && estadoVivo.cajonAbierto) {
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

    // Diálogo "¿NUC o en vivo?" (Task 11): se pregunta una sola vez por serie, la primera vez que se
    // abre un capítulo cuya serie tiene algo bajado a la NUC. La respuesta la recuerda
    // PlaybackPreferenceStore (vía vm.resolveAskPlaybackSource); acá solo se muestra el estado que
    // expone el ViewModel.

    if (triviaAbierta) {
        AlertDialog(
            onDismissRequest = { triviaAbierta = false },
            title = { Text("Dato curioso") },
            text = { Text(trivia.getOrNull(indiceTrivia).orEmpty()) },
            confirmButton = {
                TextButton(onClick = { triviaAbierta = false }) { Text("Cerrar") }
            },
        )
    }

    if (askPlaybackSource != null) {
        AlertDialog(
            onDismissRequest = { vm.resolveAskPlaybackSource(com.arkiv.player.data.offline.PlaybackChoice.LIVE) },
            title = { Text("¿Reproducir desde tu NUC?") },
            text = { Text("Este capítulo ya está descargado en tu NUC de casa. ¿Reproducir esta serie desde ahí cuando esté disponible, en vez de en vivo?") },
            confirmButton = {
                TextButton(onClick = { vm.resolveAskPlaybackSource(com.arkiv.player.data.offline.PlaybackChoice.NUC) }) {
                    Text("Usar la NUC")
                }
            },
            dismissButton = {
                TextButton(onClick = { vm.resolveAskPlaybackSource(com.arkiv.player.data.offline.PlaybackChoice.LIVE) }) {
                    Text("En vivo")
                }
            },
        )
    }

    // Diálogo de dispositivos DLNA. El armado de la URL que se le manda al renderer vive en
    // `mandarAlRenderer`; acá solo queda de qué ítem sale y qué hacer si el renderer la rechaza.
    DialogoDispositivosDlna(estadoDlna) { device ->
        val ep = playlistRef.value?.items?.getOrNull(currentIndex)
        controller.pause()
        scope.launch {
            val ok = mandarAlRenderer(dlna, device, ep, graph.torrentEngine, graph.liveHlsProxy)
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

    // Diálogo de audio y subtítulos (embebidos vía VLC + OpenSubtitles). Los dos datos que recibe
    // son solo para etiquetar las pistas que magis entrega sin idioma; ver `etiquetaDeSpu`.
    DialogoDeAudioYSubtitulos(
        estado = estadoPistas,
        esMagis = PlayerSource.kindFor(episodeId) == SourceKind.MAGIS,
        idiomasDeclarados = webExtras?.subtitles?.map { it.lang }.orEmpty(),
    )
}

@Composable
private fun MarkerEditor(
    mode: MarkingMode,
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
                if (mode == MarkingMode.INTRO) "Marcá el FIN del intro" else "Marcá el INICIO del outro",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                "Movete con el slider hasta la posición exacta y guardá.",
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

@Composable
private fun SkipButton(text: String, icon: Boolean = false, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(
            containerColor = Color.White.copy(alpha = 0.92f),
            contentColor = Color.Black,
        ),
    ) {
        Text(text)
        if (icon) Icon(Icons.Default.SkipNext, contentDescription = null)
    }
}

private fun fmtRate(kbps: Int): String =
    if (kbps >= 1024) "%.1f MB/s".format(kbps / 1024.0) else "$kbps KB/s"
