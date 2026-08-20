package com.arkiv.player.data.marcadores

import com.arkiv.player.data.gateway.parseMarcadores
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MarcadoresDelGatewayTest {

    @Test fun trae_los_tres_tiempos() {
        val m = parseMarcadores("""{"openingStartMs":0,"openingEndMs":90000,"endingStartMs":1319000}""")
        assertEquals(0L, m!!.openingStartMs)
        assertEquals(90_000L, m.openingEndMs)
        assertEquals(1_319_000L, m.endingStartMs)
    }

    /** El gateway contesta `{}` cuando no sabe: no es un error, es "este capítulo no tiene". */
    @Test fun un_objeto_vacio_es_no_hay_marcadores() {
        assertNull(parseMarcadores("{}"))
    }

    /** Media respuesta vale: sale el botón de intro y no el de outro. */
    @Test fun solo_opening_tambien_sirve() {
        val m = parseMarcadores("""{"openingStartMs":0,"openingEndMs":90000}""")
        assertEquals(90_000L, m!!.openingEndMs)
        assertNull(m.endingStartMs)
    }

    @Test fun un_json_roto_no_revienta() {
        assertNull(parseMarcadores("no soy json"))
    }
}
