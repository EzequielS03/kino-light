package com.arkiv.player.data

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * El título que se le manda a TMDB para resolver el arte. El bug real: "Naruto — Pack" no matcheaba
 * nada (la raya larga `—` no se normalizaba y "Pack" no es parte del título), así que esos ítems
 * quedaban sin `tmdbId` y no se agrupaban con el resto de la serie.
 */
class CleanTitleForSearchTest {

    @Test
    fun `quita el sufijo de pack con raya larga`() {
        assertEquals("Naruto", cleanTitleForSearch("Naruto — Pack"))
        assertEquals("Los Simpson", cleanTitleForSearch("Los Simpson — Pack"))
    }

    @Test
    fun `no toca un titulo que ya esta limpio`() {
        assertEquals("Naruto", cleanTitleForSearch("Naruto"))
        assertEquals("DAN DA DAN", cleanTitleForSearch("DAN DA DAN"))
    }

    /** La raya larga en medio del título no es un sufijo de pack: no se corta la parte de atrás. */
    @Test
    fun `una raya larga que no es sufijo de pack se conserva como separador`() {
        assertEquals("Naruto Shippuden", cleanTitleForSearch("Naruto — Shippuden"))
    }

    /**
     * Pin adicional (no pedido por el plan, agregado en el self-review): "Pack" sin una raya
     * delante NO es el sufijo que agrega la app, así que no debe recortarse. Sin este caso, una
     * regex que quite "Pack" al final sin exigir la raya pasaría igual los tres tests de arriba.
     */
    @Test
    fun `pack sin raya delante no se recorta`() {
        assertEquals("Naruto Pack", cleanTitleForSearch("Naruto Pack"))
    }
}
