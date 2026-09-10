package com.arkiv.player.data

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Si hay que identificar una obra como serie o película. Recuperados de
 * `TriviaDelPlayerTest.kt` (borrado junto con la feature de trivia en `light-magis`): la función
 * sobrevivió como `tipoDeObra` porque `ArkivRepository.obraDeTriviaPara` la sigue usando para
 * pedir los marcadores de intro/outro al gateway, así que su cobertura tenía que sobrevivir con
 * ella.
 */
class TipoDeObraTest {

    @Test
    fun `el tipo lo dice el item cuando lo sabe`() {
        // Lo escribe el gateway al canonizar, verificado contra TMDB: es mejor dato que
        // cualquier cosa que deduzcamos acá.
        assertEquals("tv", tipoDeObra(tipoDelItem = "tv", categoryOverride = null, episodio = null))
        assertEquals("movie", tipoDeObra(tipoDelItem = "movie", categoryOverride = null, episodio = 7))
    }

    @Test
    fun `sin tipo manda que haya numero de episodio`() {
        // Un ítem sin canonizar todavía: si tiene número de capítulo, es serie.
        assertEquals("tv", tipoDeObra(tipoDelItem = null, categoryOverride = null, episodio = 7))
        assertEquals("movie", tipoDeObra(tipoDelItem = "", categoryOverride = null, episodio = null))
    }

    @Test
    fun `una serie sin numero de capitulo NO se pide como pelicula`() {
        // Medido en producción: se pidió `movie:82452` para Avatar. En TMDB, tv:82452 es
        // "Avatar: La leyenda de Aang" y movie:82452 es "Savage Water", una película de
        // rafting de 1979 -- y eso fue lo que se le mostró a quien estaba viendo Avatar.
        // Un id de TMDB solo significa algo DENTRO de su catálogo.
        //
        // `categoryOverride` es lo que la app ya usa para decidir si algo es serie
        // (`LibraryRow.isMovie`); ignorarlo y mirar solo si ESTE capítulo trae número era
        // adivinar teniendo el dato al lado.
        assertEquals(
            "tv",
            tipoDeObra(tipoDelItem = null, categoryOverride = "series", episodio = null),
        )
    }

    @Test
    fun `el tipo canonico manda sobre categoryOverride`() {
        // El del ítem lo verificó el gateway contra TMDB; `categoryOverride` es lo que
        // dedujo la app o corrigió la persona.
        assertEquals(
            "movie",
            tipoDeObra(tipoDelItem = "movie", categoryOverride = "series", episodio = 3),
        )
    }

    @Test
    fun `sin ninguna senal sigue siendo pelicula`() {
        assertEquals("movie", tipoDeObra(null, categoryOverride = null, episodio = null))
    }
}
