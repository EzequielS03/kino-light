package com.arkiv.player.playback

/**
 * Si VLC está pintando video, según lo que la app puede saber.
 *
 * Vive aparte del reproductor para poder probarse: es la regla que decide si mostrás el spinner de
 * "esperando video" o te quedás mirando un negro sin explicación.
 *
 * La sutileza está en que **no se puede confiar solo en los eventos de VLC**. Cuando se suelta la
 * superficie (`detachViews`), la salida de video deja de existir, pero libVLC 3.x no manda ningún
 * `Vout 0` para avisarlo — verificado en el Fire Stick: se ve el `Vout 0 -> 1` al arrancar y después
 * nada, ni al soltar ni al reenganchar. Un contador alimentado solo por eventos queda clavado en 1
 * para siempre y la app cree que hay imagen cuando la pantalla está negra.
 */
class VoutTracker {

    @Volatile private var salidas = 0

    /** Si alguna vez hubo imagen. Es lo que distingue el arranque en frío (VLC crea el vout solo)
     * del reenganche tras perder la superficie (no lo crea, hay que forzarlo). */
    @Volatile private var huboVideo = false

    fun onVout(n: Int) {
        salidas = n
        if (n > 0) huboVideo = true
    }

    /** Soltar la ventana ES quedarse sin salida de video, lo diga VLC o no. */
    fun onDetach() { salidas = 0 }

    fun hayVideo(): Boolean = salidas > 0

    /**
     * ¿Hay que forzar a VLC a reconstruir el vout? Solo cuando ya hubo imagen y ahora no hay salida:
     * ahí libVLC se queda convencido de que su vout sigue vivo y no lo rehace para la superficie
     * nueva. En el arranque en frío devuelve false a propósito — no hay nada que reconstruir todavía,
     * y el empujón (apagar y prender la pista de video) se vería como un parpadeo gratuito.
     */
    fun necesitaEmpujon(): Boolean = huboVideo && salidas == 0
}
