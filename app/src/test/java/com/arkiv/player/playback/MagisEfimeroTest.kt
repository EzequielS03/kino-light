package com.arkiv.player.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * El camino que reproduce sin escribir una fila. Ver el KDoc de [MagisEfimero] para el porqué.
 */
class MagisEfimeroTest {

    @Test fun `el id efimero se reconoce y sigue siendo de magis`() {
        val id = MagisEfimero.idPara("C1")

        assertTrue(MagisEfimero.esEfimero(id))
        // Sin esto `PlayerViewModel.load` lo mandaría al camino de archive y no reproduciría nada.
        assertEquals(SourceKind.MAGIS, PlayerSource.kindFor(id))
    }

    /**
     * Un episodeId de biblioteca NO puede parecer efímero: si lo pareciera, un ítem guardado se
     * reproduciría por el camino que no guarda progreso, y se perdería el "seguir viendo" de
     * contenido normal sin que nadie se entere.
     */
    @Test fun `un id de biblioteca no se confunde con uno efimero`() {
        assertFalse(MagisEfimero.esEfimero("magis:12345::1"))
        assertFalse(MagisEfimero.esEfimero("torrent:abc::0"))
    }

    @Test fun `lo dejado se recupera con su mismo id`() {
        val id = MagisEfimero.idPara("C2")
        MagisEfimero.dejar(MagisEfimero.Pendiente(id, ref = "abc.def", titulo = "Peli", adulto = true))

        val p = MagisEfimero.tomar(id)

        assertEquals("abc.def", p?.ref)
        assertEquals("Peli", p?.titulo)
        assertTrue(p?.adulto == true)
    }

    /**
     * EL BORDE: preguntar por OTRO episodio no puede devolver el pendiente que quedó de la
     * reproducción anterior. Si lo devolviera, abrir un ítem cualquiera de la biblioteca justo
     * después de haber visto algo por este camino resolvería el stream equivocado.
     */
    @Test fun `preguntar por otro episodio no devuelve el pendiente ajeno`() {
        MagisEfimero.dejar(
            MagisEfimero.Pendiente(MagisEfimero.idPara("C3"), ref = "x.y", titulo = "", adulto = true),
        )

        assertNull(MagisEfimero.tomar(MagisEfimero.idPara("C4")))
        assertNull(MagisEfimero.tomar("magis:999::1"))
    }
}
