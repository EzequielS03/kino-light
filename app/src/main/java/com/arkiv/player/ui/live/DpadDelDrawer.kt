package com.arkiv.player.ui.live

import android.view.KeyEvent

/** Dónde está el foco dentro del cajón. */
enum class FocoDelDrawer {
    CATEGORIAS,
    CANALES,

    /**
     * El teclado del buscador. Es su propio estado y no una variante de [CATEGORIAS] porque
     * cambia el significado de las flechas: un teclado en pantalla se recorre con las cuatro,
     * así que mientras está abierto ninguna puede seguir queriendo decir "cambiar de columna"
     * ni "cerrar" — escribir "RCN" cerraría el cajón en la primera letra.
     */
    TECLADO,
}

/** Qué hay que hacer con una tecla, decidido antes de tocar nada de la interfaz. */
enum class AccionDelDrawer {
    /** Abrir el cajón (y no dejar que la tecla llegue al reproductor). */
    ABRIR,

    /** Cerrarlo y devolver el foco al video. */
    CERRAR,

    /** Mover el foco a la columna de categorías. */
    A_CATEGORIAS,

    /** Mover el foco a la lista de canales. */
    A_CANALES,

    /**
     * La tecla es de la lista que tiene el foco: la resuelve Compose (recorrer con arriba/abajo,
     * elegir con OK). Se CONSUME igual, para que no siga bajando al reproductor.
     */
    DE_LA_LISTA,

    /** No es del cajón: que la maneje quien está debajo (en vivo, el zapping del reproductor). */
    PASAR,
}

/**
 * Qué hace cada flecha cuando el cajón de canales está en juego, mientras el vivo se reproduce.
 *
 * Vive acá y no dentro del `setOnKeyListener` de [com.arkiv.player.ui.player.PlayerScreen] porque
 * el proyecto no tiene tests de interfaz (no hay `androidTest`): una regla de navegación escrita
 * ahí adentro no se puede probar de ninguna forma, y es justo el tipo de regla que se rompe
 * callada al retocar la pantalla meses después.
 *
 * Lo que hace que esto NO sea trivial es que en vivo arriba y abajo ya están tomadas: zapean
 * ([PlayerScreen] llama a `zapAnterior`/`zapSiguiente`). Con el cajón abierto tienen que recorrer
 * la lista y NO zapear -- si se dejaran pasar, la lista no se movería y el canal cambiaría solo.
 * Por eso [AccionDelDrawer.DE_LA_LISTA] consume la tecla en vez de devolverla.
 */
object DpadDelDrawer {

    fun accion(tecla: Int, abierto: Boolean, foco: FocoDelDrawer): AccionDelDrawer {
        if (!abierto) {
            // Cerrado, la ÚNICA tecla que le pertenece es izquierda. Cualquier otra sigue
            // haciendo lo de siempre -- sobre todo arriba y abajo, que zapean (y Atrás, que sale
            // del reproductor: quedárselo dejaría a la persona encerrada en el canal).
            return if (tecla == KeyEvent.KEYCODE_DPAD_LEFT) AccionDelDrawer.ABRIR else AccionDelDrawer.PASAR
        }
        // Con el teclado abierto las cuatro flechas son suyas; solo Atrás lo cierra a él (no al
        // cajón entero, que sería perder la búsqueda recién tipeada por querer corregirla).
        if (foco == FocoDelDrawer.TECLADO) {
            return if (tecla == KeyEvent.KEYCODE_BACK) AccionDelDrawer.A_CATEGORIAS
            else AccionDelDrawer.DE_LA_LISTA
        }
        return when (tecla) {
            KeyEvent.KEYCODE_BACK -> AccionDelDrawer.CERRAR
            // Derecha cierra cuando ya no queda nada a la derecha. Desde las categorías todavía
            // hay lista de canales, y saltearla obligaría a cruzar el cajón entero de vuelta.
            KeyEvent.KEYCODE_DPAD_RIGHT ->
                if (foco == FocoDelDrawer.CANALES) AccionDelDrawer.CERRAR else AccionDelDrawer.A_CANALES
            // Y en espejo: desde la columna de más a la izquierda no hay a dónde ir, así que
            // izquierda también es una salida.
            KeyEvent.KEYCODE_DPAD_LEFT ->
                if (foco == FocoDelDrawer.CATEGORIAS) AccionDelDrawer.CERRAR else AccionDelDrawer.A_CATEGORIAS
            else -> AccionDelDrawer.DE_LA_LISTA
        }
    }
}
