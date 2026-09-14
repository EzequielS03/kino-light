package com.arkiv.player.data.trivia

import com.arkiv.player.data.ia.AiResponse
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TriviaFactsTest {

    @get:Rule val carpeta = TemporaryFolder()

    private val pelicula = TriviaSubject(kind = "movie", tmdbId = 354912, canonicalTitle = null, season = null, episode = null)
    private val fichaCoco = WorkSheet(kind = "movie", name = "Coco")

    private class CacheEnMemoria : TriviaCache {
        val datos = mutableMapOf<String, List<String>>()
        override fun read(key: String) = datos[key]
        override fun save(key: String, data: List<String>) { this.datos[key] = data }
    }

    @Test fun `the key uses tmdbId when there is one`() {
        assertEquals("v3:movie:354912:0:0", pelicula.key)
        assertEquals("v3:tv:46260:1:2", TriviaSubject("tv", 46260, "Naruto", 1, 2).key)
    }

    @Test fun `with no tmdbId the key uses the canonical title`() {
        assertEquals("v3:tv:naruto:1:2", TriviaSubject("tv", null, "Naruto", 1, 2).key)
    }

    /** Asking blind is the fastest way for the model to make something up. */
    @Test fun `with no tmdbId or canonical title there is no subject`() {
        assertNull(TriviaSubject.of("movie", tmdbId = null, canonicalTitle = " ", season = null, episode = null))
        assertNull(TriviaSubject.of("movie", tmdbId = 0, canonicalTitle = null, season = null, episode = null))
    }

    /**
     * A movie can't receive a fake "episode": `EpisodeNumbering.episodeOf` deduces the chapter
     * from `displayName` ("Se7en" gives 7) with no idea whether the work is a series.
     * `TriviaSubject.of` is where `ArkivRepository.triviaSubjectFor` builds the work's identity, so
     * the filter goes here.
     */
    @Test fun `a movie cannot receive a fake episode or season`() {
        val o = TriviaSubject.of("movie", tmdbId = 807, canonicalTitle = null, season = 1, episode = 7)!!
        assertNull(o.episode)
        assertNull(o.season)
    }

    @Test fun `a series does keep its season and its episode`() {
        val o = TriviaSubject.of("tv", tmdbId = 46260, canonicalTitle = null, season = 1, episode = 2)!!
        assertEquals(1, o.season)
        assertEquals(2, o.episode)
    }

    @Test fun `a movie's prompt names the work and the rules`() {
        val i = TriviaPrompt.prompt(fichaCoco, season = null, episode = null)
        assertTrue(i.startsWith("Dame hasta 8 datos curiosos y verificables sobre «Coco»."))
        assertTrue(i.contains("de menos de 220 caracteres"))
        assertTrue(i.contains("SIN SPOILERS"))
    }

    @Test fun `a chapter's prompt names the season and episode`() {
        val ficha = WorkSheet(kind = "tv", name = "Naruto")
        assertTrue(TriviaPrompt.prompt(ficha, 1, 2).contains("sobre «Naruto», temporada 1, episodio 2."))
    }

    @Test fun `with no season it names only the episode`() {
        val ficha = WorkSheet(kind = "tv", name = "Dragon Ball")
        assertTrue(TriviaPrompt.prompt(ficha, null, 35).contains("sobre «Dragon Ball», episodio 35."))
    }

    @Test fun `the prompt names series and chapter and includes the sheet's lines`() {
        val ficha = WorkSheet(
            kind = "tv",
            name = "Naruto",
            releaseDate = "2002-10-03",
            creators = listOf("Masashi Kishimoto"),
            chapter = ChapterSheet(
                season = 1,
                episode = 2,
                name = "¡Soy Konohamaru!",
                date = "2002-10-10",
            ),
        )
        val i = TriviaPrompt.prompt(ficha, 1, 2)
        // Names the series AND the chapter's name, not just the numbers.
        assertTrue(i.contains("sobre «Naruto», temporada 1, episodio 2, «¡Soy Konohamaru!»."))
        // The sheet goes underneath, presented as verified TMDB data.
        assertTrue(i.contains("Datos verificados de TMDB"))
        assertTrue(i.contains(ficha.lines()))
    }

    @Test fun `the prompt asks for people's names in latin characters`() {
        val i = TriviaPrompt.prompt(fichaCoco, null, null)
        assertTrue(i.contains("caracteres latinos"))
    }

    @Test fun `asks for up to 8, not exactly 8`() {
        val i = TriviaPrompt.prompt(fichaCoco, null, null)
        assertTrue(i.contains("Dame hasta 8"))
        assertTrue(!i.contains("Dame 8 "))
    }

    @Test fun `asks to not contradict the sheet and to prefer empty over making things up`() {
        val i = TriviaPrompt.prompt(fichaCoco, null, null)
        assertTrue(i.contains("nunca contradigas"))
        assertTrue(i.contains("no estás seguro"))
        assertTrue(i.lowercase().contains("arreglo vacío") || i.lowercase().contains("[]"))
    }

    @Test fun `clean keeps short strings and at most eight`() {
        val largo = "x".repeat(221)
        val arr = JSONArray(listOf(" uno ", 2, largo, "", "tres") + (4..12).map { "dato $it" })
        val limpio = TriviaPrompt.clean(arr)
        assertEquals("uno", limpio.first())
        assertTrue(limpio.none { it.length > 220 || it.isBlank() })
        assertEquals(8, limpio.size)
    }

    @Test fun `a good answer is cleaned and saved`() = runTest {
        val cache = CacheEnMemoria()
        val datos = TriviaFacts(ia = { AiResponse.Text("""["a","b"]""", "m") }, cache = cache)
        assertEquals(listOf("a", "b"), datos.of(pelicula) { fichaCoco })
        assertEquals(listOf("a", "b"), cache.datos[pelicula.key])
    }

    /** The sheet can cost up to 2 TMDB calls: with a cache hit it isn't requested. */
    @Test fun `with a cache hit neither the model nor the sheet is asked`() = runTest {
        val cache = CacheEnMemoria().apply { datos[pelicula.key] = listOf("guardado") }
        var preguntas = 0
        var fichas = 0
        val datos = TriviaFacts(ia = { preguntas++; AiResponse.Unable }, cache = cache)
        assertEquals(listOf("guardado"), datos.of(pelicula) { fichas++; fichaCoco })
        assertEquals(0, preguntas)
        assertEquals(0, fichas)
    }

    @Test fun `with no sheet the model is not asked`() = runTest {
        var preguntas = 0
        val datos = TriviaFacts(ia = { preguntas++; AiResponse.Unable }, cache = CacheEnMemoria())
        assertTrue(datos.of(pelicula) { null }.isEmpty())
        assertEquals(0, preguntas)
    }

    /** Sealing a failure would leave the work with no trivia for a month over a thirty-second outage. */
    @Test fun `a failure is not saved`() = runTest {
        val cache = CacheEnMemoria()
        val datos = TriviaFacts(ia = { AiResponse.Unable }, cache = cache)
        assertTrue(datos.of(pelicula) { fichaCoco }.isEmpty())
        assertNull(cache.datos[pelicula.key])
    }

    @Test fun `an unreadable answer is not saved`() = runTest {
        val cache = CacheEnMemoria()
        val datos = TriviaFacts(ia = { AiResponse.Text("no sé", "m") }, cache = cache)
        assertTrue(datos.of(pelicula) { fichaCoco }.isEmpty())
        assertNull(cache.datos[pelicula.key])
    }

    /** A `[]` is a legitimate answer ("I have nothing sure"): saving it avoids asking again. */
    @Test fun `an empty array from the model is saved and the second call does not ask`() = runTest {
        val cache = CacheEnMemoria()
        var preguntas = 0
        val datos = TriviaFacts(ia = { preguntas++; AiResponse.Text("[]", "m") }, cache = cache)
        assertTrue(datos.of(pelicula) { fichaCoco }.isEmpty())
        assertEquals(1, preguntas)
        assertEquals(emptyList<String>(), cache.datos[pelicula.key])
        // Second call: there's already a cache entry (even if empty), it isn't asked again.
        assertTrue(datos.of(pelicula) { fichaCoco }.isEmpty())
        assertEquals(1, preguntas)
    }

    /** An array that carried data and ended up empty after cleaning is the model stumbling, not a
     *  "I don't know": it isn't saved, so it's retried next time. */
    @Test fun `an array that ends up empty after cleaning is not saved`() = runTest {
        val cache = CacheEnMemoria()
        val largo = "x".repeat(221)
        val datos = TriviaFacts(ia = { AiResponse.Text("""["$largo"]""", "m") }, cache = cache)
        assertTrue(datos.of(pelicula) { fichaCoco }.isEmpty())
        assertNull(cache.datos[pelicula.key])
    }

    /**
     * A degraded sheet (series with no chapter requested, because that call failed) is still
     * asked about with what there is, but the answer is NOT saved: saving it would leave THAT
     * chapter with generic series facts for a month, over a failure the next opening might not
     * repeat.
     */
    @Test fun `a degraded sheet is not saved`() = runTest {
        val cache = CacheEnMemoria()
        val serieDegradada = TriviaSubject(kind = "tv", tmdbId = 46260, canonicalTitle = null, season = 1, episode = 2)
        val fichaDegradada = WorkSheet(kind = "tv", name = "Naruto", degraded = true)
        var preguntas = 0
        val datos = TriviaFacts(ia = { preguntas++; AiResponse.Text("""["a","b"]""", "m") }, cache = cache)
        assertEquals(listOf("a", "b"), datos.of(serieDegradada) { fichaDegradada })
        assertEquals(1, preguntas)
        assertNull(cache.datos[serieDegradada.key])
    }

    @Test fun `the disk cache expires after thirty days`() {
        var ahora = 0L
        val cache = DiskTriviaCache(carpeta.root) { ahora }
        cache.save("movie:1:0:0", listOf("dato"))
        ahora += 30L * 24 * 60 * 60 * 1000 - 1
        assertEquals(listOf("dato"), cache.read("movie:1:0:0"))
        ahora += 2
        assertNull(cache.read("movie:1:0:0"))
    }

    @Test fun `a key with unusual characters is still saved`() {
        val cache = DiskTriviaCache(carpeta.root) { 0L }
        cache.save("tv:el/niño:1:2", listOf("x"))
        assertEquals(listOf("x"), cache.read("tv:el/niño:1:2"))
    }
}
