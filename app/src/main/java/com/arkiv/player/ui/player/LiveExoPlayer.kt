package com.arkiv.player.ui.player

import android.net.Uri
import android.os.SystemClock
import android.util.Log
import android.view.TextureView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import kotlinx.coroutines.delay

private const val TAG = "LiveExo"

/**
 * Reproduce el canal en vivo de Magis usando ExoPlayer (Task 1, poda de light-magis — gate de
 * VLC). Reemplaza a VLC como reproductor del vivo; ver el KDoc de
 * [com.arkiv.player.ui.player.PlayerViewModel.abrirCanalActual] y el brief de la tarea para el
 * porqué del cambio.
 *
 * [mediaUrl] ya llega servida por [com.arkiv.player.playback.LiveHlsProxy] en `127.0.0.1`, con el
 * `Content-Auth`/`Content-License` del CDN inyectados por el proxy en cada petición — ExoPlayer la
 * descarga como HTTP plano, igual que hace [MagisExoPlayer] con el proxy de VOD. No hace falta
 * pasarle headers propios: la razón de ser del proxy es justamente que VLC no podía mandar esos
 * headers, y ExoPlayer tampoco los necesita porque nunca los ve — los pone el proxy.
 *
 * A diferencia de [MagisExoPlayer]:
 * - Sin `startPositionMs`/reanudación: un directo no tiene "dónde ibas".
 * - Sin subtítulos: el portal no los manda para el vivo.
 * - Sin [LoadControl] a medida: el problema que motivó el de Magis (un solo TS gigante con 8
 *   pistas de audio mal intercaladas) no existe acá — el proxy sirve un HLS bien segmentado, así
 *   que los valores por defecto de ExoPlayer (pensados para live) alcanzan.
 * - `onError` no debe mostrar un cartel: [PlayerViewModel.onLiveExoError] lo manda a
 *   `reabrirVivoPorCorte()`, que reintenta solo -- un canal en vivo se recupera casi siempre en
 *   unos segundos (ver su KDoc), y mostrar error en el primer tropiezo sería alarmar de más.
 */
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
internal fun LiveExoPlayer(
    mediaUrl: String,
    /**
     * Fuerza recrear el ExoPlayer aunque [mediaUrl] no cambie.
     *
     * [com.arkiv.player.playback.LiveHlsProxy.urlPara] devuelve SIEMPRE la misma URL
     * (`http://127.0.0.1:<puerto>/live.m3u8?t=<token>`) para toda la vida del proxy: el canal
     * activo lo decide el proxy puertas adentro (su campo `sesion`), no la URL. Zapear a otro
     * canal, o reabrir el mismo tras un corte, NO cambia `mediaUrl` -- solo cambia qué playlist
     * contesta el proxy en esa misma ruta. Sin esta clave, `remember` vería la URL igual y jamás
     * recrearía/re-prepararía el player: la pantalla se quedaría congelada en el canal viejo. Pasar
     * `liveItem.episodeId` (que sí cambia por canal) combinado con `generacionVivo` (que sube en
     * cada reapertura, incluida la del MISMO canal tras un corte -- ver su KDoc en
     * PlayerViewModel) cubre los dos casos. Es el equivalente ExoPlayer de lo que antes lograba
     * `controller.setMediaItems(...)+prepare()` en el VLC de siempre, forzado por esa misma marca.
     */
    key: Any,
    espejo: EspejoDelPlayer,
    onPlayerReady: (Player?) -> Unit = {},
    onTextureViewReady: (TextureView?) -> Unit = {},
    onError: (String) -> Unit = {},
    onPrimeraImagen: (Boolean) -> Unit = {},
    zoom: Float = 1f,
) {
    val context = LocalContext.current

    val exoPlayer = remember(key) {
        Log.i(TAG, "Creando ExoPlayer · url=${mediaUrl.take(80)} key=$key")
        val httpFactory = DefaultHttpDataSource.Factory()
            .setUserAgent("okhttp/4.12.0")
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(20_000)

        val mediaItem = MediaItem.Builder()
            .setUri(Uri.parse(mediaUrl))
            .build()

        ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(httpFactory))
            .build()
            .also { player ->
                player.setMediaItem(mediaItem)
                player.prepare()
                player.playWhenReady = true
                Log.i(TAG, "ExoPlayer preparado")
            }
    }

    val textureView = remember(exoPlayer) {
        TextureView(context).apply {
            isOpaque = false
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
        Log.i(TAG, "DisposableEffect enganchado · state=${exoPlayer.playbackState}")

        val listener = object : Player.Listener {

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                val ratio = if (videoSize.height > 0)
                    videoSize.width.toFloat() * videoSize.pixelWidthHeightRatio / videoSize.height.toFloat()
                else 0f
                if (ratio > 0f) videoAspectRatio = ratio
            }

            override fun onPlaybackStateChanged(state: Int) {
                val nombre = when (state) {
                    Player.STATE_IDLE -> "IDLE"
                    Player.STATE_BUFFERING -> "BUFFERING"
                    Player.STATE_READY -> "READY"
                    Player.STATE_ENDED -> "ENDED"
                    else -> "?"
                }
                Log.i(TAG, "onPlaybackStateChanged → $nombre · isPlaying=${exoPlayer.isPlaying} pos=${exoPlayer.currentPosition}ms")
                espejo.cambioElBuffering(state == Player.STATE_BUFFERING)
            }

            override fun onIsPlayingChanged(playing: Boolean) {
                espejo.cambioElPlaying(playing)
            }

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                espejo.cambioLaIntencion(playWhenReady)
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
            espejo.reiniciarElReloj()
            espejo.sincronizarTransporte(buffereando = false, reproduciendo = false, quiereReproducir = false)
            onPlayerReady(null)
            onTextureViewReady(null)
            onPrimeraImagen(false)
        }
    }

    // Sondeo de posición + el mismo rescate escalonado de MagisExoPlayer (ver su KDoc extenso):
    // el proxy local puede cortar una conexión a mitad de segmento (cambio de red, CDN caído) y
    // dejar a ExoPlayer con el reloj corriendo libre sin un solo frame nuevo. Acá el rescate es
    // el ÚNICO mecanismo de recuperación silenciosa entre tropiezos leves y el aviso explícito de
    // `onError` → `reabrirVivoPorCorte()` (que sí re-resuelve la sesión completa contra el
    // gateway) -- por eso vale la pena mantenerlo también en vivo, aunque el motivo original (el
    // MPEG-TS de Magis) no aplique acá.
    LaunchedEffect(exoPlayer) {
        var lastPos = -1L
        var lastFrames = -1L
        var congeladoDesdeMs = 0L
        var ultimoRescateMs = 0L

        while (true) {
            delay(500)
            val pos = exoPlayer.currentPosition
            val dur = exoPlayer.duration
            val playing = exoPlayer.isPlaying
            val state = exoPlayer.playbackState
            val frames = exoPlayer.videoDecoderCounters?.renderedOutputBufferCount?.toLong() ?: -1L

            val relojAvanzo = lastPos >= 0 && pos > lastPos
            val sinFrames = lastFrames >= 0 && frames == lastFrames
            val ahora = SystemClock.elapsedRealtime()

            if (playing && state == Player.STATE_READY && relojAvanzo && sinFrames && frames >= 0) {
                if (congeladoDesdeMs == 0L) {
                    congeladoDesdeMs = ahora
                    Log.w(TAG, "VIDEO SIN FRAMES · empieza · pos=${pos}ms frames=$frames")
                }
                val congeladoMs = ahora - congeladoDesdeMs
                if (congeladoMs >= 5_000 && ahora - ultimoRescateMs >= 8_000) {
                    ultimoRescateMs = ahora
                    congeladoDesdeMs = 0L
                    Log.w(TAG, "VIDEO CONGELADO ${congeladoMs}ms · prepare() en $pos")
                    exoPlayer.prepare()
                    exoPlayer.playWhenReady = true
                }
            } else {
                if (congeladoDesdeMs != 0L) {
                    Log.i(TAG, "VIDEO SIN FRAMES · se recuperó tras ${ahora - congeladoDesdeMs}ms · frames=$frames")
                }
                congeladoDesdeMs = 0L
            }

            lastPos = pos
            lastFrames = frames

            espejo.leyoElReloj(
                posicionMs = pos,
                duracionMs = if (dur > 0) dur else 0L,
            )
        }
    }

    // Mismo esquema de letterbox que MagisExoPlayer: el ratio se aplica transformando el
    // CONTENIDO del TextureView (que siempre ocupa la pantalla entera), no el tamaño de la vista
    // -- ver el KDoc de [ajustarAlAspecto] para el porqué (evita la franja verde del Fire Stick).
    BoxWithConstraints(
        Modifier.fillMaxSize().background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { textureView },
            update = { it.ajustarAlAspecto(videoAspectRatio, zoom) },
        )

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
    }
}
