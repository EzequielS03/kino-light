package com.arkiv.player.data.trivia

import com.arkiv.player.data.ia.RespuestaDeIa
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DatosCuriososTest {

    @get:Rule val carpeta = TemporaryFolder()

    private val pelicula = ObraDeDatos(tipo = "movie", tmdbId = 354912, tituloCanonico = null, temporada = null, episodio = null)

    private class CacheEnMemoria : CacheDeDatos {
        val datos = mutableMapOf<String, List<String>>()
        override fun leer(clave: String) = datos[clave]
        override fun guardar(clave: String, datos: List<String>) { this.datos[clave] = datos }
    }

    @Test fun `la clave usa el tmdbId cuando lo hay`() {
        assertEquals("movie:354912:0:0", pelicula.clave)
        assertEquals("tv:46260:1:2", ObraDeDatos("tv", 46260, "Naruto", 1, 2).clave)
    }

    @Test fun `sin tmdbId la clave usa el titulo canonico`() {
        assertEquals("tv:naruto:1:2", ObraDeDatos("tv", null, "Naruto", 1, 2).clave)
    }

    /** Preguntar a ciegas es la forma más rápida de que el modelo invente. */
    @Test fun `sin tmdbId ni titulo canonico no hay obra`() {
        assertNull(ObraDeDatos.de("movie", tmdbId = null, tituloCanonico = " ", temporada = null, episodio = null))
        assertNull(ObraDeDatos.de("movie", tmdbId = 0, tituloCanonico = null, temporada = null, episodio = null))
    }

    @Test fun `la instruccion de una pelicula nombra la obra y las reglas`() {
        val i = PreguntaDeDatos.instruccion("Coco", temporada = null, episodio = null)
        assertTrue(i.startsWith("Dame 8 datos curiosos y verificables sobre Coco."))
        assertTrue(i.contains("de menos de 220 caracteres"))
        assertTrue(i.contains("SIN SPOILERS"))
    }

    @Test fun `la instruccion de un capitulo nombra temporada y episodio`() {
        assertTrue(PreguntaDeDatos.instruccion("Naruto", 1, 2).contains("sobre Naruto, temporada 1, episodio 2."))
    }

    @Test fun `sin temporada nombra solo el episodio`() {
        assertTrue(PreguntaDeDatos.instruccion("Dragon Ball", null, 35).contains("sobre Dragon Ball, episodio 35."))
    }

    @Test fun `limpiar se queda con cadenas cortas y como maximo ocho`() {
        val largo = "x".repeat(221)
        val arr = JSONArray(listOf(" uno ", 2, largo, "", "tres") + (4..12).map { "dato $it" })
        val limpio = PreguntaDeDatos.limpiar(arr)
        assertEquals("uno", limpio.first())
        assertTrue(limpio.none { it.length > 220 || it.isBlank() })
        assertEquals(8, limpio.size)
    }

    @Test fun `una respuesta buena se limpia y se guarda`() = runTest {
        val cache = CacheEnMemoria()
        val datos = DatosCuriosos(ia = { RespuestaDeIa.Texto("""["a","b"]""", "m") }, cache = cache)
        assertEquals(listOf("a", "b"), datos.de(pelicula) { "Coco" })
        assertEquals(listOf("a", "b"), cache.datos[pelicula.clave])
    }

    /** El nombre puede costar una llamada a TMDB: con caché no se pide. */
    @Test fun `con cache no se pregunta ni se pide el nombre`() = runTest {
        val cache = CacheEnMemoria().apply { datos[pelicula.clave] = listOf("guardado") }
        var preguntas = 0
        var nombres = 0
        val datos = DatosCuriosos(ia = { preguntas++; RespuestaDeIa.NoPude }, cache = cache)
        assertEquals(listOf("guardado"), datos.de(pelicula) { nombres++; "Coco" })
        assertEquals(0, preguntas)
        assertEquals(0, nombres)
    }

    @Test fun `sin nombre no se pregunta`() = runTest {
        var preguntas = 0
        val datos = DatosCuriosos(ia = { preguntas++; RespuestaDeIa.NoPude }, cache = CacheEnMemoria())
        assertTrue(datos.de(pelicula) { null }.isEmpty())
        assertEquals(0, preguntas)
    }

    /** Sellar un fallo dejaría a la obra sin trivia un mes por una caída de treinta segundos. */
    @Test fun `un fallo no se guarda`() = runTest {
        val cache = CacheEnMemoria()
        val datos = DatosCuriosos(ia = { RespuestaDeIa.NoPude }, cache = cache)
        assertTrue(datos.de(pelicula) { "Coco" }.isEmpty())
        assertNull(cache.datos[pelicula.clave])
    }

    @Test fun `una respuesta ilegible no se guarda`() = runTest {
        val cache = CacheEnMemoria()
        val datos = DatosCuriosos(ia = { RespuestaDeIa.Texto("no sé", "m") }, cache = cache)
        assertTrue(datos.de(pelicula) { "Coco" }.isEmpty())
        assertNull(cache.datos[pelicula.clave])
    }

    @Test fun `el cache en disco vence a los treinta dias`() {
        var ahora = 0L
        val cache = CacheDeDatosEnDisco(carpeta.root) { ahora }
        cache.guardar("movie:1:0:0", listOf("dato"))
        ahora += 30L * 24 * 60 * 60 * 1000 - 1
        assertEquals(listOf("dato"), cache.leer("movie:1:0:0"))
        ahora += 2
        assertNull(cache.leer("movie:1:0:0"))
    }

    @Test fun `una clave con caracteres raros se guarda igual`() {
        val cache = CacheDeDatosEnDisco(carpeta.root) { 0L }
        cache.guardar("tv:el/niño:1:2", listOf("x"))
        assertEquals(listOf("x"), cache.leer("tv:el/niño:1:2"))
    }
}
