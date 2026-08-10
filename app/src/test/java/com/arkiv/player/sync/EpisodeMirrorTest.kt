package com.arkiv.player.sync

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * El espejo de biblioteca solo copiaba los episodios al CREAR el ítem: si el ítem ya existía en el
 * destino, la lista quedaba congelada para siempre (Dragon Ball GT: 29 capítulos en la TV mientras
 * el origen ya iba en 58). Estos casos fijan cuándo hay que volver a copiarla.
 */
class EpisodeMirrorTest {

    @Test fun `misma lista no necesita refresco`() {
        assertFalse(EpisodeMirror.differs(listOf("a", "b"), listOf("a", "b")))
    }

    @Test fun `el orden no cuenta como diferencia`() {
        assertFalse(EpisodeMirror.differs(listOf("a", "b"), listOf("b", "a")))
    }

    @Test fun `capitulos nuevos en el origen si necesitan refresco`() {
        assertTrue(EpisodeMirror.differs(listOf("a", "b"), listOf("a", "b", "c")))
    }

    @Test fun `capitulos borrados en el origen si necesitan refresco`() {
        assertTrue(EpisodeMirror.differs(listOf("a", "b", "c"), listOf("a", "b")))
    }

    @Test fun `origen vacio no borra lo local`() {
        // Si el snapshot no trae episodios de ese ítem, casi siempre es que no llegaron todavía;
        // borrar los locales dejaría la serie sin capítulos y sin forma de recuperarlos.
        assertFalse(EpisodeMirror.differs(listOf("a", "b"), emptyList()))
    }

    @Test fun `item que llega con capitulos y localmente no tiene si refresca`() {
        assertTrue(EpisodeMirror.differs(emptyList(), listOf("a")))
    }
}
