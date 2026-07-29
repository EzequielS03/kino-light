package com.arkiv.player.data.catalog.web

import com.arkiv.player.data.catalog.providers.ContentType
import com.arkiv.player.data.catalog.providers.SearchContext
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WebSourceBackendTest {
    // Fetcher fake: devuelve HTML por URL, o null (fallo) para simular dominio caído.
    private class FakeFetcher(val pages: Map<String, String>) : PageFetcher {
        val hits = mutableListOf<String>()
        override suspend fun fetch(url: String, charset: String, headers: Map<String, String>): String? { hits += url; return pages[url] }
    }

    private val def = WebSourceDefinition.fromJson(JSONObject("""
      {"id":"cine","baseUrl":"https://cine.tld","hostAlt":["https://cine2.tld"],
       "browse":{"movie":"/peliculas?page={page}"},"search":"/?s={query}",
       "keywords":{"movie":"{title} {year}"},
       "parser":{"rowSelector":"article.item",
         "title":{"selector":"h2","attr":"text"},
         "pageUrl":{"selector":"a","attr":"href","resolve":"absolute"}}}
    """.trimIndent()))!!

    private val gridHtml = """<article class="item"><a href="/pelicula/coco"></a><h2>Coco</h2></article>"""

    @Test fun `browse arma la URL de browse y parsea`() = runBlocking {
        val f = FakeFetcher(mapOf("https://cine.tld/peliculas?page=1" to gridHtml))
        val rows = WebSourceBackend(def, f).browse("movie", 1)
        assertEquals(1, rows.size)
        assertEquals("Coco", rows[0].title)
    }

    @Test fun `search sustituye query y parsea`() = runBlocking {
        val url = "https://cine.tld/?s=Coco%202017"
        val f = FakeFetcher(mapOf(url to gridHtml))
        val ctx = SearchContext(titles = listOf("Coco"), type = ContentType.MOVIE, year = "2017")
        val rows = WebSourceBackend(def, f).search(ctx)
        assertTrue(rows.any { it.title == "Coco" })
    }

    @Test fun `hostAlt como fallback cuando el dominio principal falla`() = runBlocking {
        val altUrl = "https://cine2.tld/peliculas?page=1"
        val f = FakeFetcher(mapOf(altUrl to gridHtml)) // el host principal NO responde
        val rows = WebSourceBackend(def, f).browse("movie", 1)
        assertEquals(1, rows.size)
        assertTrue(f.hits.contains("https://cine.tld/peliculas?page=1")) // intentó el principal primero
        assertTrue(f.hits.contains(altUrl))                              // y cayó al alt
    }

    @Test fun `browse de un kind sin plantilla devuelve vacio`() = runBlocking {
        val f = FakeFetcher(emptyMap())
        assertTrue(WebSourceBackend(def, f).browse("tv", 1).isEmpty())
    }
}
