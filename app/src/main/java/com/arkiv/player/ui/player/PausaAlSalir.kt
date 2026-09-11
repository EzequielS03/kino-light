package com.arkiv.player.ui.player

/**
 * ¿Hay que pausar lo que suena cuando la app se va al fondo (Home, otra app)?
 *
 * Los ExoPlayer —Magis, el vivo y Caracol— se componen DENTRO de `PlayerScreen`: con Home la
 * pantalla no se destruye, así que seguían sonando afuera. A downloaded file is different: it plays
 * on the ExoPlayer hosted by `PlaybackService`, reached through the screen's `controller`, and on
 * the phone it keeps playing in the background on purpose (with the media notification).
 *
 * - Enviando a un Chromecast, no: lo que se ve está en la tele.
 * - On the TV, yes, everything, the local player included.
 * - On the phone, only the in-screen ExoPlayers; the local (service) player keeps playing.
 */
internal fun hayQuePausarAlSalir(esTv: Boolean, esExoPlayer: Boolean, casteando: Boolean): Boolean = when {
    casteando -> false
    esTv -> true
    else -> esExoPlayer
}

/** Qué se hace con lo que suena cuando la app se va al fondo. Ver [alIrseAlFondo]. */
internal enum class AlIrseAlFondo {
    /** Keeps playing: the local (service) player on the phone, or casting to a Chromecast. */
    SEGUIR,

    /** Se pausa donde va. Al volver queda en pausa: decide la persona. */
    PAUSAR,

    /**
     * Un canal en vivo en ExoPlayer: se pausa y se detiene (`stop()`), y al volver se prepara otra
     * vez en el directo, no donde quedó; sigue sonando solo si sonaba (ver [alVolverAlDirecto]).
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
 * [esExoPlayer] means what is playing is not the `controller` (the service-hosted local player).
 */
internal fun alIrseAlFondo(esTv: Boolean, esExoPlayer: Boolean, casteando: Boolean, enVivo: Boolean): AlIrseAlFondo =
    when {
        !hayQuePausarAlSalir(esTv, esExoPlayer, casteando) -> AlIrseAlFondo.SEGUIR
        enVivo && esExoPlayer -> AlIrseAlFondo.DETENER_EL_DIRECTO
        else -> AlIrseAlFondo.PAUSAR
    }

/** Qué se hace al volver con el directo que se detuvo al irse al fondo. Ver [alVolverAlDirecto]. */
internal enum class AlVolverAlDirecto {
    /** Sonaba al salir: se prepara en el borde del directo y vuelve a sonar. */
    REANUDAR_EN_EL_DIRECTO,

    /** Estaba en pausa al salir: se prepara en el borde del directo y sigue en pausa. */
    SEGUIR_EN_PAUSA,
}

/**
 * [sonabaAlSalir] es la intención de reproducir (`playWhenReady`) leída ANTES de pausarlo para
 * irse: si la persona lo había pausado, al volver sigue en pausa. Vale igual para el vivo de Magis y
 * para el de Caracol.
 */
internal fun alVolverAlDirecto(sonabaAlSalir: Boolean): AlVolverAlDirecto =
    if (sonabaAlSalir) AlVolverAlDirecto.REANUDAR_EN_EL_DIRECTO else AlVolverAlDirecto.SEGUIR_EN_PAUSA
