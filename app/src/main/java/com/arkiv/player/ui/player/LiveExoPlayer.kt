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
import androidx.compose.runtime.key
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
 * Plays the Magis live channel using ExoPlayer (Task 1, light-magis pruning -- the VLC gate).
 * Replaced VLC as the live player; see the KDoc of
 * [com.arkiv.player.ui.player.PlayerViewModel.abrirCanalActual] and the task brief for why.
 *
 * [mediaUrl] already arrives served by [com.arkiv.player.playback.LiveHlsProxy] on `127.0.0.1`,
 * with the CDN's `Content-Auth`/`Content-License` injected by the proxy on every request --
 * ExoPlayer downloads it as plain HTTP, the same way [MagisExoPlayer] does with the VOD proxy. No
 * need to pass it its own headers: the proxy exists precisely because VLC couldn't send those
 * headers, and ExoPlayer doesn't need them either since it never sees them -- the proxy sets them.
 *
 * Unlike [MagisExoPlayer]:
 * - No `startPositionMs`/resume: a live feed has no "where you were".
 * - No subtitles: the portal doesn't send any for the live feed.
 * - No custom [LoadControl]: the problem that motivated Magis's (a single giant TS with 8 badly
 *   interleaved audio tracks) doesn't exist here -- the proxy serves a properly segmented HLS, so
 *   ExoPlayer's defaults (built for live) are enough.
 * - `onError` must not show an overlay: [PlayerViewModel.onLiveExoError] sends it to
 *   `reabrirVivoPorCorte()`, which retries on its own -- a live channel recovers almost always in
 *   a few seconds (see its KDoc), and showing an error on the first hiccup would over-alarm.
 */
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
internal fun LiveExoPlayer(
    mediaUrl: String,
    /**
     * Forces the ExoPlayer to be recreated even when [mediaUrl] doesn't change.
     *
     * [com.arkiv.player.playback.LiveHlsProxy.urlPara] ALWAYS returns the same URL
     * (`http://127.0.0.1:<port>/live.m3u8?t=<token>`) for the whole life of the proxy: the active
     * channel is decided by the proxy behind closed doors (its `sesion` field), not by the URL.
     * Switching to another channel, or reopening the same one after a cut, does NOT change
     * `mediaUrl` -- it only changes which playlist the proxy answers on that same route. Without
     * this key, `remember` would see the same URL and never recreate/re-prepare the player: the
     * screen would stay frozen on the old channel. Passing `liveItem.episodeId` (which does change
     * per channel) combined with `generacionVivo` (which goes up on every reopen, including of the
     * SAME channel after a cut -- see its KDoc in PlayerViewModel) covers both cases. It's the
     * ExoPlayer equivalent of what `controller.setMediaItems(...)+prepare()` used to achieve on the
     * old VLC player, forced by that same key.
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
        // `key(exoPlayer)`: sin esto, al zapear `textureView` cambia (está `remember(exoPlayer)`
        // arriba) pero `AndroidView` NO vuelve a llamar a `factory` -- Compose solo lo invoca una
        // vez por posición en el árbol, así que se queda mostrando la vista vieja (con el último
        // frame del canal anterior) para siempre, mientras el audio sí sigue al ExoPlayer nuevo
        // porque no depende de ninguna vista. Envolver en `key` fuerza a Compose a tratarlo como
        // un nodo nuevo en cada zapeo, y ahí sí vuelve a llamar `factory` con el `textureView`
        // recién creado.
        key(exoPlayer) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { textureView },
                update = { it.ajustarAlAspecto(videoAspectRatio, zoom) },
            )
        }

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
