package com.arkiv.player.ui.player

import android.net.Uri
import android.view.SurfaceView
import android.view.TextureView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.drm.DefaultDrmSessionManager
import androidx.media3.exoplayer.drm.FrameworkMediaDrm
import androidx.media3.exoplayer.drm.HttpMediaDrmCallback
import kotlinx.coroutines.delay

/**
 * Reproduce un stream MPEG-DASH con Widevine DRM usando ExoPlayer.
 * Solo para Ditu (Caracol Streaming): VLC no soporta Widevine.
 *
 * Alimenta [espejo] con posición/duración/buffering para que los controles de
 * Arkiv funcionen igual que con VLC. Expone el [ExoPlayer] vía [onPlayerReady]
 * para que PlayerScreen lo use como `activePlayer` (play/pause/seek).
 *
 * Usa [SurfaceView] y NO TextureView, a diferencia del resto de reproductores. Widevine entrega
 * los buffers marcados como protegidos, y un buffer protegido no se puede convertir en textura:
 * hwui lo rechaza al validarlo y ABORTA el proceso entero desde el RenderThread —la app se cierra
 * de golpe, sin excepción de Kotlin que capturar (`Invalid GrBackendTexture … protected==1`).
 * SurfaceView sí admite la ruta segura.
 *
 * El precio es que Ditu se queda sin miniaturas de frame: [onTextureViewReady] recibe null a
 * propósito. No es una limitación que se pueda sortear —capturar la imagen es exactamente lo que
 * el DRM existe para impedir— y el reproductor de VLC ya se comporta así con lo que no puede
 * capturar. Ver `capturarFrame`, que trata el null como "esta fuente no da imagen".
 */
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
internal fun DituExoPlayer(
    mediaUrl: String,
    licenseUrl: String,
    licenseHeaders: Map<String, String> = emptyMap(),
    espejo: EspejoDelPlayer,
    startPositionMs: Long = 0L,
    onPlayerReady: (Player?) -> Unit = {},
    onTextureViewReady: (TextureView?) -> Unit = {},
    onError: (String) -> Unit = {},
) {
    val context = LocalContext.current

    val exoPlayer = remember(mediaUrl, licenseUrl) {
        val httpFactory = DefaultHttpDataSource.Factory()
            .setUserAgent("okhttp/4.12.0")
            .setDefaultRequestProperties(mapOf("restful" to "yes"))

        val drmCallback = HttpMediaDrmCallback(licenseUrl, httpFactory).also { cb ->
            licenseHeaders.forEach { (k, v) -> cb.setKeyRequestProperty(k, v) }
        }
        val drmManager = DefaultDrmSessionManager.Builder()
            .setUuidAndExoMediaDrmProvider(C.WIDEVINE_UUID, FrameworkMediaDrm.DEFAULT_PROVIDER)
            .build(drmCallback)

        val mediaSource = DashMediaSource.Factory(httpFactory)
            .setDrmSessionManagerProvider { drmManager }
            .createMediaSource(MediaItem.fromUri(Uri.parse(mediaUrl)))

        ExoPlayer.Builder(context)
            .build()
            .also { player ->
                player.setMediaSource(mediaSource)
                player.prepare()
                if (startPositionMs > 0L) player.seekTo(startPositionMs)
                // NO arranca acá: espera a tener superficie. Ver [surfaceView] — en el Fire Stick
                // tarda 8 s en crearse y ExoPlayer, que no la espera, soltaba el audio sobre una
                // pantalla negra todo ese rato.
                player.playWhenReady = false
            }
    }

    // El arranque queda esperando a la superficie; el respaldo de abajo lo suelta igual si no llega.
    var arranquePendiente by remember(exoPlayer) { mutableStateOf(true) }

    val surfaceView = remember(exoPlayer) {
        SurfaceView(context).also { sv ->
            // La superficie es lo único que decide si HAY video: sin ella el renderer se queda
            // deshabilitado y ExoPlayer ni crea el decodificador —se ve el síntoma de "suena pero
            // no hay imagen"—, así que se sigue su ciclo de vida a mano para poder verlo en el log.
            sv.holder.addCallback(object : android.view.SurfaceHolder.Callback {
                override fun surfaceCreated(h: android.view.SurfaceHolder) {
                    android.util.Log.i("DituExo", "surfaceCreated · valida=${h.surface?.isValid} → arranco")
                    arranquePendiente = false
                    exoPlayer.playWhenReady = true
                }
                override fun surfaceChanged(h: android.view.SurfaceHolder, f: Int, w: Int, alto: Int) {
                    android.util.Log.i("DituExo", "surfaceChanged · ${w}x${alto}")
                }
                override fun surfaceDestroyed(h: android.view.SurfaceHolder) {
                    android.util.Log.w("DituExo", "surfaceDestroyed")
                }
            })
        }
    }
    // Relación de aspecto del video: 0 = desconocida todavía (spinner / inicio).
    var videoAspectRatio by remember(exoPlayer) { mutableFloatStateOf(0f) }

    DisposableEffect(exoPlayer) {
        exoPlayer.setVideoSurfaceView(surfaceView)
        android.util.Log.i("DituExo", "enganchado el SurfaceView · state=${exoPlayer.playbackState}")
        onPlayerReady(exoPlayer)
        onTextureViewReady(null)
        espejo.sincronizarTransporte(
            buffereando = exoPlayer.playbackState == Player.STATE_BUFFERING,
            reproduciendo = exoPlayer.isPlaying,
            // La INTENCIÓN es reproducir, aunque el playWhenReady todavía esté en false esperando
            // la superficie: pasarle ese false a la barra le haría mostrar el botón de play, como
            // si lo hubieras pausado tú, durante los segundos que tarda en aparecer la imagen.
            quiereReproducir = true,
        )

        val listener = object : Player.Listener {
            override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
                val v = tracks.groups.filter { it.type == C.TRACK_TYPE_VIDEO }
                val a = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
                android.util.Log.i("DituExo", "onTracksChanged · video=${v.size} audio=${a.size}")
                v.forEach { g ->
                    for (i in 0 until g.length) {
                        val f = g.getTrackFormat(i)
                        android.util.Log.i("DituExo", "  video[$i] ${f.width}x${f.height} codec=${f.sampleMimeType} soportado=${g.isTrackSupported(i)} elegido=${g.isTrackSelected(i)}")
                    }
                }
            }

            override fun onRenderedFirstFrame() {
                android.util.Log.i("DituExo", "onRenderedFirstFrame · pos=${exoPlayer.currentPosition}ms")
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                android.util.Log.i("DituExo", "onVideoSizeChanged · ${videoSize.width}x${videoSize.height}")
                if (videoSize.height > 0) {
                    videoAspectRatio = videoSize.width.toFloat() *
                        videoSize.pixelWidthHeightRatio / videoSize.height.toFloat()
                }
            }
            override fun onPlaybackStateChanged(state: Int) {
                android.util.Log.i("DituExo", "state=$state isPlaying=${exoPlayer.isPlaying} pos=${exoPlayer.currentPosition}ms")
                espejo.cambioElBuffering(state == Player.STATE_BUFFERING)
            }
            override fun onIsPlayingChanged(playing: Boolean) {
                espejo.cambioElPlaying(playing)
            }
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                espejo.cambioLaIntencion(playWhenReady)
            }
            override fun onPlayerError(error: PlaybackException) {
                val msg = error.message ?: "Error de reproducción (${error.errorCode})"
                android.util.Log.e("DituExo", "ExoPlayer error: $msg", error)
                onError(msg)
            }
        }
        exoPlayer.addListener(listener)

        onDispose {
            exoPlayer.removeListener(listener)
            exoPlayer.clearVideoSurfaceView(surfaceView)
            exoPlayer.release()
            espejo.reiniciarElReloj()
            espejo.sincronizarTransporte(buffereando = false, reproduciendo = false, quiereReproducir = false)
            onPlayerReady(null)
            onTextureViewReady(null)
        }
    }

    // RESPALDO del arranque. Esperar la superficie evita el audio sobre pantalla negra, pero deja
    // la reproducción atada a un callback: si no llegara, no sonaría nada nunca. A los 10 s se
    // arranca igual —con el defecto viejo, que al menos reproduce— antes que quedarse mudo.
    // Medido en el Fire Stick: la superficie tardó 8,2 s la primera vez y 2,2 s la segunda.
    LaunchedEffect(exoPlayer) {
        delay(10_000)
        if (arranquePendiente) {
            android.util.Log.w("DituExo", "la superficie no llegó en 10s · arranco igual")
            arranquePendiente = false
            exoPlayer.playWhenReady = true
        }
    }

    LaunchedEffect(exoPlayer) {
        while (true) {
            delay(500)
            val dur = exoPlayer.duration
            espejo.leyoElReloj(
                posicionMs = exoPlayer.currentPosition,
                duracionMs = if (dur > 0) dur else 0L,
            )
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        AndroidView(
            modifier = if (videoAspectRatio > 0f)
                Modifier.aspectRatio(videoAspectRatio)
            else
                Modifier.fillMaxSize(),
            factory = { surfaceView },
        )
    }
}
