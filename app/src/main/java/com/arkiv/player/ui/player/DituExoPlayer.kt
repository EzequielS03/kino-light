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
                player.playWhenReady = true
            }
    }

    val surfaceView = remember(exoPlayer) { SurfaceView(context) }
    // Relación de aspecto del video: 0 = desconocida todavía (spinner / inicio).
    var videoAspectRatio by remember(exoPlayer) { mutableFloatStateOf(0f) }

    DisposableEffect(exoPlayer) {
        exoPlayer.setVideoSurfaceView(surfaceView)
        onPlayerReady(exoPlayer)
        onTextureViewReady(null)
        espejo.sincronizarTransporte(
            buffereando = exoPlayer.playbackState == Player.STATE_BUFFERING,
            reproduciendo = exoPlayer.isPlaying,
            quiereReproducir = exoPlayer.playWhenReady,
        )

        val listener = object : Player.Listener {
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (videoSize.height > 0) {
                    videoAspectRatio = videoSize.width.toFloat() *
                        videoSize.pixelWidthHeightRatio / videoSize.height.toFloat()
                }
            }
            override fun onPlaybackStateChanged(state: Int) {
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
