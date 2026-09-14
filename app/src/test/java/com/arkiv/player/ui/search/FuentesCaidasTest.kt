package com.arkiv.player.ui.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.UnknownHostException

/** Qué dicen los resultados de la búsqueda cuando una fuente, o todas, no respondieron. */
class FuentesCaidasTest {

    private val sinErrores = EstadoDeLasFuentes(respondieron = setOf("magis", "ditu"))
    private val caracolCaido = EstadoDeLasFuentes().conRespuesta("magis").conCaida("ditu", "sin red")
    private val todoCaido = EstadoDeLasFuentes().conCaida("magis", "timeout").conCaida("ditu", "sin red")

    /** Sin errores, Magis se ve exactamente como antes: ningún aviso y los textos de siempre. */
    @Test fun sin_errores_la_pantalla_dice_lo_de_siempre() {
        for (estado in listOf(EstadoDeLasFuentes(), sinErrores)) {
            assertTrue(avisosDeFuentesCaidas(estado, SourceTab.TODO).isEmpty())
            assertEquals(SIN_FUENTES, textoSinFuentes(estado))
            assertEquals("Sin resultados en Caracol.", textoPestanaVacia(SourceTab.CARACOL, false, estado))
            assertEquals("Buscando en Magis…", textoPestanaVacia(SourceTab.MAGIS, true, estado))
            assertEquals("Sin resultados", textoSeccionVacia(SourceTab.MAGIS, estado))
        }
    }

    @Test fun caracol_caido_deja_su_linea_sin_tapar_a_magis() {
        // "sin red" no dice nada que se entienda: la línea es el genérico, sin el texto crudo.
        assertEquals(listOf("Caracol no respondió"), avisosDeFuentesCaidas(caracolCaido, SourceTab.TODO))
        assertEquals(listOf("Caracol no respondió"), avisosDeFuentesCaidas(caracolCaido, SourceTab.CARACOL))
        assertTrue(avisosDeFuentesCaidas(caracolCaido, SourceTab.MAGIS).isEmpty())
        // La pestaña de Caracol no dice "Buscando…" ni "Sin resultados": lo explica su línea.
        assertNull(textoPestanaVacia(SourceTab.CARACOL, true, caracolCaido))
        assertEquals("Sin resultados en Magis.", textoPestanaVacia(SourceTab.MAGIS, false, caracolCaido))
        assertEquals("No respondió", textoSeccionVacia(SourceTab.CARACOL, caracolCaido))
        assertEquals("Sin resultados", textoSeccionVacia(SourceTab.MAGIS, caracolCaido))
        // Magis sí respondió, sin nada: el consejo de siempre sigue siendo el correcto.
        assertEquals(SIN_FUENTES, textoSinFuentes(caracolCaido))
    }

    /** Con todo caído el problema no es la temporada: el texto no puede aconsejar cambiarla. */
    @Test fun todo_caido_no_aconseja_otra_temporada() {
        assertEquals(SIN_RESPUESTA, textoSinFuentes(todoCaido))
        assertFalse(textoSinFuentes(todoCaido).contains("temporada"))
        assertEquals(
            listOf("Magis no respondió: timeout", "Caracol no respondió"),
            avisosDeFuentesCaidas(todoCaido, SourceTab.TODO),
        )
    }

    /** La línea de Caracol la dice `CaracolFailure`, con la excepción que mandó la fuente. */
    @Test fun la_linea_de_caracol_va_en_palabras_de_persona() {
        val estado = EstadoDeLasFuentes().conRespuesta("magis").conCaida(
            "ditu",
            "Caracol no responde: Unable to resolve host \"middleware.ditu.caracoltv.com\"",
            UnknownHostException("Unable to resolve host \"middleware.ditu.caracoltv.com\""),
        )
        assertEquals(
            listOf("Caracol no respondió: sin conexión a internet"),
            avisosDeFuentesCaidas(estado, SourceTab.CARACOL),
        )
    }

    /** Magis intacto: su línea sigue siendo su nombre y el texto de su error, como antes. */
    @Test fun la_linea_de_magis_sigue_igual() {
        val estado = EstadoDeLasFuentes().conCaida("magis", "Unable to resolve host \"x\"", UnknownHostException("x"))
        assertEquals(listOf("Magis no respondió: Unable to resolve host \"x\""), avisosDeFuentesCaidas(estado, SourceTab.TODO))
    }

    /** `FuenteCompuesta` nombra "desconocida" a una fuente que se cae antes de anunciarse. */
    @Test fun una_fuente_sin_nombre_se_avisa_igual() {
        val estado = EstadoDeLasFuentes().conCaida("desconocida", "boom")
        assertEquals(listOf("Una fuente no respondió: boom"), avisosDeFuentesCaidas(estado, SourceTab.TODO))
        assertTrue(avisosDeFuentesCaidas(estado, SourceTab.CARACOL).isEmpty())
    }
}
