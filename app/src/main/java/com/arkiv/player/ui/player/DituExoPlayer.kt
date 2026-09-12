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
 * El reproductor de Caracol: MPEG-DASH con Widevine.
 *
 * ExoPlayer, same as [MagisExoPlayer] and [LiveExoPlayer], feeding the same [EspejoDelPlayer] they
 * do. libVLC never negotiated Widevine licenses, so Caracol was always going to need ExoPlayer even
 * before the rest of the app dropped VLC.
 *
 * El `DrmSessionManager` se arma a mano, como en el reproductor de Caracol de `main`, para poder
 * apagar el keepalive de la sesión DRM (ver el comentario junto a `setSessionKeepaliveMs`): el
 * `DefaultDrmSessionManagerProvider` de media3 no tiene cómo cambiarlo.
 *
 * [drmLicenseHeaders] no es opcional en la práctica: trae la cookie `playback_token` que devolvió
 * `CONTENT/VIDEOURL`, que es lo que autoriza la licencia (ver `DituResolve` y `DituCliente`).
 *
 * No hay proxy local de por medio, a diferencia de Magis: los headers que pide Caracol los pone el
 * propio `DefaultHttpDataSource` de acá, y el MISMO se usa para el manifiesto, los segmentos y la
 * petición de licencia. Lleva el `User-Agent` y el `restful: yes` de `DituCliente.CABECERAS` y,
 * además, [drmLicenseHeaders]: el reproductor de `main` manda la cookie también al manifiesto y a
 * los segmentos, con la nota de que sin ella el CDN devuelve HTML. Acá se copia sin haberlo medido
 * todavía en esta rama.
 *
 * El video va en el `SurfaceView` que [PlayerView] usa por defecto, no en un `TextureView` como
 * [MagisExoPlayer]. En `main` se midió que un buffer protegido por Widevine no se puede pintar en
 * un `TextureView` (hwui aborta el proceso). El precio es que Caracol se queda sin miniaturas de
 * frame: la pantalla le pasa `null` a `capturarFrame`, y `FrameCapturer.capturar` devuelve `false`
 * con un `TextureView` nulo.
 *
 * La publicidad no se filtra. Ante un error recuperable (ver [esRecuperable]) se vuelve a preparar
 * el stream mientras [pedirRepreparado] lo permita; si no, el `errorCode` del error va a [onError], y
 * `PlayerScreen` le pide al ViewModel una URL nueva. Los topes de los dos escalones no viven acá sino en
 * `EstadoDeDitu`, que los repone recién después de reproducción estable: [onPosicion] le pasa cada
 * lectura del reloj.
 *
 * Arranca con la primera imagen, no antes. Se prepara en pausa y [ArranqueConLaPrimeraImagen] decide
 * cuándo darle play: cuando se pinta la primera imagen, o pasada
 * [ESPERA_MAXIMA_DE_LA_PRIMERA_IMAGEN_MS] sin ella, para no quedar mudo y colgado. Antes arrancaba de
 * una, y el audio podía empezar antes que la imagen. Que ExoPlayer pinte la primera imagen estando en
 * pausa no está probado en un aparato en esta rama: si no la pintara, lo que queda es esa salida de
 * seguridad.
 *
 * [arrancarSolo] en `false` lo arma preparado en pausa, sin que arranque solo: es el de una recarga de
 * algo que estaba en pausa. [onError] entrega, junto con el código, si este reproductor quería
 * reproducir ([ArranqueConLaPrimeraImagen.queriaReproducir]), que es lo que hereda la recarga.
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
            "Creando ExoPlayer DASH · url=${mediaUrl.take(80)} licencia=${drmLicenseUrl.take(60)} " +
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
                Log.i(TAG, "la app se fue al fondo esperando la primera imagen: la espera queda en suspenso")
                arranque.suspender()
            }
            if (evento == Lifecycle.Event.ON_START && arranque.suspendida) {
                Log.i(TAG, "la app volvió: se retoma la espera de la primera imagen")
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
                    Log.i(TAG, "play/pausa esperando la primera imagen (playWhenReady=$playWhenReady): decide la persona")
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
                Log.w(TAG, "sin primera imagen en ${ESPERA_MAXIMA_DE_LA_PRIMERA_IMAGEN_MS}ms: arranco igual")
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
