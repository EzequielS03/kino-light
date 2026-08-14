package com.arkiv.player.ui.entrada

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Los bordes de la máscara del código de licencia.
 *
 * El caso feliz es obvio; lo que hay que fijar es lo que rompe: perder lo escrito al reformatear,
 * dejar pasar caracteres que el gateway va a rechazar después, y el cursor quedando del lado
 * equivocado del guión (que se ve como "escribo y las letras salen desordenadas").
 */
class MascaraDeLicenciaTest {

    @Test
    fun pone_los_guiones_sola() {
        assertEquals("TUXY-Q7EV-HHKF", MascaraDeLicencia.formatear("TUXYQ7EVHHKF"))
    }

    @Test
    fun formatear_lo_ya_formateado_no_cambia_nada() {
        // Idempotencia: el campo se reformatea en CADA tecla, pasándole lo que ya tiene. Si no
        // fuera idempotente, cada pulsación acumularía guiones o se comería lo escrito.
        val una = MascaraDeLicencia.formatear("TUXYQ7EVHHKF")
        assertEquals(una, MascaraDeLicencia.formatear(una))
        assertEquals(una, MascaraDeLicencia.formatear(MascaraDeLicencia.formatear(una)))
    }

    @Test
    fun pasa_a_mayusculas() {
        assertEquals("TUXY-Q7EV-HHKF", MascaraDeLicencia.formatear("tuxyq7evhhkf"))
    }

    @Test
    fun descarta_lo_que_el_gateway_no_acepta() {
        // Espacios, guiones de más y signos: se caen acá y no después de tipear los 14.
        assertEquals("TUXY-Q7EV-HHKF", MascaraDeLicencia.formatear(" TUXY - Q7EV_HHKF! "))
    }

    @Test
    fun corrige_los_caracteres_ambiguos_en_vez_de_tragarselos() {
        // El alfabeto sacó I/L/O/0/1 PORQUE se confunden al leer. Si llegan, lo que la persona
        // quiso poner solo puede ser su pareja; rechazar la tecla dejaría un botón que no responde.
        assertEquals("Q", MascaraDeLicencia.formatear("O"))
        assertEquals("Q", MascaraDeLicencia.formatear("0"))
        assertEquals("J", MascaraDeLicencia.formatear("I"))
        assertEquals("J", MascaraDeLicencia.formatear("1"))
        assertEquals("J", MascaraDeLicencia.formatear("L"))
    }

    @Test
    fun no_deja_escribir_de_mas() {
        assertEquals("TUXY-Q7EV-HHKF", MascaraDeLicencia.formatear("TUXYQ7EVHHKFZZZZZZ"))
    }

    @Test
    fun bloques_incompletos_no_arrastran_un_guion_colgando() {
        // Un guión al final se ve como un campo roto, y peor: al borrar hacia atrás el guión
        // volvería a aparecer solo y no se podría pasar de ahí.
        assertEquals("TUXY", MascaraDeLicencia.formatear("TUXY"))
        assertEquals("TUXY-Q", MascaraDeLicencia.formatear("TUXYQ"))
        assertEquals("TUXY-Q7EV", MascaraDeLicencia.formatear("TUXYQ7EV"))
    }

    @Test
    fun sabe_cuando_esta_completo() {
        assertTrue(MascaraDeLicencia.estaCompleto("tuxyq7evhhkf"))
        assertTrue(MascaraDeLicencia.estaCompleto("TUXY-Q7EV-HHKF"))
        assertFalse(MascaraDeLicencia.estaCompleto("TUXY-Q7EV-HHK"))
        assertFalse(MascaraDeLicencia.estaCompleto(""))
    }

    @Test
    fun el_cursor_salta_por_encima_del_guion_recien_puesto() {
        // Al escribir el cuarto carácter aparece un guión. Si el cursor se quedara antes, la tecla
        // siguiente se metería del lado equivocado y las letras saldrian desordenadas.
        assertEquals(0, MascaraDeLicencia.cursorTrasFormatear("", 0))
        assertEquals(4, MascaraDeLicencia.cursorTrasFormatear("TUXY", 4))
        assertEquals(6, MascaraDeLicencia.cursorTrasFormatear("TUXYQ", 5))
        assertEquals(9, MascaraDeLicencia.cursorTrasFormatear("TUXYQ7EV", 8))
        assertEquals(11, MascaraDeLicencia.cursorTrasFormatear("TUXYQ7EVH", 9))
    }

    @Test
    fun el_cursor_nunca_se_va_del_campo() {
        assertEquals(14, MascaraDeLicencia.cursorTrasFormatear("TUXYQ7EVHHKFZZZ", 15))
    }

    @Test
    fun el_alfabeto_es_el_mismo_que_valida_el_gateway() {
        // Si alguna vez cambia alla, esto tiene que romper aca y no en la cara de quien tipea:
        // `_ALFABETO` en identidad/sesion.py.
        assertEquals("ABCDEFGHJKMNPQRSTUVWXYZ23456789", MascaraDeLicencia.ALFABETO)
        for (prohibido in listOf('I', 'L', 'O', '0', '1')) {
            assertFalse("$prohibido no puede estar en el alfabeto", prohibido in MascaraDeLicencia.ALFABETO)
        }
    }
}
