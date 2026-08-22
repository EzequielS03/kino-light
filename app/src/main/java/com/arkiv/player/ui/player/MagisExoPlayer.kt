package com.arkiv.player.ui.player

import android.net.Uri
import android.os.SystemClock
import android.util.Log
import android.view.TextureView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.SubtitleView
import com.arkiv.player.data.catalog.web.ResolvedSub
import kotlinx.coroutines.delay

private const val TAG = "MagisExo"

/**
 * Reproduce un stream de Magis usando ExoPlayer.
 *
 * La URL ya llega proxificada por [archiveCacheProxy] (http://127.0.0.1:…), que inyecta los
 * headers de autenticación del CDN transparentemente. ExoPlayer la descarga como HTTP plano.
 *
 * [DefaultMediaSourceFactory] auto-detecta HLS, DASH o progresivo (MP4/TS) según el tipo de
 * contenido. Para la barra de progreso y los controles usa el mismo [EspejoDelPlayer] que VLC.
 *
 * Usa [TextureView] directamente para que [onTextureViewReady] exponga la superficie y
 * `capturarFrame` funcione igual que con VLC. El aspect ratio se mantiene escuchando
 * [Player.Listener.onVideoSizeChanged]: en portrait el video queda centrado en formato horizontal.
 *
 * Los subtítulos externos del portal se pasan como [subtitleConfigs] y ExoPlayer los carga
 * automáticamente; el [SubtitleView] superpuesto los renderiza en pantalla. Las pistas de audio
 * y subtítulo detectadas se notifican via [onTracksChanged] para que [EstadoDePistas] las
 * exponga en el menú.
 */
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
internal fun MagisExoPlayer(
    mediaUrl: String,
    espejo: EspejoDelPlayer,
    startPositionMs: Long = 0L,
    subtitleConfigs: List<MediaItem.SubtitleConfiguration> = emptyList(),
    onPlayerReady: (Player?) -> Unit = {},
    onTextureViewReady: (TextureView?) -> Unit = {},
    onError: (String) -> Unit = {},
    onTracksChanged: ((Tracks) -> Unit)? = null,
) {
    val context = LocalContext.current

    val exoPlayer = remember(mediaUrl, subtitleConfigs) {
        Log.i(TAG, "Creando ExoPlayer · url=${mediaUrl.take(80)} startMs=$startPositionMs subs=${subtitleConfigs.size}")
        val httpFactory = DefaultHttpDataSource.Factory()
            .setUserAgent("okhttp/4.12.0")
            .setConnectTimeoutMs(30_000)
            .setReadTimeoutMs(30_000)

        val mediaItem = MediaItem.Builder()
            .setUri(Uri.parse(mediaUrl))
            .setSubtitleConfigurations(subtitleConfigs)
            .build()

        // El CDN de magis entrega a 70–230 KB/s y sus ficheros traen 8 pistas de audio mal
        // intercaladas: el video vive en una zona y el audio a 13 MB de distancia, así que el
        // player salta entre las dos y cada salto le cuesta entre 1,6 s y 3,9 s de espera. Con el
        // buffer de fábrica —50 s de techo y 2,5 s para arrancar— se queda seco cada dos o tres
        // segundos y la imagen tartamudea. Se le da un buffer muy holgado para que cada zona se lea
        // de a tramos grandes y la latencia del CDN quede absorbida por delante.
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                /* minBufferMs = */ 120_000,
                /* maxBufferMs = */ 300_000,
                /* bufferForPlaybackMs = */ 5_000,
                /* bufferForPlaybackAfterRebufferMs = */ 15_000,
            )
            .setTargetBufferBytes(96 * 1024 * 1024)
            // Manda la duración y no el tamaño: con 8 pistas de audio el techo en bytes se alcanza
            // mucho antes que los segundos de video que hacen falta para cubrir un salto.
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(httpFactory))
            .setLoadControl(loadControl)
            .build()
            .also { player ->
                player.setMediaItem(mediaItem)
                player.prepare()
                if (startPositionMs > 0L) player.seekTo(startPositionMs)
                player.playWhenReady = true
                Log.i(TAG, "ExoPlayer preparado · seekTo=$startPositionMs")
            }
    }

    val textureView = remember(exoPlayer) { TextureView(context) }
    val subtitleView = remember(exoPlayer) {
        SubtitleView(context).apply {
            setUserDefaultStyle()
            setUserDefaultTextSize()
        }
    }
    var videoAspectRatio by remember(exoPlayer) { mutableFloatStateOf(0f) }

    DisposableEffect(exoPlayer) {
        exoPlayer.setVideoTextureView(textureView)
        onPlayerReady(exoPlayer)
        onTextureViewReady(textureView)
        espejo.sincronizarTransporte(
            buffereando = exoPlayer.playbackState == Player.STATE_BUFFERING,
            reproduciendo = exoPlayer.isPlaying,
            quiereReproducir = exoPlayer.playWhenReady,
        )
        Log.i(TAG, "DisposableEffect enganchado · state=${exoPlayer.playbackState} isPlaying=${exoPlayer.isPlaying}")

        val listener = object : Player.Listener {

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                val ratio = if (videoSize.height > 0)
                    videoSize.width.toFloat() * videoSize.pixelWidthHeightRatio / videoSize.height.toFloat()
                else 0f
                Log.i(TAG, "onVideoSizeChanged · ${videoSize.width}x${videoSize.height} sar=${videoSize.pixelWidthHeightRatio} → ratio=$ratio")
                if (ratio > 0f) videoAspectRatio = ratio
            }

            override fun onTracksChanged(tracks: Tracks) {
                val audio = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
                val text  = tracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }
                val video = tracks.groups.filter { it.type == C.TRACK_TYPE_VIDEO }

                Log.i(TAG, "onTracksChanged · video=${video.size} grupos / audio=${audio.size} grupos / subs=${text.size} grupos")

                audio.forEachIndexed { gi, group ->
                    for (ti in 0 until group.length) {
                        val fmt = group.getTrackFormat(ti)
                        Log.i(TAG, "  audio[$gi][$ti] lang=${fmt.language} label=${fmt.label} codec=${fmt.sampleMimeType} ch=${fmt.channelCount} selected=${group.isTrackSelected(ti)}")
                    }
                }
                text.forEachIndexed { gi, group ->
                    for (ti in 0 until group.length) {
                        val fmt = group.getTrackFormat(ti)
                        Log.i(TAG, "  subs[$gi][$ti] lang=${fmt.language} label=${fmt.label} mime=${fmt.sampleMimeType} selected=${group.isTrackSelected(ti)}")
                    }
                }

                onTracksChanged?.invoke(tracks)
            }

            override fun onCues(cueGroup: CueGroup) {
                subtitleView.setCues(cueGroup.cues)
            }

            override fun onPlaybackStateChanged(state: Int) {
                val nombre = when (state) {
                    Player.STATE_IDLE     -> "IDLE"
                    Player.STATE_BUFFERING -> "BUFFERING"
                    Player.STATE_READY    -> "READY"
                    Player.STATE_ENDED    -> "ENDED"
                    else                  -> "?"
                }
                Log.i(TAG, "onPlaybackStateChanged → $nombre · isPlaying=${exoPlayer.isPlaying} pos=${exoPlayer.currentPosition}ms dur=${exoPlayer.duration}ms")
                espejo.cambioElBuffering(state == Player.STATE_BUFFERING)
            }

            override fun onIsPlayingChanged(playing: Boolean) {
                Log.i(TAG, "onIsPlayingChanged → playing=$playing · pos=${exoPlayer.currentPosition}ms playWhenReady=${exoPlayer.playWhenReady}")
                espejo.cambioElPlaying(playing)
            }

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                Log.i(TAG, "onPlayWhenReadyChanged → playWhenReady=$playWhenReady reason=$reason · isPlaying=${exoPlayer.isPlaying}")
                espejo.cambioLaIntencion(playWhenReady)
            }

            override fun onRenderedFirstFrame() {
                Log.i(TAG, "onRenderedFirstFrame · pos=${exoPlayer.currentPosition}ms")
            }

            override fun onPlayerError(error: PlaybackException) {
                val msg = error.message ?: "Error de reproducción (${error.errorCode})"
                Log.e(TAG, "onPlayerError errorCode=${error.errorCode} msg=$msg", error)
                onError(msg)
            }
        }
        exoPlayer.addListener(listener)

        onDispose {
            Log.i(TAG, "onDispose · pos=${exoPlayer.currentPosition}ms isPlaying=${exoPlayer.isPlaying}")
            exoPlayer.removeListener(listener)
            exoPlayer.clearVideoTextureView(textureView)
            exoPlayer.release()
            espejo.reiniciarElReloj()
            espejo.sincronizarTransporte(buffereando = false, reproduciendo = false, quiereReproducir = false)
            onPlayerReady(null)
            onTextureViewReady(null)
        }
    }

    // Sondeo de posición. Además vigila dos patologías que el reloj solo no delata:
    //  · la posición avanza mientras !isPlaying (bug de transporte);
    //  · el reloj avanza pero el renderer no saca ni un frame — la imagen se queda congelada con la
    //    barra corriendo. Pasa cuando la red cambia debajo: el proxy abandona sus conexiones al
    //    origen, la respuesta que le sirve a ExoPlayer se corta a media descarga y ExoPlayer la lee
    //    como fin de stream legítimo. Se queda en READY sin pedir más datos, el AudioTrack se para
    //    y media3 cae a su reloj interno, que corre libre aunque no llegue un solo byte.
    //
    //    Se mide con los contadores del decoder, no con el reloj: son la única prueba de que un
    //    frame llegó a la pantalla. El rescate va escalonado porque las dos causas piden remedios
    //    distintos: primero un seek (barato, destraba un decoder atascado), y si el contador sigue
    //    clavado, prepare(), que es lo único que reconstruye la fuente y reabre el HTTP — un seek
    //    no reabre nada cuando el player cree que el stream ya terminó.
    LaunchedEffect(exoPlayer) {
        var lastPos = -1L
        var lastFrames = -1L
        var congeladoDesdeMs = 0L
        var ultimoRescateMs = 0L
        var rescatesSeguidos = 0

        while (true) {
            delay(500)
            val pos = exoPlayer.currentPosition
            val dur = exoPlayer.duration
            val playing = exoPlayer.isPlaying
            val wantPlay = exoPlayer.playWhenReady
            val state = exoPlayer.playbackState
            val frames = exoPlayer.videoDecoderCounters?.renderedOutputBufferCount?.toLong() ?: -1L

            if (!playing && !wantPlay && pos != lastPos && lastPos >= 0) {
                Log.w(TAG, "POSICION AVANZA PAUSADO · pos=$pos lastPos=$lastPos state=$state")
            }

            // El reloj corre de verdad (no es un seek ni una pausa) pero no entró ningún frame.
            val relojAvanzo = lastPos >= 0 && pos > lastPos
            val sinFrames = lastFrames >= 0 && frames == lastFrames
            val ahora = SystemClock.elapsedRealtime()

            if (playing && state == Player.STATE_READY && relojAvanzo && sinFrames && frames >= 0) {
                if (congeladoDesdeMs == 0L) {
                    congeladoDesdeMs = ahora
                    Log.w(TAG, "VIDEO SIN FRAMES · empieza · pos=${pos}ms frames=$frames")
                }
                val congeladoMs = ahora - congeladoDesdeMs
                // 8 s: el punto medio medido. Con 4 s el rescate entraba encima de los tirones
                // normales del CDN —que llegan a durar 4 s y se recuperan solos— y cada seek de
                // más obliga a reabrir conexiones contra el mismo CDN lento que ya venía ahogado.
                // Con 20 s la imagen se queda muerta demasiado tiempo: se midió un cuelgue en el
                // que el player pasó 20 s sin pedirle un solo byte al proxy y volvió 2,3 s después
                // del seek, así que esperar es puro castigo. El atasco no se cura solo.
                if (congeladoMs >= 8_000 && ahora - ultimoRescateMs >= 15_000) {
                    ultimoRescateMs = ahora
                    congeladoDesdeMs = 0L
                    rescatesSeguidos++
                    if (rescatesSeguidos == 1) {
                        Log.w(TAG, "VIDEO CONGELADO ${congeladoMs}ms · rescate 1: seekTo($pos)")
                        exoPlayer.seekTo(pos)
                    } else {
                        Log.w(TAG, "VIDEO CONGELADO ${congeladoMs}ms · rescate $rescatesSeguidos: prepare() para reabrir la fuente en $pos")
                        exoPlayer.seekTo(pos)
                        exoPlayer.prepare()
                        exoPlayer.playWhenReady = true
                    }
                }
            } else {
                if (congeladoDesdeMs != 0L) {
                    Log.i(TAG, "VIDEO SIN FRAMES · se recuperó tras ${ahora - congeladoDesdeMs}ms · frames=$frames")
                }
                congeladoDesdeMs = 0L
                // Solo cuenta como recuperado si de verdad entraron frames nuevos, no por un
                // pantallazo de BUFFERING entre dos tramos congelados.
                if (frames > lastFrames && lastFrames >= 0) rescatesSeguidos = 0
            }

            lastPos = pos
            lastFrames = frames

            espejo.leyoElReloj(
                posicionMs = pos,
                duracionMs = if (dur > 0) dur else 0L,
            )
        }
    }

    // El ratio se aplica en el Box contenedor, NO en el AndroidView: si el Modifier del
    // AndroidView cambiara (fillMaxSize → aspectRatio), Compose puede reattachar el TextureView
    // brevemente, destruyendo su SurfaceTexture y dejando el video en negro con audio.
    // Con un Box wrapper el TextureView siempre tiene fillMaxSize() → superficie estable.
    Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        Box(
            modifier = if (videoAspectRatio > 0f)
                Modifier.aspectRatio(videoAspectRatio)
            else
                Modifier.fillMaxSize(),
        ) {
            AndroidView(modifier = Modifier.fillMaxSize(), factory = { textureView })
        }
        // SubtitleView superpuesto: renderiza cues VTT/SRT cargados via SubtitleConfiguration.
        AndroidView(modifier = Modifier.matchParentSize(), factory = { subtitleView })
    }
}

/** Convierte la lista de subtítulos del portal a SubtitleConfiguration de ExoPlayer. */
internal fun List<ResolvedSub>.toExoSubtitleConfigs(): List<MediaItem.SubtitleConfiguration> =
    map { sub ->
        val mime = when {
            sub.url.contains(".vtt", ignoreCase = true) -> MimeTypes.TEXT_VTT
            sub.url.contains(".srt", ignoreCase = true) -> MimeTypes.APPLICATION_SUBRIP
            else -> MimeTypes.TEXT_VTT
        }
        MediaItem.SubtitleConfiguration.Builder(Uri.parse(sub.url))
            .setMimeType(mime)
            .setLanguage(sub.lang)
            .build()
    }
