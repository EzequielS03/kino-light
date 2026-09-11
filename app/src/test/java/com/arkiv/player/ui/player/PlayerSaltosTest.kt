package com.arkiv.player.ui.player

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test

class SaltoDeOutroTest {

    @Test fun con_otro_item_en_la_playlist_avanza_por_dentro() {
        // archive.org (source removed in this branch's pruning) was the only multi-item source: it
        // loaded the whole section as a playlist, and there `seekToNextMediaItem()` did lead
        // somewhere, without re-resolving the source.
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
        // The bug this fixes: magis, Ditu, local, and the legacy sources (web, torrent, NUC)
        // publish ONE item, so the `seekToNextMediaItem()` the button made had nowhere to
        // go and pressing it did NOTHING. It's the same path the auto-advance already uses when a
        // chapter ends.
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

class InsistirConElFocoTest {

    private var pedidos = 0
    private var esperas = 0
    private var enfocado = false

    private fun insistir(intentos: Int = 10, pedir: () -> Unit) = runBlocking {
        insistirConElFoco(
            intentos = intentos,
            yaEstaEnfocado = { enfocado },
            esperar = { esperas++ },
            pedir = { pedidos++; pedir() },
        )
    }

    @Test fun corta_de_verdad_en_cuanto_consigue_el_foco() {
        // El bug: `return@repeat` retorna del lambda, o sea que es un `continue`. Las diez
        // vueltas ocurrían igual (y encima salteándose la espera).
        insistir { if (pedidos >= 2) enfocado = true }
        assertEquals(2, pedidos)
    }

    @Test fun si_ya_lo_tiene_no_lo_vuelve_a_pedir() {
        enfocado = true
        assertTrue(insistir { })
        assertEquals(0, pedidos)
        assertEquals(0, esperas)
    }

    @Test fun espera_entre_intento_e_intento() {
        // Lo único que le da sentido a reintentar. Antes la espera estaba DESPUÉS del `return`
        // que se ejecutaba casi siempre, así que los diez intentos pasaban en microsegundos.
        insistir(intentos = 3) { }
        assertEquals(3, pedidos)
        assertEquals(3, esperas)
    }

    @Test fun si_no_lo_consigue_lo_dice() {
        assertFalse(insistir(intentos = 3) { })
    }

    @Test fun una_excepcion_no_cuenta_como_haber_conseguido_el_foco() {
        // El corazón del bug: se miraba `runCatching { requestFocus() }.isSuccess`, y en Compose
        // 1.7.6 `requestFocus()` devuelve void (llama a `focus()` y descarta su booleano —
        // verificado con javap sobre el AAR), así que solo es "fallo" si LANZA, cosa que hace
        // únicamente cuando el FocusRequester no está asociado a ningún nodo. La señal buena es
        // el `onFocusChanged` del botón, no la excepción.
        assertFalse(insistir(intentos = 3) { throw IllegalStateException("no asociado a un nodo") })
        assertEquals("una excepción no corta el reintento", 3, pedidos)
    }
}
