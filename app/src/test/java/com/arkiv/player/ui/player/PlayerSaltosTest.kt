package com.arkiv.player.ui.player

import org.junit.Assert.assertEquals
import org.junit.Test

class SaltoDeOutroTest {

    @Test fun con_otro_item_en_la_playlist_avanza_por_dentro() {
        // archive.org es la única fuente multi-ítem: carga la sección entera como playlist y ahí
        // `seekToNextMediaItem()` sí lleva a algún lado, sin re-resolver la fuente.
        assertEquals(
            SaltoDeOutro.Accion.AVANZAR_EN_LA_PLAYLIST,
            SaltoDeOutro.decidir(indiceActual = 0, itemsEnLaPlaylist = 12, siguienteCapitulo = "e2"),
        )
    }

    @Test fun en_el_ultimo_item_de_la_playlist_navega_al_siguiente_capitulo() {
        assertEquals(
            SaltoDeOutro.Accion.IR_AL_SIGUIENTE_CAPITULO,
            SaltoDeOutro.decidir(indiceActual = 11, itemsEnLaPlaylist = 12, siguienteCapitulo = "e13"),
        )
    }

    @Test fun con_un_solo_item_navega_al_siguiente_capitulo() {
        // El bug que arregla esto: magis, web, torrent, local y la NUC publican UN ítem, así que
        // el `seekToNextMediaItem()` que hacía el botón no tenía a dónde ir y pulsarlo no hacía
        // NADA. Es el mismo camino que ya usa el auto-avance al terminar el capítulo.
        assertEquals(
            SaltoDeOutro.Accion.IR_AL_SIGUIENTE_CAPITULO,
            SaltoDeOutro.decidir(indiceActual = 0, itemsEnLaPlaylist = 1, siguienteCapitulo = "e2"),
        )
    }

    @Test fun sin_siguiente_capitulo_no_hay_salto() {
        // Una película, o el último capítulo de la serie: el botón no se dibuja. Un botón que se
        // ve y no hace nada es peor que no tenerlo.
        assertEquals(
            SaltoDeOutro.Accion.NINGUNA,
            SaltoDeOutro.decidir(indiceActual = 0, itemsEnLaPlaylist = 1, siguienteCapitulo = null),
        )
    }

    @Test fun un_siguiente_en_blanco_es_lo_mismo_que_no_tenerlo() {
        assertEquals(
            SaltoDeOutro.Accion.NINGUNA,
            SaltoDeOutro.decidir(indiceActual = 0, itemsEnLaPlaylist = 1, siguienteCapitulo = "  "),
        )
    }

    @Test fun sin_playlist_todavia_solo_queda_el_siguiente_capitulo() {
        assertEquals(
            SaltoDeOutro.Accion.IR_AL_SIGUIENTE_CAPITULO,
            SaltoDeOutro.decidir(indiceActual = 0, itemsEnLaPlaylist = 0, siguienteCapitulo = "e2"),
        )
    }

    @Test fun un_indice_fuera_de_rango_no_inventa_un_item_siguiente() {
        // `currentIndex` lo mueve el listener del player y la playlist se reemplaza entera al
        // cambiar de capítulo: pueden cruzarse por un frame.
        assertEquals(
            SaltoDeOutro.Accion.IR_AL_SIGUIENTE_CAPITULO,
            SaltoDeOutro.decidir(indiceActual = 40, itemsEnLaPlaylist = 12, siguienteCapitulo = "e13"),
        )
    }
}
