package com.arkiv.player.ui.player

import android.net.Uri
import android.os.SystemClock
import android.util.Log
import android.view.TextureView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
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
import kotlinx.coroutines.delay

private const val TAG = "MagisExo"

/**
 * Plays a Magis stream using ExoPlayer.
 *
 * The URL already arrives proxied by [archiveCacheProxy] (http://127.0.0.1:...), which injects the
 * CDN's authentication headers transparently. ExoPlayer downloads it as plain HTTP.
 *
 * [DefaultMediaSourceFactory] auto-detects HLS, DASH or progressive (MP4/TS) based on the content
 * type. For the progress bar and controls it uses the same [PlayerMirror] VLC used to.
 *
 * Uses [TextureView] directly so [onTextureViewReady] exposes the surface and `capturarFrame`
 * works the same way it did with VLC. The aspect ratio is kept in sync by listening to
 * [Player.Listener.onVideoSizeChanged]: in portrait the video stays centered in landscape format.
 *
 * The portal's external subtitles are passed as [subtitleConfigs] and ExoPlayer loads them
 * automatically; the overlaid [SubtitleView] renders them on screen. Detected audio and subtitle
 * tracks are reported via [onTracksChanged] so [EstadoDePistas] can expose them in the menu.
 */
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
internal fun MagisExoPlayer(
    mediaUrl: String,
    espejo: PlayerMirror,
    startPositionMs: Long = 0L,
    subtitleConfigs: List<MediaItem.SubtitleConfiguration> = emptyList(),
    onPlayerReady: (Player?) -> Unit = {},
    onTextureViewReady: (TextureView?) -> Unit = {},
    onError: (String) -> Unit = {},
    onTracksChanged: ((Tracks) -> Unit)? = null,
    onPrimeraImagen: (Boolean) -> Unit = {},
    /**
     * The episode reached its end.
     *
     * Magis plays here and not on the service player, and the screen's own end-of-episode listener
     * deliberately stays quiet while an ExoPlayer is active -- its STATE_ENDED would belong to a
     * local player holding nothing. That left NOBODY watching for the end of a Magis episode, so
     * it simply stopped at the last frame and the next one had to be started by hand. The comment
     * excusing it said "the ExoPlayer handles its own end"; it never did.
     */
    onFinDelCapitulo: () -> Unit = {},
    zoom: Float = 1f,
) {
    val context = LocalContext.current

    val exoPlayer = remember(mediaUrl, subtitleConfigs) {
        Log.i(TAG, "Creating ExoPlayer · url=${mediaUrl.take(80)} startMs=$startPositionMs subs=${subtitleConfigs.size}")
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
        // segundos y la imagen tartamudea, así que se le da margen de sobra por delante.
        //
        // Pero solo hasta donde entra en un teléfono. Se probó con 300 s y 96 MB de techo y fue
        // peor que el mal original: a 236 KB/s de bitrate eso son ~70 MB retenidos, el heap se fue
        // de 107 MB a 142 MB, el GC entró en bucle y la imagen se congelaba cada 15 s como un
        // reloj. 60 s de techo son unos 14 MB, que cubren de sobra el salto más lento medido.
        // Lo que se acumula ANTES de reanudar tras un corte son 4 s y no 8: con este CDN esos
        // segundos de más se pagan carísimos. Medido en el Fire Stick con el origen a 36 KB/s —una
        // sexta parte de lo que pide el video— un rebuffer costó 85 s de espera, porque juntar 8 s
        // de contenido a ese caudal son casi 2 MB. Con 4 s la espera se parte por la mitad y sigue
        // habiendo colchón para un bache normal.
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                /* minBufferMs = */ 30_000,
                /* maxBufferMs = */ 60_000,
                /* bufferForPlaybackMs = */ 3_000,
                /* bufferForPlaybackAfterRebufferMs = */ 4_000,
            )
            .setTargetBufferBytes(24 * 1024 * 1024)
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
                Log.i(TAG, "ExoPlayer prepared · seekTo=$startPositionMs")
            }
    }

    val textureView = remember(exoPlayer) {
        TextureView(context).apply {
            // Sin opacidad, para que lo que no tenga imagen deje ver el fondo. Por sí solo NO quitó
            // la franja verde —se probó— pero es lo correcto para una vista que no llena su hueco,
            // y no cuesta nada.
            isOpaque = false
            // Dónde queda colocado. Fue lo que destapó que la vista se encogía a mitad de camino
            // (1920x1080 al montarse, 1920x800 al llegar la proporción del video), y sigue acá por
            // si algún aparato vuelve a hacer algo raro con el tamaño.
            addOnLayoutChangeListener { _, l, t, r, b, _, _, _, _ ->
                Log.i(TAG, "TextureView placed at [$l,$t]-[$r,$b] · ${r - l}x${b - t}")
            }
        }
    }
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
        espejo.syncTransport(
            buffering = exoPlayer.playbackState == Player.STATE_BUFFERING,
            playing = exoPlayer.isPlaying,
            wantsToPlay = exoPlayer.playWhenReady,
        )
        Log.i(TAG, "DisposableEffect hooked · state=${exoPlayer.playbackState} isPlaying=${exoPlayer.isPlaying}")

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

                Log.i(TAG, "onTracksChanged · video=${video.size} groups / audio=${audio.size} groups / subs=${text.size} groups")

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
                espejo.updateBuffering(state == Player.STATE_BUFFERING)
                if (state == Player.STATE_ENDED) {
                    Log.w(TAG, "episode ended at ${exoPlayer.currentPosition}ms of ${exoPlayer.duration}ms")
                    onFinDelCapitulo()
                }
            }

            override fun onIsPlayingChanged(playing: Boolean) {
                Log.i(TAG, "onIsPlayingChanged → playing=$playing · pos=${exoPlayer.currentPosition}ms playWhenReady=${exoPlayer.playWhenReady}")
                espejo.updatePlaying(playing)
            }

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                Log.i(TAG, "onPlayWhenReadyChanged → playWhenReady=$playWhenReady reason=$reason · isPlaying=${exoPlayer.isPlaying}")
                espejo.updateWantsToPlay(playWhenReady)
            }

            override fun onRenderedFirstFrame() {
                Log.i(TAG, "onRenderedFirstFrame · pos=${exoPlayer.currentPosition}ms")
                onPrimeraImagen(true)
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
            espejo.resetClock()
            espejo.syncTransport(buffering = false, playing = false, wantsToPlay = false)
            onPlayerReady(null)
            onTextureViewReady(null)
            onPrimeraImagen(false)
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
                Log.w(TAG, "POSITION ADVANCES WHILE PAUSED · pos=$pos lastPos=$lastPos state=$state")
            }

            // El reloj corre de verdad (no es un seek ni una pausa) pero no entró ningún frame.
            val relojAvanzo = lastPos >= 0 && pos > lastPos
            val sinFrames = lastFrames >= 0 && frames == lastFrames
            val ahora = SystemClock.elapsedRealtime()

            if (playing && state == Player.STATE_READY && relojAvanzo && sinFrames && frames >= 0) {
                if (congeladoDesdeMs == 0L) {
                    congeladoDesdeMs = ahora
                    Log.w(TAG, "VIDEO WITHOUT FRAMES · starts · pos=${pos}ms frames=$frames")
                }
                val congeladoMs = ahora - congeladoDesdeMs
                // 5 s de margen: por debajo se confunde con los tirones normales del CDN, que
                // llegan a durar 4 s y se recuperan solos.
                //
                // El rescate ataca los tres puntos donde se midió el atasco, del más barato al más
                // caro, porque cada uno cura un caso que el anterior no:
                //  1. seekTo — destraba un decodificador atascado. A veces basta (se midió una
                //     recuperación en 190 ms), pero en el atasco duro el contador de frames se
                //     queda clavado en el mismo número tras un seek perfectamente exitoso.
                //  2. prepare() — reconstruye la fuente y reabre el HTTP. Cura cuando el player
                //     leyó el corte del proxy como fin de stream y dejó de pedir datos.
                //  3. reenganchar el TextureView — la superficie dejó de drenar y el decodificador
                //     se quedó sin buffers de salida. Se midió una tanda en la que ni el prepare()
                //     movía el contador: ahí no falta ni fuente ni decodificador, falta a dónde
                //     pintar, y solo soltar y volver a poner la superficie lo arregla.
                if (congeladoMs >= 5_000 && ahora - ultimoRescateMs >= 8_000) {
                    ultimoRescateMs = ahora
                    congeladoDesdeMs = 0L
                    rescatesSeguidos++
                    if (rescatesSeguidos <= 1) {
                        Log.w(TAG, "VIDEO FROZEN ${congeladoMs}ms · rescue 1: prepare() at $pos")
                        exoPlayer.seekTo(pos)
                        exoPlayer.prepare()
                        exoPlayer.playWhenReady = true
                    } else {
                        Log.w(TAG, "VIDEO FROZEN ${congeladoMs}ms · rescue $rescatesSeguidos: re-hooking the surface at $pos")
                        exoPlayer.clearVideoTextureView(textureView)
                        exoPlayer.setVideoTextureView(textureView)
                        exoPlayer.seekTo(pos)
                        exoPlayer.prepare()
                        exoPlayer.playWhenReady = true
                    }
                }
            } else {
                if (congeladoDesdeMs != 0L) {
                    Log.i(TAG, "VIDEO WITHOUT FRAMES · recovered after ${ahora - congeladoDesdeMs}ms · frames=$frames")
                }
                congeladoDesdeMs = 0L
                // Solo cuenta como recuperado si de verdad entraron frames nuevos, no por un
                // pantallazo de BUFFERING entre dos tramos congelados.
                if (frames > lastFrames && lastFrames >= 0) rescatesSeguidos = 0
            }

            lastPos = pos
            lastFrames = frames

            espejo.readClock(
                positionMs = pos,
                durationMs = if (dur > 0) dur else 0L,
            )
        }
    }

    // El ratio se aplica en el Box contenedor, NO en el AndroidView: si el Modifier del
    // AndroidView cambiara (fillMaxSize → aspectRatio), Compose puede reattachar el TextureView
    // brevemente, destruyendo su SurfaceTexture y dejando el video en negro con audio.
    // Con un Box wrapper el TextureView siempre tiene fillMaxSize() → superficie estable.
    // El TextureView NO cambia nunca de tamaño: ocupa siempre la pantalla entera y la proporción se
    // consigue transformando su contenido (ver [ajustarAlAspecto]).
    //
    // Antes se le daba el aspecto al Box de alrededor, y eso encogía la vista a mitad de camino: se
    // colocaba a 1920x1080 —hasta que el decodificador no arranca no se sabe la proporción— y al
    // llegar el onVideoSizeChanged pasaba a 1920x800. Pero su SurfaceTexture se había creado con
    // 1080, y los 280 px que sobraban seguían ahí con el búfer sin estrenar: una franja VERDE bajo
    // el video en toda película panorámica. Medido en el Fire Stick con una 2.4:1, y sin salir en
    // las 16:9 justamente porque ahí la vista ya llenaba la pantalla y nunca se encogía.
    BoxWithConstraints(
        Modifier.fillMaxSize().background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { textureView },
            update = { it.ajustarAlAspecto(videoAspectRatio, zoom) },
        )

        // BANDAS NEGRAS ENCIMA, tapando lo que sobra del video.
        //
        // Es un parche y conviene saberlo: en una película panorámica la mitad de abajo del hueco
        // salía VERDE —el búfer sin estrenar de la superficie— y no se encontró la causa. Se
        // descartaron, midiendo cada vez en el Fire Stick: la capa de composición del zoom, la
        // opacidad del TextureView, que la vista cambiara de tamaño a mitad de camino, y la
        // transformación del contenido. Con todas ellas el video quedaba EXACTAMENTE donde debía
        // (medido: y=138..941 para una 2.4:1 en 1080) y la franja seguía igual. Lo más raro es que
        // la banda de ARRIBA siempre salió negra y solo la de abajo verde, con la misma superficie.
        //
        // Así que se pinta negro encima de las dos bandas. No arregla el búfer, pero el hueco de una
        // panorámica tiene que ser negro y así lo es.
        // Las bandas van donde toque: arriba y abajo si el video es más ANCHO que la pantalla (una
        // panorámica en la tele), a los lados si es más ESTRECHO (un 4:3 en la tele, o cualquier
        // cosa en el móvil de pie). Solo una de las dos ramas puede darse a la vez, y con el video
        // justo del mismo formato no se pinta ninguna.
        val alto = maxHeight
        val ancho = maxWidth
        if (videoAspectRatio > 0f && alto > 0.dp && ancho > 0.dp) {
            val aspectoDeLaPantalla = ancho / alto
            if (videoAspectRatio > aspectoDeLaPantalla) {
                val banda = (alto - ancho / videoAspectRatio) / 2
                if (banda > 0.dp) {
                    Box(Modifier.align(Alignment.TopCenter).fillMaxWidth().height(banda).background(Color.Black))
                    Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(banda).background(Color.Black))
                }
            } else if (videoAspectRatio < aspectoDeLaPantalla) {
                val banda = (ancho - alto * videoAspectRatio) / 2
                if (banda > 0.dp) {
                    Box(Modifier.align(Alignment.CenterStart).fillMaxHeight().width(banda).background(Color.Black))
                    Box(Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(banda).background(Color.Black))
                }
            }
        }

        // SubtitleView superpuesto: renderiza cues VTT/SRT cargados via SubtitleConfiguration.
        AndroidView(modifier = Modifier.matchParentSize(), factory = { subtitleView })
    }
}

