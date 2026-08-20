package com.arkiv.player.data

import com.arkiv.player.data.db.SkipMarkerEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Qué marcador manda para un capítulo.
 *
 * El caso que obliga a esto, medido contra AniSkip el 2026-08-19: en Demon Slayer el opening del
 * capítulo 1 empieza a los 1270 s y el del 2 a los 57 s. Con un marcador por SERIE, "Saltar intro"
 * te tiraría a la mitad del capítulo.
 *
 * Corrección posterior, también medida contra el aparato real: AniSkip A VECES SE EQUIVOCA (un
 * capítulo trajo los créditos etiquetados como opening) y no hay forma fiable de detectarlo. La
 * salida es que la persona lo corrija a mano, así que lo MANUAL tiene que poder ganarle a lo
 * automático sin importar el alcance -- si no, un tiempo malo del capítulo nunca se podría arreglar.
 */
class MarcadorDeCapituloTest {

    private fun marcador(
        itemId: String,
        episodeId: String,
        opEnd: Long,
        origen: String = MarcadorDeCapitulo.ORIGEN_MANUAL,
    ) = SkipMarkerEntity(
        id = MarcadorDeCapitulo.idDe(itemId, episodeId),
        itemId = itemId,
        episodeId = episodeId,
        openingStartMs = 0,
        openingEndMs = opEnd,
        endingStartMs = null,
        origen = origen,
    )

    @Test fun la_llave_junta_el_item_y_el_capitulo() {
        assertEquals("magis:ABC|magis:ABC::e1", MarcadorDeCapitulo.idDe("magis:ABC", "magis:ABC::e1"))
    }

    @Test fun el_marcador_de_toda_la_serie_lleva_el_capitulo_vacio() {
        assertEquals("magis:ABC|", MarcadorDeCapitulo.idDe("magis:ABC", ""))
    }

    @Test fun dos_capitulos_de_la_misma_serie_no_comparten_llave() {
        assertNotEquals(
            MarcadorDeCapitulo.idDe("magis:ABC", "magis:ABC::e1"),
            MarcadorDeCapitulo.idDe("magis:ABC", "magis:ABC::e2"),
        )
    }

    /** A igual origen (los dos manuales, el default de `marcador()`), gana el del capítulo. */
    @Test fun el_del_capitulo_le_gana_al_de_la_serie() {
        val elegido = MarcadorDeCapitulo.elegir(
            delCapitulo = marcador("magis:ABC", "magis:ABC::e1", opEnd = 147_000),
            deLaSerie = marcador("magis:ABC", "", opEnd = 90_000),
        )
        assertEquals(147_000L, elegido!!.openingEndMs)
    }

    /** Lo puesto a mano sigue valiendo donde no hay dato automático: nadie pierde su trabajo. */
    @Test fun sin_marcador_de_capitulo_manda_el_de_la_serie() {
        val elegido = MarcadorDeCapitulo.elegir(
            delCapitulo = null,
            deLaSerie = marcador("magis:ABC", "", opEnd = 90_000),
        )
        assertEquals(90_000L, elegido!!.openingEndMs)
    }

    @Test fun sin_ninguno_no_hay_marcador() {
        assertNull(MarcadorDeCapitulo.elegir(delCapitulo = null, deLaSerie = null))
    }

    /** Una fila sin ningún tiempo no es un marcador: dibujaría un botón que no lleva a ningún lado. */
    @Test fun un_marcador_sin_tiempos_no_cuenta() {
        val vacio = SkipMarkerEntity(
            id = "x", itemId = "magis:ABC", episodeId = "magis:ABC::e1",
            openingStartMs = null, openingEndMs = null, endingStartMs = null,
        )
        assertNull(MarcadorDeCapitulo.elegir(delCapitulo = vacio, deLaSerie = null))
    }

    // --- Precedencia por origen: lo manual le gana a lo automático, sin importar el alcance ---

    /**
     * EL caso que motiva la corrección: AniSkip trajo mal el capítulo (créditos como opening), la
     * persona lo corrigió a mano en el diálogo de la SERIE -- y ese arreglo tiene que ganarle al
     * dato automático del capítulo, no al revés.
     */
    @Test fun el_manual_de_la_serie_le_gana_al_automatico_del_capitulo() {
        val elegido = MarcadorDeCapitulo.elegir(
            delCapitulo = marcador(
                "magis:ABC", "magis:ABC::e1", opEnd = 999_000,
                origen = MarcadorDeCapitulo.ORIGEN_AUTO,
            ),
            deLaSerie = marcador(
                "magis:ABC", "", opEnd = 90_000,
                origen = MarcadorDeCapitulo.ORIGEN_MANUAL,
            ),
        )
        assertEquals(90_000L, elegido!!.openingEndMs)
    }

