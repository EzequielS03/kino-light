package com.arkiv.player.ui.player

/**
 * Qué hace el botón "Saltar outro" cuando se pulsa —y, por lo tanto, si vale la pena dibujarlo.
 *
 * El botón hacía siempre `seekToNextMediaItem()`, que solo sirve si la playlist tiene más de un
 * ítem. Y **archive es la única fuente multi-ítem**: magis, web, torrent, local y la NUC publican
 * `PlaylistData(listOf(item), …)`. Mientras el botón exigía un marcador horneado en `PlayerData`
 * —que solo horneaba `loadArchive`— eso no se notaba; al pasar a leer los marcadores de Room el
 * botón empezó a salir en todas las fuentes, y en el Fire TV con magis —el caso que motivó la
 * feature— salía en los últimos minutos de cada capítulo para no hacer nada al pulsarlo.
 *
 * El camino bueno ya existía al lado: `alTerminarElCapitulo()` navega a la ruta del capítulo
 * siguiente ([PlayerScreen]'s `onNextEpisode`), que es lo que re-arranca la resolución de la
 * fuente. Se prefiere el avance dentro de la playlist cuando lo hay porque no re-resuelve nada
 * (archive ya tiene el ítem cargado); si no, se navega; y si no hay ninguno de los dos —una
 * película, o el último capítulo— no se dibuja el botón.
 */
internal object SaltoDeOutro {

    enum class Accion {
        /** `seekToNextMediaItem()`: hay otro ítem cargado en la playlist (archive). */
        AVANZAR_EN_LA_PLAYLIST,

        /** `onNextEpisode(siguiente)`: el mismo camino que el auto-avance de fin de capítulo. */
        IR_AL_SIGUIENTE_CAPITULO,

        /** No hay a dónde saltar: el botón no se dibuja. */
        NINGUNA,
    }

    fun decidir(indiceActual: Int, itemsEnLaPlaylist: Int, siguienteCapitulo: String?): Accion = when {
        indiceActual in 0 until itemsEnLaPlaylist - 1 -> Accion.AVANZAR_EN_LA_PLAYLIST
        !siguienteCapitulo.isNullOrBlank() -> Accion.IR_AL_SIGUIENTE_CAPITULO
        else -> Accion.NINGUNA
    }
}

/** Cuál de los dos botones flotantes está en pantalla. Nunca los dos: ver [BotonDeSalto.cual]. */
internal enum class BotonDeSalto {
    INTRO,
    OUTRO,
    ;

    companion object {
        /**
         * El botón que corresponde a esta posición del capítulo, o `null` si no va ninguno.
         *
         * Sale UNO solo aunque los intervalos se pisen: desde que el botón se lleva el foco al
         * aparecer (ver [FocoDelSalto]), dos botones a la vez son dos candidatos al foco.
         *
         * Casteando el de intro sale igual —es un `seekTo` que el CastPlayer hace igual de bien—
         * y el de outro no, porque cambia de capítulo y el receptor tiene uno solo cargado.
         */
        fun cual(
            enOpening: Boolean,
            enEnding: Boolean,
            accionDelOutro: SaltoDeOutro.Accion,
            casting: Boolean,
        ): BotonDeSalto? = when {
            enOpening -> INTRO
            enEnding && !casting && accionDelOutro != SaltoDeOutro.Accion.NINGUNA -> OUTRO
            else -> null
        }
    }
}

/**
 * Quién tiene el foco del D-pad mientras el botón de saltar está en pantalla.
 *
 * Se probó en el Fire TV: el botón salía pero no había forma de llegar a él, porque se dibuja
 * fuera del bloque de controles y fuera del sistema de focos (`PlayerFoco.kt`). Con el foco en la
 * barra de progreso, pulsar OK **pausaba el video** — el gesto natural hacía lo contrario de lo
 * que uno espera. Así que el botón se lleva el foco al aparecer, como en Netflix o Crunchyroll:
 * con el capítulo sonando y el botón visible, un solo OK salta el opening.
 *
 * Las dos condiciones que lo hacen tolerable, y por las que esto es una máquina de estados y no un
 * `requestFocus()` suelto:
 *
 * - **Roba el foco UNA vez.** Esto se consulta con cada tick de la posición; si pidiera el foco en
 *   cada pasada, irse a los controles sería imposible (medio segundo después volvería al botón).
 *   Por eso solo actúa cuando CAMBIA cuál botón hay, y por eso la comparación es contra el botón
 *   anterior y no contra un booleano: intro → outro es un botón nuevo y sí pide el foco.
 * - **Al desaparecer devuelve el foco.** Si el nodo enfocado se va sin más, el foco queda huérfano
 *   y el control remoto deja de responder, que es mucho peor que el bug original. Vuelve al video
 *   (que es quien tiene el "cualquier tecla = mostrar los controles") o a la barra si el overlay
 *   está abierto. Y si para entonces el foco ya no estaba en el botón —la persona se fue a los
 *   controles— no se toca nada: sería quitárselo de donde lo puso.
 */
internal class FocoDelSalto {

    enum class Accion { PEDIR, DEVOLVER_A_LOS_CONTROLES, DEVOLVER_AL_VIDEO, NADA }

    private var anterior: BotonDeSalto? = null

    fun alCambiar(boton: BotonDeSalto?, teniaElFoco: Boolean, controlesVisibles: Boolean): Accion {
        if (boton == anterior) return Accion.NADA
        anterior = boton
        return when {
            boton != null -> Accion.PEDIR
            !teniaElFoco -> Accion.NADA
            controlesVisibles -> Accion.DEVOLVER_A_LOS_CONTROLES
            else -> Accion.DEVOLVER_AL_VIDEO
        }
    }
}
