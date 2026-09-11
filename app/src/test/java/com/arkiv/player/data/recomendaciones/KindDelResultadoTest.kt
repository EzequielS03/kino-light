package com.arkiv.player.data.recomendaciones

import com.arkiv.player.data.gateway.GatewayResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * `MagisFuente` arma cada resultado con `kind = ctx.type` (el tipo BUSCADO, no el del ítem): al
 * buscar una serie, las películas del pool de Magis llegan etiquetadas "tv". [kindRealDelRef] lee
 * el `kind` de verdad desde el propio `ref`, antes de que el árbitro vea la lista.
 */
class KindDelResultadoTest {

    @Test fun `un ref de magis de pelicula da movie`() {
        assertEquals("movie", kindRealDelRef("magis1:movie:0:abc"))
    }

    @Test fun `un ref de magis de serie da tv`() {
        assertEquals("tv", kindRealDelRef("magis1:teleplay:3:abc"))
    }

    @Test fun `un ref de caracol de pelicula da movie`() {
        assertEquals("movie", kindRealDelRef("ditu1:VOD:abc"))
    }

    @Test fun `un ref de caracol de serie da tv`() {
        assertEquals("tv", kindRealDelRef("ditu1:GROUP_OF_BUNDLES:abc"))
    }

    @Test fun `un ref que no se entiende da null`() {
        assertNull(kindRealDelRef("torrent:magnet-cualquiera"))
    }

    @Test fun `conKindReal corrige el kind del resultado`() {
        val r = GatewayResult(source = "magis", title = "X", ref = "magis1:teleplay:1:abc", kind = "movie")
        assertEquals("tv", conKindReal(r).kind)
    }

    @Test fun `conKindReal deja el kind igual si el ref no se entiende`() {
        val r = GatewayResult(source = "raro", title = "X", ref = "no-se-entiende", kind = "movie")
        assertEquals("movie", conKindReal(r).kind)
    }
}