    /** Un manual de capítulo también le gana a un automático de la serie (nadie pisa lo a mano). */
    @Test fun el_manual_del_capitulo_le_gana_al_automatico_de_la_serie() {
        val elegido = MarcadorDeCapitulo.elegir(
            delCapitulo = marcador(
                "magis:ABC", "magis:ABC::e1", opEnd = 147_000,
                origen = MarcadorDeCapitulo.ORIGEN_MANUAL,
            ),
            deLaSerie = marcador(
                "magis:ABC", "", opEnd = 90_000,
                origen = MarcadorDeCapitulo.ORIGEN_AUTO,
            ),
        )
        assertEquals(147_000L, elegido!!.openingEndMs)
    }

    /** A igual origen automático, sigue mandando el del capítulo (mismo criterio que lo manual). */
    @Test fun a_igual_origen_automatico_el_del_capitulo_le_gana_al_de_la_serie() {
        val elegido = MarcadorDeCapitulo.elegir(
            delCapitulo = marcador(
                "magis:ABC", "magis:ABC::e1", opEnd = 147_000,
                origen = MarcadorDeCapitulo.ORIGEN_AUTO,
            ),
            deLaSerie = marcador(
                "magis:ABC", "", opEnd = 90_000,
                origen = MarcadorDeCapitulo.ORIGEN_AUTO,
            ),
        )
        assertEquals(147_000L, elegido!!.openingEndMs)
    }

    // --- enOpening/enEnding: la cuenta que decide si sale el botón, extraída de PlayerScreen ---

    private fun conAmbosTramos(openStart: Long?, openEnd: Long?, endingStart: Long?) = SkipMarkerEntity(
        id = "x", itemId = "magis:ABC", episodeId = "magis:ABC::e1",
        openingStartMs = openStart, openingEndMs = openEnd, endingStartMs = endingStart,
    )

    @Test fun en_opening_dentro_del_rango() {
        val m = conAmbosTramos(openStart = 10_000, openEnd = 90_000, endingStart = null)
        assertTrue(MarcadorDeCapitulo.enOpening(m, posicionMs = 50_000))
    }

    @Test fun en_opening_en_los_bordes_del_rango_cuenta() {
        val m = conAmbosTramos(openStart = 10_000, openEnd = 90_000, endingStart = null)
        assertTrue(MarcadorDeCapitulo.enOpening(m, posicionMs = 10_000))
        assertTrue(MarcadorDeCapitulo.enOpening(m, posicionMs = 90_000))
    }

    @Test fun en_opening_falso_antes_o_despues_del_rango() {
        val m = conAmbosTramos(openStart = 10_000, openEnd = 90_000, endingStart = null)
        assertFalse(MarcadorDeCapitulo.enOpening(m, posicionMs = 9_999))
        assertFalse(MarcadorDeCapitulo.enOpening(m, posicionMs = 90_001))
    }

    /** Sin `openingStartMs` el rango arranca en 0: el opening manual viejo no lo guardaba. */
    @Test fun en_opening_sin_inicio_arranca_en_cero() {
        val m = conAmbosTramos(openStart = null, openEnd = 90_000, endingStart = null)
        assertTrue(MarcadorDeCapitulo.enOpening(m, posicionMs = 0))
        assertFalse(MarcadorDeCapitulo.enOpening(m, posicionMs = 90_001))
    }

    @Test fun en_opening_falso_sin_openingEndMs() {
        val m = conAmbosTramos(openStart = 10_000, openEnd = null, endingStart = null)
        assertFalse(MarcadorDeCapitulo.enOpening(m, posicionMs = 50_000))
    }

    @Test fun en_opening_falso_sin_marcador() {
        assertFalse(MarcadorDeCapitulo.enOpening(null, posicionMs = 50_000))
    }

    @Test fun en_ending_verdadero_desde_que_arranca() {
        val m = conAmbosTramos(openStart = null, openEnd = null, endingStart = 1_400_000)
        assertTrue(MarcadorDeCapitulo.enEnding(m, posicionMs = 1_400_000))
        assertTrue(MarcadorDeCapitulo.enEnding(m, posicionMs = 1_500_000))
    }

    @Test fun en_ending_falso_antes_de_que_arranque() {
        val m = conAmbosTramos(openStart = null, openEnd = null, endingStart = 1_400_000)
        assertFalse(MarcadorDeCapitulo.enEnding(m, posicionMs = 1_399_999))
    }

    @Test fun en_ending_falso_sin_endingStartMs() {
        val m = conAmbosTramos(openStart = 10_000, openEnd = 90_000, endingStart = null)
        assertFalse(MarcadorDeCapitulo.enEnding(m, posicionMs = 999_999_999))
    }

    @Test fun en_ending_falso_sin_marcador() {
        assertFalse(MarcadorDeCapitulo.enEnding(null, posicionMs = 999_999_999))
    }
}
