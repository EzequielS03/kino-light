package com.arkiv.player.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Cuándo el `orderIndex` de una fila trae la numeración adentro y cuándo no.
 *
 * El caso que originó esto: la regla vieja era "si `orderIndex >= 1000`, viene codificado", y una
 * temporada 0 da 0*1000 + N — por debajo de 1000. El especial 3 se mostraba como "E4".
 */
class NumeracionCodificadaTest {

    @Test fun `una temporada normal de torrent se decodifica`() {
        assertEquals(1 to 3, NumeracionCodificada.coordenadas("torrent:series:tt1", "Temporada 1", 1003))
    }

    @Test fun `una temporada 0 se decodifica aunque no llegue a 1000`() {
        assertEquals(0 to 3, NumeracionCodificada.coordenadas("torrent:series:tt1", "Temporada 0", 3))
    }

    @Test fun `web codifica igual que torrent`() {
        assertEquals(0 to 1, NumeracionCodificada.coordenadas("web:series:tt1", "Temporada 0", 1))
    }

    @Test fun `un pack tambien codifica`() {
        assertEquals(2 to 7, NumeracionCodificada.coordenadas("torrent:abc123", "Temporada 2", 2007))
    }

    /** archive.org usa el orderIndex como correlativo: no hay nada que decodificar. */
    @Test fun `archive nunca codifica`() {
        assertNull(NumeracionCodificada.coordenadas("mi-serie-favorita", "", 3))
    }

    /**
     * El contraejemplo que obliga a mirar las DOS cosas: una subida de archive.org en español bien
     * puede tener sus archivos en una carpeta llamada "Temporada 1". La sección sola no alcanza.
     */
    @Test fun `archive con una carpeta llamada Temporada sigue sin codificar`() {
        assertNull(NumeracionCodificada.coordenadas("mi-serie-favorita", "Temporada 1", 0))
    }

    /**
     * El otro contraejemplo, por arriba: un pack de anime con numeración absoluta pasa de 1000 sin
     * estar codificado. La regla vieja lo leía como "T1 · E85".
     */
    @Test fun `un pack con numeracion absoluta no codifica`() {
        assertNull(NumeracionCodificada.coordenadas("torrent:abc123", "", 1085))
    }

    /** Magis numera en su propia columna y deja la sección vacía. */
    @Test fun `magis no codifica`() {
        assertNull(NumeracionCodificada.coordenadas("magis:ABC", "", 5))
    }

    /** Una sección que solo EMPIEZA como la de temporada no cuenta: el nombre tiene que ser ese. */
    @Test fun `una seccion parecida pero distinta no cuenta`() {
        assertNull(NumeracionCodificada.coordenadas("torrent:series:tt1", "Temporada 1 (dual)", 1003))
        assertNull(NumeracionCodificada.coordenadas("torrent:series:tt1", "Temporadas", 1003))
    }
}
