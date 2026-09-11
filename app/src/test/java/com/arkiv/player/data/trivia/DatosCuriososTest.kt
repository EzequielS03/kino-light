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
    private val fichaCoco = FichaDeObra(tipo = "movie", nombre = "Coco")

    private class CacheEnMemoria : CacheDeDatos {
        val datos = mutableMapOf<String, List<String>>()
        override fun leer(clave: String) = datos[clave]
        override fun guardar(clave: String, datos: List<String>) { this.datos[clave] = datos }
    }

    @Test fun `la clave usa el tmdbId cuando lo hay`() {
        assertEquals("v3:movie:354912:0:0", pelicula.clave)
        assertEquals("v3:tv:46260:1:2", ObraDeDatos("tv", 46260, "Naruto", 1, 2).clave)
    }

    @Test fun `sin tmdbId la clave usa el titulo canonico`() {
        assertEquals("v3:tv:naruto:1:2", ObraDeDatos("tv", null, "Naruto", 1, 2).clave)
    }

    /** Preguntar a ciegas es la forma más rápida de que el modelo invente. */
    @Test fun `sin tmdbId ni titulo canonico no hay obra`() {
        assertNull(ObraDeDatos.de("movie", tmdbId = null, tituloCanonico = " ", temporada = null, episodio = null))
        assertNull(ObraDeDatos.de("movie", tmdbId = 0, tituloCanonico = null, temporada = null, episodio = null))
    }

    /**
     * Una película no puede recibir un "episodio" falso: `EpisodeNumbering.episodeOf` deduce el
     * capítulo del `displayName` ("Se7en" da 7) sin saber si la obra es una serie. `ObraDeDatos.de`
     * es donde `ArkivRepository.obraParaDatos` arma la identidad de la obra, así que el filtro va acá.
     */
    @Test fun `una pelicula no puede recibir un episodio ni una temporada falsos`() {
        val o = ObraDeDatos.de("movie", tmdbId = 807, tituloCanonico = null, temporada = 1, episodio = 7)!!
        assertNull(o.episodio)
        assertNull(o.temporada)
    }

    @Test fun `una serie si conserva su temporada y su episodio`() {
        val o = ObraDeDatos.de("tv", tmdbId = 46260, tituloCanonico = null, temporada = 1, episodio = 2)!!
        assertEquals(1, o.temporada)
        assertEquals(2, o.episodio)
    }

    @Test fun `la instruccion de una pelicula nombra la obra y las reglas`() {
        val i = PreguntaDeDatos.instruccion(fichaCoco, temporada = null, episodio = null)
        assertTrue(i.startsWith("Dame hasta 8 datos curiosos y verificables sobre «Coco»."))
        assertTrue(i.contains("de menos de 220 caracteres"))
        assertTrue(i.contains("SIN SPOILERS"))
    }

    @Test fun `la instruccion de un capitulo nombra temporada y episodio`() {
        val ficha = FichaDeObra(tipo = "tv", nombre = "Naruto")
        assertTrue(PreguntaDeDatos.instruccion(ficha, 1, 2).contains("sobre «Naruto», temporada 1, episodio 2."))
    }

    @Test fun `sin temporada nombra solo el episodio`() {
        val ficha = FichaDeObra(tipo = "tv", nombre = "Dragon Ball")
        assertTrue(PreguntaDeDatos.instruccion(ficha, null, 35).contains("sobre «Dragon Ball», episodio 35."))
    }

    @Test fun `la instruccion nombra serie y capitulo e incluye los renglones de la ficha`() {
        val ficha = FichaDeObra(
            tipo = "tv",
            nombre = "Naruto",
            fechaEstreno = "2002-10-03",
            creadores = listOf("Masashi Kishimoto"),
            capitulo = FichaDeCapitulo(
                temporada = 1,
                episodio = 2,
                nombre = "¡Soy Konohamaru!",
                fecha = "2002-10-10",
            ),
        )
        val i = PreguntaDeDatos.instruccion(ficha, 1, 2)
        // Nombra la serie Y el nombre del capítulo, no solo los números.
        assertTrue(i.contains("sobre «Naruto», temporada 1, episodio 2, «¡Soy Konohamaru!»."))
        // La ficha va debajo, presentada como datos verificados de TMDB.
        assertTrue(i.contains("Datos verificados de TMDB"))
        assertTrue(i.contains(ficha.renglones()))
    }

    @Test fun `la instruccion pide los nombres de personas en caracteres latinos`() {
        val i = PreguntaDeDatos.instruccion(fichaCoco, null, null)
        assertTrue(i.contains("caracteres latinos"))
    }

    @Test fun `pide hasta 8, no 8 exactos`() {
        val i = PreguntaDeDatos.instruccion(fichaCoco, null, null)
        assertTrue(i.contains("Dame hasta 8"))
        assertTrue(!i.contains("Dame 8 "))
    }

    @Test fun `pide no contradecir la ficha y preferir vacio a inventar`() {
        val i = PreguntaDeDatos.instruccion(fichaCoco, null, null)
        assertTrue(i.contains("nunca contradigas"))
        assertTrue(i.contains("no estás seguro"))
        assertTrue(i.lowercase().contains("arreglo vacío") || i.lowercase().contains("[]"))
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
        assertEquals(listOf("a", "b"), datos.de(pelicula) { fichaCoco })
        assertEquals(listOf("a", "b"), cache.datos[pelicula.clave])
    }

    /** La ficha puede costar hasta 2 llamadas a TMDB: con caché no se pide. */
    @Test fun `con cache no se pregunta ni se pide la ficha`() = runTest {
        val cache = CacheEnMemoria().apply { datos[pelicula.clave] = listOf("guardado") }
        var preguntas = 0
        var fichas = 0
        val datos = DatosCuriosos(ia = { preguntas++; RespuestaDeIa.NoPude }, cache = cache)
        assertEquals(listOf("guardado"), datos.de(pelicula) { fichas++; fichaCoco })
        assertEquals(0, preguntas)
        assertEquals(0, fichas)
    }

    @Test fun `sin ficha no se pregunta`() = runTest {
        var preguntas = 0
        val datos = DatosCuriosos(ia = { preguntas++; RespuestaDeIa.NoPude }, cache = CacheEnMemoria())
        assertTrue(datos.de(pelicula) { null }.isEmpty())
        assertEquals(0, preguntas)
    }

    /** Sellar un fallo dejaría a la obra sin trivia un mes por una caída de treinta segundos. */
    @Test fun `un fallo no se guarda`() = runTest {
        val cache = CacheEnMemoria()
        val datos = DatosCuriosos(ia = { RespuestaDeIa.NoPude }, cache = cache)
        assertTrue(datos.de(pelicula) { fichaCoco }.isEmpty())
        assertNull(cache.datos[pelicula.clave])
    }

    @Test fun `una respuesta ilegible no se guarda`() = runTest {
        val cache = CacheEnMemoria()
        val datos = DatosCuriosos(ia = { RespuestaDeIa.Texto("no sé", "m") }, cache = cache)
        assertTrue(datos.de(pelicula) { fichaCoco }.isEmpty())
        assertNull(cache.datos[pelicula.clave])
    }

    /** Un `[]` es una respuesta legítima ("no tengo nada seguro"): guardarla evita repreguntar. */
    @Test fun `un arreglo vacio del modelo se guarda y en la segunda llamada no se pregunta`() = runTest {
        val cache = CacheEnMemoria()
        var preguntas = 0
        val datos = DatosCuriosos(ia = { preguntas++; RespuestaDeIa.Texto("[]", "m") }, cache = cache)
        assertTrue(datos.de(pelicula) { fichaCoco }.isEmpty())
        assertEquals(1, preguntas)
        assertEquals(emptyList<String>(), cache.datos[pelicula.clave])
        // Segunda llamada: ya hay caché (aunque sea vacío), no se vuelve a preguntar.
        assertTrue(datos.de(pelicula) { fichaCoco }.isEmpty())
        assertEquals(1, preguntas)
    }

    /** Un arreglo que traía datos y quedó vacío tras limpiar es un tropiezo del modelo, no un
     *  "no sé": no se guarda, para reintentar la próxima vez. */
    @Test fun `un arreglo que queda vacio tras limpiar no se guarda`() = runTest {
        val cache = CacheEnMemoria()
        val largo = "x".repeat(221)
        val datos = DatosCuriosos(ia = { RespuestaDeIa.Texto("""["$largo"]""", "m") }, cache = cache)
        assertTrue(datos.de(pelicula) { fichaCoco }.isEmpty())
        assertNull(cache.datos[pelicula.clave])
    }

    /**
     * Una ficha degradada (serie sin el capítulo pedido, porque esa llamada falló) igual se le
     * pregunta a Kilo con lo que hay, pero la respuesta NO se guarda: guardarla dejaría a ESE
     * capítulo con datos genéricos de la serie un mes, por una falla que la próxima apertura podría
     * no repetir.
     */
    @Test fun `una ficha degradada no se guarda`() = runTest {
        val cache = CacheEnMemoria()
        val serieDegradada = ObraDeDatos(tipo = "tv", tmdbId = 46260, tituloCanonico = null, temporada = 1, episodio = 2)
        val fichaDegradada = FichaDeObra(tipo = "tv", nombre = "Naruto", degradada = true)
        var preguntas = 0
        val datos = DatosCuriosos(ia = { preguntas++; RespuestaDeIa.Texto("""["a","b"]""", "m") }, cache = cache)
        assertEquals(listOf("a", "b"), datos.de(serieDegradada) { fichaDegradada })
        assertEquals(1, preguntas)
        assertNull(cache.datos[serieDegradada.clave])
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
