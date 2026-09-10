package com.arkiv.player.ui.player

/**
 * ¿Hay que pausar lo que suena cuando la app se va al fondo (Home, otra app)?
 *
 * Los ExoPlayer —Magis, el vivo y Caracol— se componen DENTRO de `PlayerScreen`: con Home la
 * pantalla no se destruye, así que seguían sonando afuera. VLC es otra cosa: suena en
 * `PlaybackService`, y en el celular que siga sonando afuera es a propósito.
 *
 * - Enviando a un Chromecast, no: lo que se ve está en la tele.
 * - En el televisor, sí, todo, VLC incluido.
 * - En el celular, solo los ExoPlayer; VLC sigue sonando.
 */
internal fun hayQuePausarAlSalir(esTv: Boolean, esExoPlayer: Boolean, casteando: Boolean): Boolean = when {
    casteando -> false
    esTv -> true
    else -> esExoPlayer
}

/** Qué se hace con lo que suena cuando la app se va al fondo. Ver [alIrseAlFondo]. */
internal enum class AlIrseAlFondo {
    /** Sigue sonando: VLC en el celular, o enviando a un Chromecast. */
    SEGUIR,

    /** Se pausa donde va. Al volver queda en pausa: decide la persona. */
    PAUSAR,

    /**
     * Un canal en vivo en ExoPlayer: se pausa y se detiene (`stop()`), y al volver arranca otra vez
     * en el directo, no donde quedó.
     *
     * Detenido y no solo pausado porque, pausado, el reproductor sigue armado y puede fallar en el
     * fondo. El error de `LiveExoPlayer` va a `reabrirVivoPorCorte`, que gasta una de sus reaperturas y
     * vuelve a abrir el canal: `abrirCanalActual` publica un `liveItem` nuevo. En el fondo no se arma
     * nada, porque `PlayerScreen` lee `liveItem` con `collectAsStateWithLifecycle`; lo recoge al volver,
     * y ese `LiveExoPlayer` nuevo prepara con `playWhenReady = true`: arrancaría a sonar al volver
     * aunque la persona lo hubiera pausado. Detenido no baja nada ni tiene de qué fallar.
     */
    DETENER_EL_DIRECTO,
}

/**
 * [hayQuePausarAlSalir], más cómo: un video se pausa y un canal en vivo en ExoPlayer se detiene.
 * [esExoPlayer] es que lo que suena no sea el `controller` de VLC.
 */
internal fun alIrseAlFondo(esTv: Boolean, esExoPlayer: Boolean, casteando: Boolean, enVivo: Boolean): AlIrseAlFondo =
    when {
        !hayQuePausarAlSalir(esTv, esExoPlayer, casteando) -> AlIrseAlFondo.SEGUIR
        enVivo && esExoPlayer -> AlIrseAlFondo.DETENER_EL_DIRECTO
        else -> AlIrseAlFondo.PAUSAR
    }
