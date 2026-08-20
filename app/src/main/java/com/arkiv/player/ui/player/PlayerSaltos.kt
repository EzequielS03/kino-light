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
