package com.arkiv.player.ui.player

import android.net.Uri
import android.os.SystemClock
import android.util.Log
import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.drm.DefaultDrmSessionManager
import androidx.media3.exoplayer.drm.FrameworkMediaDrm
import androidx.media3.exoplayer.drm.HttpMediaDrmCallback
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay

private const val TAG = "DituExo"

/**
 * ¿Este error se arregla volviendo a preparar el mismo stream?
 *
 * Es el mismo criterio que usa el reproductor de Caracol de la app completa (rama `main`, commit
 * 7caf2abc): errores de red, de contenedor y `BEHIND_LIVE_WINDOW`. Los de DRM y de decodificador
 * quedan afuera porque contra la misma licencia y el mismo códec darían lo mismo.
 */
private fun esRecuperable(error: PlaybackException): Boolean =
    error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW ||
        error.errorCode in PlaybackException.ERROR_CODE_IO_UNSPECIFIED..PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE ||
        error.errorCode in PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED..PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED

/**
 * The Caracol player: MPEG-DASH with Widevine.
 *
 * ExoPlayer, same as [MagisExoPlayer] and [LiveExoPlayer], feeding the same [EspejoDelPlayer] they
 * do. libVLC never negotiated Widevine licenses, so Caracol was always going to need ExoPlayer even
 * before the rest of the app dropped VLC.
 *
 * The `DrmSessionManager` is built by hand, like `main`'s Caracol player, to be able to turn off
 * the DRM session's keepalive (see the comment next to `setSessionKeepaliveMs`): media3's
 * `DefaultDrmSessionManagerProvider` has no way to change that.
 *
 * [drmLicenseHeaders] isn't optional in practice: it carries the `playback_token` cookie that
 * `CONTENT/VIDEOURL` returned, which is what authorizes the license (see `DituResolve` and
 * `DituCliente`).
 *
 * There's no local proxy in the middle, unlike Magis: the headers Caracol requires are set by this
 * file's own `DefaultHttpDataSource`, and the SAME one is used for the manifest, the segments, and
 * the license request. It carries the `User-Agent` and `restful: yes` from `DituCliente.CABECERAS`
 * and, on top of that, [drmLicenseHeaders]: `main`'s player also sends the cookie to the manifest
 * and the segments, noting that without it the CDN returns HTML. It's copied here without having
 * measured it yet on this branch.
 *
 * The video goes on the `SurfaceView` that [PlayerView] uses by default, not on a `TextureView`
 * like [MagisExoPlayer]. On `main` it was measured that a Widevine-protected buffer can't be
 * painted on a `TextureView` (hwui aborts the process). The price is that Caracol loses frame
 * thumbnails: the screen passes `null` to `capturarFrame`, and `FrameCapturer.capturar` returns
 * `false` with a null `TextureView`.
 *
 * Ads aren't filtered. On a recoverable error (see [esRecuperable]) the stream is re-prepared
 * while [pedirRepreparado] allows it; otherwise, the error's `errorCode` goes to [onError], and
 * `PlayerScreen` asks the ViewModel for a new URL. The caps for the two tiers don't live here but
 * in `EstadoDeDitu`, which only resets them after stable playback: [onPosicion] passes it every
 * clock reading.
 *
 * Starts with the first frame, not before. It's prepared paused and
 * [ArranqueConLaPrimeraImagen] decides when to give it play: when the first frame is painted, or
 * after [ESPERA_MAXIMA_DE_LA_PRIMERA_IMAGEN_MS] without it, so it doesn't end up silent and
 * frozen. It used to start right away, and the audio could begin before the picture. Whether
 * ExoPlayer paints the first frame while paused hasn't been verified on a device on this branch:
 * if it didn't, what's left is that safety exit.
 *
 * [arrancarSolo] set to `false` builds it prepared and paused, without starting on its own: that's
 * the case of reloading something that was paused. [onError] hands over, along with the code,
 * whether this player wanted to play ([ArranqueConLaPrimeraImagen.queriaReproducir]), which is
 * what the reload inherits.
 */
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
internal fun DituExoPlayer(
    mediaUrl: String,
    drmLicenseUrl: String,
    drmLicenseHeaders: Map<String, String>,
    espejo: EspejoDelPlayer,
    startPositionMs: Long = 0L,
    arrancarSolo: Boolean = true,
    onPlayerReady: (Player?) -> Unit = {},
    onError: (codigo: Int, queriaReproducir: Boolean) -> Unit = { _, _ -> },
    pedirRepreparado: () -> Boolean,
    onPosicion: (posicionMs: Long, reproduciendo: Boolean) -> Unit = { _, _ -> },
    onTracksChanged: ((Tracks) -> Unit)? = null,
    onPrimeraImagen: (Boolean) -> Unit = {},
    zoom: Float = 1f,
) {
    val context = LocalContext.current

    // Los headers de licencia van en la clave: dos resoluciones del mismo episodio traen la misma
    // URL pero un `playback_token` nuevo, y el player viejo seguiría pidiendo con el vencido.
    val exoPlayer = remember(mediaUrl, drmLicenseUrl, drmLicenseHeaders) {
        Log.i(
            TAG,
            "Creating ExoPlayer DASH · url=${mediaUrl.take(80)} license=${drmLicenseUrl.take(60)} " +
                "headers=${drmLicenseHeaders.keys} startMs=$startPositionMs",
        )

        val httpFactory = DefaultHttpDataSource.Factory()
            // Los mismos valores que `DituCliente.CABECERAS`: sin ellos el CDN responde 403.
            .setUserAgent("okhttp/4.12.0")
            .setDefaultRequestProperties(mapOf("restful" to "yes") + drmLicenseHeaders)
            .setConnectTimeoutMs(30_000)
            .setReadTimeoutMs(30_000)

        val item = MediaItem.Builder()
            .setUri(Uri.parse(mediaUrl))
            .setMimeType(MimeTypes.APPLICATION_MPD)
            .build()

        // La licencia se pide con el MISMO `httpFactory`: sin él, el callback saldría por un
        // `DefaultHttpDataSource` propio, sin el `User-Agent` ni el `restful: yes` de arriba.
        val licencia = HttpMediaDrmCallback(drmLicenseUrl, httpFactory).also { cb ->
            drmLicenseHeaders.forEach { (k, v) -> cb.setKeyRequestProperty(k, v) }
        }
        val drmManager = DefaultDrmSessionManager.Builder()
            .setUuidAndExoMediaDrmProvider(C.WIDEVINE_UUID, FrameworkMediaDrm.DEFAULT_PROVIDER)
            // Portado del reproductor de Caracol de `main`, que lo midió en el Fire Stick el
            // 2026-08-22: ese aparato tiene UN SOLO camino de video seguro, y con la sesión DRM del
            // canal anterior todavía retenida, el decodificador seguro del canal nuevo tardó 14 s en
            // conseguir superficie (pantalla negra todo ese rato). Por defecto media3 retiene la
            // sesión 5 min después del último uso; C.TIME_UNSET apaga ese keepalive y la suelta
            // apenas queda sin usar. Acá pesa porque cada canal en vivo y cada recarga por token
            // vencido arman un reproductor nuevo (`key(dPlay)` en `PlayerScreen`). Sin probar en un
            // aparato en esta rama.
            .setSessionKeepaliveMs(C.TIME_UNSET)
            .build(licencia)

        ExoPlayer.Builder(context)
            .setMediaSourceFactory(
                DashMediaSource.Factory(httpFactory).setDrmSessionManagerProvider { drmManager },
            )
            .build()
            .also { player ->
                player.setMediaItem(item)
                player.prepare()
                if (startPositionMs > 0L) player.seekTo(startPositionMs)
                // En pausa: le da play [ArranqueConLaPrimeraImagen], con la primera imagen.
                player.playWhenReady = false
            }
    }

    // Uno por reproductor: una recarga (`key(dPlay)` en `PlayerScreen`) arma otro y vuelve a esperar,
    // salvo que lo que falló estuviera en pausa ([arrancarSolo]).
    val arranque = remember(exoPlayer) {
        ArranqueConLaPrimeraImagen(arrancaSolo = arrancarSolo).also { it.empezo(SystemClock.elapsedRealtime()) }
    }

    // Si la app se va al fondo mientras se espera la primera imagen, la espera queda en suspenso: la
    // salida de seguridad del reloj de abajo no mira si la app está a la vista, y podría darle play en
    // el fondo. Al volver se retoma con su plazo contado de nuevo, así que nunca queda esperando sin
    // plazo: arranca con la imagen o con la salida de seguridad. Con un canal en vivo es igual:
    // `PlayerScreen` lo detiene al irse y lo prepara en el directo al volver (ver `alVolverAlDirecto`),
    // y el arranque le da play. Lo que ya había arrancado no pasa por acá: eso lo decide
    // `alIrseAlFondo`.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, arranque) {
        val observador = LifecycleEventObserver { _, evento ->
            if (evento == Lifecycle.Event.ON_STOP && arranque.esperando) {
                Log.i(TAG, "the app went to background while waiting for the first frame: the wait is suspended")
                arranque.suspender()
            }
            if (evento == Lifecycle.Event.ON_START && arranque.suspendida) {
                Log.i(TAG, "the app came back: resuming the wait for the first frame")
                arranque.retomar(SystemClock.elapsedRealtime())
            }
        }
        lifecycleOwner.lifecycle.addObserver(observador)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observador) }
    }

    DisposableEffect(exoPlayer) {
        onPlayerReady(exoPlayer)
        espejo.sincronizarTransporte(
            buffereando = exoPlayer.playbackState == Player.STATE_BUFFERING,
            reproduciendo = exoPlayer.isPlaying,
            quiereReproducir = exoPlayer.playWhenReady,
        )

        val escucha = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                espejo.cambioElBuffering(state == Player.STATE_BUFFERING)
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                espejo.cambioElPlaying(isPlaying)
            }

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                // Si la intención cambia mientras se espera la imagen, no fue el arranque —que suelta
                // ANTES de llamar a `play()`, así que acá ya no está esperando—: fue alguien más, la
                // persona con play o pausa. Desde ahí el arranque no vuelve a tocar el reproductor.
                if (arranque.esperando) {
                    Log.i(TAG, "play/pause while waiting for the first frame (playWhenReady=$playWhenReady): the person decides")
                    arranque.laPersonaDecidio()
                }
                espejo.cambioLaIntencion(playWhenReady)
            }

            override fun onTracksChanged(tracks: Tracks) {
                onTracksChanged?.invoke(tracks)
            }

            override fun onRenderedFirstFrame() {
                Log.i(TAG, "onRenderedFirstFrame · pos=${exoPlayer.currentPosition}ms")
                onPrimeraImagen(true)
                // Con la imagen ya en pantalla: audio e imagen empiezan juntos.
                if (arranque.llegoLaImagen()) exoPlayer.play()
            }

            override fun onPlayerError(error: PlaybackException) {
                Log.w(TAG, "onPlayerError code=${error.errorCode} ${error.errorCodeName}", error)
                if (esRecuperable(error) && pedirRepreparado()) {
                    // Tras un error el player queda en IDLE: `prepare()` lo vuelve a arrancar desde
                    // la posición en que estaba. Quedarse atrás de la ventana de un directo es la
                    // excepción: ahí hay que volver al borde, porque esa posición ya no existe.
                    if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                        exoPlayer.seekToDefaultPosition()
                    }
                    exoPlayer.prepare()
                    return
                }
                onError(error.errorCode, arranque.queriaReproducir(exoPlayer.playWhenReady))
            }
        }
        exoPlayer.addListener(escucha)

        onDispose {
            Log.i(TAG, "onDispose · pos=${exoPlayer.currentPosition}ms")
            exoPlayer.removeListener(escucha)
            exoPlayer.release()
            espejo.reiniciarElReloj()
            espejo.sincronizarTransporte(buffereando = false, reproduciendo = false, quiereReproducir = false)
            onPlayerReady(null)
            onPrimeraImagen(false)
        }
    }

    // Posición y duración para la barra, igual que el sondeo de [MagisExoPlayer], y la misma
    // lectura para [onPosicion], que es de donde `EstadoDeDitu` sabe si la reproducción anda.
    LaunchedEffect(exoPlayer) {
        while (true) {
            delay(500)
            // La salida de seguridad del arranque: sin imagen a tiempo, arranca igual. Va en este
            // reloj porque ya mira cada medio segundo.
            if (arranque.vencio(SystemClock.elapsedRealtime())) {
                Log.w(TAG, "no first frame within ${ESPERA_MAXIMA_DE_LA_PRIMERA_IMAGEN_MS}ms: starting anyway")
                exoPlayer.play()
            }
            val dur = exoPlayer.duration
            val pos = exoPlayer.currentPosition
            espejo.leyoElReloj(
                posicionMs = pos,
                duracionMs = if (dur > 0) dur else 0L,
            )
            onPosicion(pos, exoPlayer.isPlaying)
        }
    }

    AndroidView(
        modifier = Modifier.fillMaxSize().background(Color.Black),
        factory = { ctx ->
            PlayerView(ctx).apply {
                useController = false
                // The screen's own video view handles the D-pad keys and requests focus for that:
                // this view doesn't need to take it away.
                isFocusable = false
                isFocusableInTouchMode = false
                descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
            }
        },
        // En `update` y no en `factory`: si cambia el episodio se crea otro ExoPlayer, pero la
        // vista es la misma, y sin esto seguiría apuntando al que ya se liberó.
        update = { vista ->
            vista.player = exoPlayer
            // El zoom de la pantalla, escalando la vista entera. Sin probar en aparato.
            vista.scaleX = zoom
            vista.scaleY = zoom
        },
        onRelease = { vista -> vista.player = null },
    )
}
