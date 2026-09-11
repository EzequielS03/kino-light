package com.arkiv.player.ui.player

/**
 * What the "Skip outro" button does when tapped -- and so, whether it's worth drawing it.
 *
 * The button always did `seekToNextMediaItem()`, which only works if the playlist has more than
 * one item. And **archive.org (removed in this branch's pruning) was the only multi-item
 * source**: every source today -- magis, Ditu, and the legacy torrent/web/local rows -- publishes
 * `PlaylistData(listOf(item), …)`, a single item. So `AVANZAR_EN_LA_PLAYLIST` no longer triggers
 * in practice; `decidir` would still return it correctly if a playlist ever had more than one
 * item, it just never does today. Back when only archive's loader baked a marker into `PlayerData`
 * for this, the bug didn't show; once the markers started being read from Room instead, the
 * button began showing up on every source, and on the Fire TV with magis -the case that motivated
 * the feature- it showed up in a chapter's last minutes and did nothing when tapped.
 *
 * The good path already existed right next to it: `alTerminarElCapitulo()` navigates to the next
 * chapter's route ([PlayerScreen]'s `onNextEpisode`), which is what restarts resolving the
 * source. Advancing within the playlist is preferred when there is one because it doesn't
 * re-resolve anything (the item's already loaded); otherwise it navigates; and if neither applies
 * -a movie, or the last chapter- the button isn't drawn.
 */
internal object SaltoDeOutro {

    enum class Accion {
        /** `seekToNextMediaItem()`: hay otro ítem cargado en la playlist (archive.org lo producía; hoy ninguna fuente arma una playlist de más de un ítem). */
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

/** Cuántas veces se insiste con el foco del botón de saltar, y cuánto se espera entre intentos. */
internal const val INTENTOS_DE_FOCO_DEL_SALTO = 10
internal const val ESPERA_ENTRE_INTENTOS_DE_FOCO_MS = 32L

/**
 * Pide el foco hasta conseguirlo, esperando de verdad entre intento e intento. `true` si lo logró.
 *
 * Reintentar hace falta porque el nodo puede no estar colocado en el frame en que aparece, y
 * porque el foco lo tiene una `View` de Android (el `videoView`, con el "cualquier tecla = mostrar
 * los controles") que Compose tiene que quitárselo por la interop.
 *
 * **Por qué la señal es [yaEstaEnfocado] y no lo que devuelve pedir el foco:** no devuelve nada.
 * En Compose UI 1.7.6 —verificado con `javap` sobre el AAR— `FocusRequester.requestFocus()` es
 * `void`: llama a `focus()` y descarta su booleano. Lo único que lanza es el caso de que el
 * `FocusRequester` no esté asociado a ningún nodo. O sea que `runCatching { … }.isSuccess`, que es
 * como estaba escrito esto, daba verdadero casi siempre —también cuando el foco NO se conseguía— y
 * el reintento no reintentaba nada. La única señal real de que el foco llegó es el
 * `onFocusChanged` del propio botón, que es lo que se consulta acá.
 *
 * El `runCatching` queda, pero solo para lo que de verdad lanza, y **sin usarlo como criterio de
 * éxito**: una excepción no corta el bucle, se sigue intentando.
 */
internal suspend fun insistirConElFoco(
    intentos: Int = INTENTOS_DE_FOCO_DEL_SALTO,
    yaEstaEnfocado: () -> Boolean,
    esperar: suspend () -> Unit,
    pedir: () -> Unit,
): Boolean {
    repeat(intentos) {
        // `return` de la función entera, no `return@repeat`: eso último retorna del lambda de la
        // vuelta, o sea que es un `continue` y el bucle no corta nunca. Era la otra mitad del bug.
        if (yaEstaEnfocado()) return true
        runCatching { pedir() }
        esperar()
    }
    return yaEstaEnfocado()
}
