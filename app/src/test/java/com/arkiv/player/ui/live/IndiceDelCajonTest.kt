package com.arkiv.player.ui.live

import com.arkiv.player.data.gateway.LiveChannel
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * En qué fila se para el cajón al abrirse.
 *
 * MEDIDO EN EL FIRE TV el 2026-08-14: al abrir el cajón, la lista se iba sola hacia arriba
 * durante un rato largo hasta el primer canal, en vez de quedarse en el que se estaba viendo.
 *
 * Eran DOS efectos peleando. Uno hacía `scrollToItem` al canal en vivo; el otro pedía el foco, y
 * el `FocusRequester` estaba puesto en el ítem 0 — pedirle foco a la primera fila arrastra la
 * lista entera de vuelta al principio. Con 1040 canales, ese arrastre se ve como un scroll
 * interminable.
 *
 * El arreglo estructural es que el índice se calcule UNA vez y sirva para las dos cosas: la
 * misma fila que recibe el foco es la que se pone a la vista. Esta función es ese único índice,
 * y está acá afuera para poder fijar sus bordes — el proyecto no tiene tests de interfaz.
 */
class IndiceDelCajonTest {

    private fun canal(code: String) = LiveChannel(code, code.uppercase(), 0, null)
    private val lista = listOf(canal("a"), canal("b"), canal("c"))

    @Test fun `se para en el canal que se esta viendo`() {
        assertEquals(1, IndiceDelCajon.para(lista, "b"))
    }

    /**
     * El caso que aparece apenas se escribe en el buscador: la lista filtrada puede no contener
     * el canal en pantalla. Tiene que quedar en el primer resultado — NO en -1, que reventaría
     * el `scrollToItem`, ni dejando el foco en una fila que ya no existe.
     */
    @Test fun `si el canal no esta en la lista, se para en el primero`() {
        assertEquals(0, IndiceDelCajon.para(lista, "z"))
        assertEquals(0, IndiceDelCajon.para(lista, null))
        assertEquals(0, IndiceDelCajon.para(lista, ""))
    }

    @Test fun `con la lista vacia no hay indice que valga`() {
        assertEquals(0, IndiceDelCajon.para(emptyList(), "a"))
    }
}
