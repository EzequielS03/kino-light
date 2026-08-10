package com.arkiv.player.data.nuevos

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * El número del badge de "hay capítulos nuevos". Ver [ContadorDeNuevos] para por qué se cuenta así
 * y no con fechas.
 */
class ContadorDeNuevosTest {

    @Test fun sin_haber_mirado_nunca_no_hay_badge() {
        // Toda la biblioteca que ya existe caería en este caso: si `null` contara como 0 vistos,
        // el día que se estrene esto cada serie aparecería con badge de todos sus capítulos.
        assertEquals(0, ContadorDeNuevos.cuantos(actuales = 26, vistos = null))
    }

    @Test fun dos_capitulos_mas_que_la_ultima_vez() {
        assertEquals(2, ContadorDeNuevos.cuantos(actuales = 26, vistos = 24))
    }

    @Test fun sin_novedades_no_hay_badge() {
        assertEquals(0, ContadorDeNuevos.cuantos(actuales = 26, vistos = 26))
    }

    @Test fun si_borraste_capitulos_no_hay_badge_negativo() {
        // Pasa si se elimina una fuente del grupo, o si la fuente re-derivó con menos archivos.
        assertEquals(0, ContadorDeNuevos.cuantos(actuales = 24, vistos = 26))
    }

    @Test fun una_serie_vacia_no_tiene_badge() {
        assertEquals(0, ContadorDeNuevos.cuantos(actuales = 0, vistos = 0))
    }

    @Test fun hay_badge_si_debe_pintarse() {
        assertEquals(false, ContadorDeNuevos.hayQuePintar(26, 26))
        assertEquals(false, ContadorDeNuevos.hayQuePintar(26, null))
        assertEquals(true, ContadorDeNuevos.hayQuePintar(26, 24))
    }
}
