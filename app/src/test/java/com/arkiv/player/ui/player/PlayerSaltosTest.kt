package com.arkiv.player.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

class BotonDeSaltoVisibleTest {

    private fun cual(
        enOpening: Boolean = false,
        enEnding: Boolean = false,
        accion: SaltoDeOutro.Accion = SaltoDeOutro.Accion.IR_AL_SIGUIENTE_CAPITULO,
        casting: Boolean = false,
    ) = BotonDeSalto.cual(enOpening, enEnding, accion, casting)

    @Test fun sobre_el_opening_sale_el_de_intro() {
        assertEquals(BotonDeSalto.INTRO, cual(enOpening = true))
    }

    @Test fun sobre_el_ending_sale_el_de_outro() {
        assertEquals(BotonDeSalto.OUTRO, cual(enEnding = true))
    }

    @Test fun casteando_no_sale_el_de_outro() {
        // "Saltar intro" es un seekTo que el CastPlayer hace igual; el outro cambia de capítulo y
        // el receptor tiene uno solo cargado.
        assertNull(cual(enEnding = true, casting = true))
    }

    @Test fun casteando_el_de_intro_sale_igual() {
        assertEquals(BotonDeSalto.INTRO, cual(enOpening = true, casting = true))
    }

    @Test fun sin_a_donde_saltar_no_sale_el_de_outro() {
        assertNull(cual(enEnding = true, accion = SaltoDeOutro.Accion.NINGUNA))
    }

    @Test fun fuera_de_los_dos_intervalos_no_sale_ninguno() {
        assertNull(cual())
    }

    @Test fun si_cayera_en_los_dos_manda_el_de_intro() {
        // No debería pasar con datos sanos; si pasa, sale UNO solo: dos botones que se llevan el
        // foco a la vez es peor que un dato raro.
        assertEquals(BotonDeSalto.INTRO, cual(enOpening = true, enEnding = true))
    }
}

class FocoDelSaltoTest {

    private val foco = FocoDelSalto()

    @Test fun al_aparecer_el_boton_se_lleva_el_foco() {
        // Sin esto, el botón se ve y OK cae en el control de reproducción: PAUSA el video en vez
        // de saltar el opening, que es lo contrario de lo que uno espera.
        assertEquals(
            FocoDelSalto.Accion.PEDIR,
            foco.alCambiar(BotonDeSalto.INTRO, teniaElFoco = false, controlesVisibles = false),
        )
    }

    @Test fun mientras_sigue_en_pantalla_no_lo_vuelve_a_pedir() {
        // La posición avanza y esto se re-evalúa a cada tick de la barra: robar el foco una vez es
        // servicial, robarlo cada medio segundo es inusable. Si la persona se fue a los controles,
        // se queda allá.
        foco.alCambiar(BotonDeSalto.INTRO, teniaElFoco = false, controlesVisibles = false)
        assertEquals(
            FocoDelSalto.Accion.NADA,
            foco.alCambiar(BotonDeSalto.INTRO, teniaElFoco = false, controlesVisibles = true),
        )
    }

    @Test fun el_de_outro_es_otro_boton_y_si_pide_el_foco() {
        foco.alCambiar(BotonDeSalto.INTRO, teniaElFoco = false, controlesVisibles = false)
        assertEquals(
            FocoDelSalto.Accion.PEDIR,
            foco.alCambiar(BotonDeSalto.OUTRO, teniaElFoco = false, controlesVisibles = false),
        )
    }

    @Test fun al_desaparecer_con_el_foco_puesto_y_sin_controles_vuelve_al_video() {
        // Si el foco queda huérfano el mando deja de responder, que es mucho peor que el bug
        // original. El video es quien tiene el "cualquier tecla = mostrar los controles".
        foco.alCambiar(BotonDeSalto.INTRO, teniaElFoco = false, controlesVisibles = false)
        assertEquals(
            FocoDelSalto.Accion.DEVOLVER_AL_VIDEO,
            foco.alCambiar(null, teniaElFoco = true, controlesVisibles = false),
        )
    }

    @Test fun al_desaparecer_con_los_controles_abiertos_vuelve_a_la_barra() {
        foco.alCambiar(BotonDeSalto.INTRO, teniaElFoco = false, controlesVisibles = false)
        assertEquals(
            FocoDelSalto.Accion.DEVOLVER_A_LOS_CONTROLES,
            foco.alCambiar(null, teniaElFoco = true, controlesVisibles = true),
        )
    }

    @Test fun al_desaparecer_sin_el_foco_no_se_toca_nada() {
        // La persona ya se había ido a los controles: moverle el foco desde acá sería quitárselo
        // de donde lo puso.
        foco.alCambiar(BotonDeSalto.INTRO, teniaElFoco = false, controlesVisibles = true)
        assertEquals(
            FocoDelSalto.Accion.NADA,
            foco.alCambiar(null, teniaElFoco = false, controlesVisibles = true),
        )
    }

    @Test fun sin_boton_nunca_no_hace_nada() {
        assertEquals(
            FocoDelSalto.Accion.NADA,
            foco.alCambiar(null, teniaElFoco = false, controlesVisibles = false),
        )
    }
}
