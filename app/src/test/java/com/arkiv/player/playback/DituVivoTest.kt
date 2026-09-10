package com.arkiv.player.playback

import com.arkiv.player.data.DituEntities
import com.arkiv.player.data.ditu.DituCanal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** El camino de un canal en vivo de Caracol al reproductor. Ver el KDoc de [DituVivo]. */
class DituVivoTest {

    private val canalUno = DituCanal(channelId = 1, nombre = "Caracol TV", logoUrl = "", assetId = 11)
    private val canalDos = DituCanal(channelId = 2, nombre = "Noticias Caracol", logoUrl = "", assetId = 22)

    /** Sin esto `PlayerViewModel.load` no lo mandaría a `loadDitu` y el canal no sonaría. */
    @Test fun `el id de un canal es de Caracol`() {
        val id = DituVivo.dejar(canalUno)

        assertTrue(DituVivo.esVivo(id))
        assertEquals(SourceKind.DITU, PlayerSource.kindFor(id))
    }

    @Test fun `con su id se toma el canal que se dejo`() {
        val id = DituVivo.dejar(canalUno)

        assertEquals(canalUno, DituVivo.tomar(id))
    }

    /** Un vivo no puede reabrir un canal viejo, ni un episodio de la biblioteca encontrar un canal. */
    @Test fun `con otro id no devuelve nada`() {
        val idViejo = DituVivo.dejar(canalDos)
        DituVivo.dejar(canalUno)

        assertNull(DituVivo.tomar(idViejo))
        assertNull(DituVivo.tomar("ditu:42::0"))
    }

    /**
     * La recarga por token vencido vuelve a pedir el canal con el mismo id. Si [DituVivo.tomar] lo
     * vaciara, la segunda vez no lo encontraría y el vivo moriría al vencer el token.
     */
    @Test fun `tomar no lo vacia`() {
        val id = DituVivo.dejar(canalUno)

        DituVivo.tomar(id)

        assertEquals(canalUno, DituVivo.tomar(id))
    }

    /** Lo guardado de Caracol en la biblioteca no puede entrar por la rama del vivo. */
    @Test fun `un id de la biblioteca no es un vivo`() {
        val pelicula = DituEntities.episodioIdDePelicula(DituEntities.itemIdDe("42"))
        val capitulo = DituEntities.episodioIdDe(DituEntities.itemIdDe("99"), 1)

        assertFalse(DituVivo.esVivo(pelicula))
        assertFalse(DituVivo.esVivo(capitulo))
    }
}
