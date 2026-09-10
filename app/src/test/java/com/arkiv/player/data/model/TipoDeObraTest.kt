package com.arkiv.player.data.model

import org.junit.Assert.assertEquals
import org.junit.Test

class TipoDeObraTest {

    @Test fun `el tipo lo dice el item cuando lo sabe`() {
        assertEquals("tv", TipoDeObra.de(tipoDelItem = "tv", categoryOverride = null, episodio = null))
        assertEquals("movie", TipoDeObra.de(tipoDelItem = "movie", categoryOverride = null, episodio = 7))
    }

    @Test fun `sin tipo manda que haya numero de episodio`() {
        assertEquals("tv", TipoDeObra.de(tipoDelItem = null, categoryOverride = null, episodio = 7))
        assertEquals("movie", TipoDeObra.de(tipoDelItem = "", categoryOverride = null, episodio = null))
    }

    /**
     * Medido en producción: se pidió `movie:82452` para Avatar. En TMDB, tv:82452 es "Avatar: La
     * leyenda de Aang" y movie:82452 es "Savage Water", una película de rafting de 1979.
     */
    @Test fun `una serie sin numero de capitulo NO se pide como pelicula`() {
        assertEquals("tv", TipoDeObra.de(tipoDelItem = null, categoryOverride = "series", episodio = null))
    }

    @Test fun `el tipo del item manda sobre categoryOverride`() {
        assertEquals("movie", TipoDeObra.de(tipoDelItem = "movie", categoryOverride = "series", episodio = 3))
    }

    @Test fun `sin ninguna senal sigue siendo pelicula`() {
        assertEquals("movie", TipoDeObra.de(null, categoryOverride = null, episodio = null))
    }
}
