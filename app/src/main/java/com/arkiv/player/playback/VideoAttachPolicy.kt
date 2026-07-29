package com.arkiv.player.playback

/**
 * Re-engancha el video de libVLC al volver de segundo plano.
 *
 * Al salir de la app, Android DESTRUYE la Surface del SurfaceView y libVLC tumba su salida de video
 * (se ve un evento `Vout 0` en el log). Al volver se crea una Surface nueva, pero si nadie vuelve a
 * llamar `attachViews` la salida NUNCA se reconstruye: queda la pantalla NEGRA con el audio sonando
 * (el VlcPlayer vive en el PlaybackService, así que la reproducción sigue sin la UI).
 *
 * Es el patrón del sample oficial de libVLC (attach en onStart / detach en onStop) con una
 * diferencia: acá NO se para el player, porque el audio debe seguir en segundo plano.
 *
 * Solo re-engancha si hubo un ON_STOP previo: al entrar a la pantalla el layout ya se enganchó al
 * construirse, y el Lifecycle despacha un ON_START al registrar el observador — sin este guardián
 * ese ON_START tumbaría y rehacía el vout recién creado.
 */
class VideoAttachPolicy(
    private val attach: () -> Unit,
    private val detach: () -> Unit,
) {
    private var detached = false

    /** La app se fue al fondo: soltar el video (el audio sigue). */
    fun onStop() {
        if (detached) return
        detached = true
        detach()
    }

    /** La app volvió al frente: reconstruir la salida de video si la habíamos soltado. */
    fun onStart() {
        if (!detached) return
        detached = false
        attach()
    }
}