/**
 * Encaja el video en la vista sin deformarlo, moviendo el CONTENIDO y no la vista.
 *
 * ExoPlayer estira el video hasta llenar el TextureView, así que una película 2.4:1 en una pantalla
 * 16:9 sale achatada. Se corrige con la matriz de la superficie: se calcula cuánto sobra en el eje
 * que no encaja y se encoge por ahí, dejando el resto en negro como cualquier letterbox. Es lo mismo
 * que hace el PlayerView de media3 con TextureView.
 *
 * [zoom] multiplica al final, para que el gesto de zoom siga funcionando sobre el resultado.
 *
 * `internal` (no `private`): [LiveExoPlayer] la reusa tal cual para el mismo letterbox del vivo.
 */
internal fun TextureView.ajustarAlAspecto(aspectoDelVideo: Float, zoom: Float) {
    val w = width.toFloat()
    val h = height.toFloat()
    if (aspectoDelVideo <= 0f || w <= 0f || h <= 0f) return

    val aspectoDeLaVista = w / h
    // Solo se ENCOGE el eje que sobra: agrandar el otro recortaría imagen.
    val escalaX = if (aspectoDelVideo > aspectoDeLaVista) 1f else aspectoDelVideo / aspectoDeLaVista
    val escalaY = if (aspectoDelVideo > aspectoDeLaVista) aspectoDeLaVista / aspectoDelVideo else 1f

    setTransform(
        android.graphics.Matrix().apply {
            setScale(escalaX * zoom, escalaY * zoom, w / 2f, h / 2f)
        },
    )
}

/**
 * Tipo de un subtítulo a partir de su ruta. VTT por defecto: es lo que sirve el portal de magis;
 * el caso .srt queda por si algún día una fuente lo nombra así con su extensión.
 */
private fun mimeDeSubtitulo(ruta: String): String = when {
    ruta.contains(".srt", ignoreCase = true) -> MimeTypes.APPLICATION_SUBRIP
    else -> MimeTypes.TEXT_VTT
}

/** Convierte la lista de subtítulos del portal a SubtitleConfiguration de ExoPlayer. */
internal fun List<ResolvedSub>.toExoSubtitleConfigs(): List<MediaItem.SubtitleConfiguration> =
    map { sub ->
        MediaItem.SubtitleConfiguration.Builder(Uri.parse(sub.url))
            .setMimeType(mimeDeSubtitulo(sub.url))
            .setLanguage(sub.lang)
            .build()
    }
