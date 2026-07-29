package com.arkiv.player.data.catalog.web

import com.arkiv.player.data.catalog.providers.ContentType
import com.arkiv.player.data.catalog.providers.SearchContext
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WebSourceEngineTest {
    private class MapFetcher(val pages: Map<String, String>) : PageFetcher {
        override suspend fun fetch(url: String, charset: String, headers: Map<String, String>): String? = pages[url]
    }
    private fun def(id: String, host: String) = WebSourceDefinition.fromJson(JSONObject("""
      {"id":"$id","baseUrl":"$host","browse":{"movie":"/p?page={page}"},"keywords":{"movie":"{title}"},
       "parser":{"rowSelector":"article","title":{"selector":"h2","attr":"text"},
         "pageUrl":{"selector":"a","attr":"href","resolve":"absolute"}}}
    """.trimIndent()))!!

    private fun html(t: String) = """<article><a href="/x"></a><h2>$t</h2></article>"""

    @Test fun `browse agrega resultados de todas las webs activas`() = runBlocking {
        val defs = listOf(def("a", "https://a.tld"), def("b", "https://b.tld"))
        val f = MapFetcher(mapOf(
            "https://a.tld/p?page=1" to html("DesdeA"),
            "https://b.tld/p?page=1" to html("DesdeB"),
        ))
        val rows = WebSourceEngine(f) { defs }.browse("movie", 1)
        assertEquals(setOf("DesdeA", "DesdeB"), rows.map { it.title }.toSet())
    }

    @Test fun `una web caida no rompe a las demas`() = runBlocking {
        val defs = listOf(def("a", "https://a.tld"), def("b", "https://b.tld"))
        val f = MapFetcher(mapOf("https://b.tld/p?page=1" to html("DesdeB"))) // 'a' no responde
        val rows = WebSourceEngine(f) { defs }.browse("movie", 1)
        assertEquals(listOf("DesdeB"), rows.map { it.title })
    }

    @Test fun `ignora definiciones deshabilitadas`() = runBlocking {
        val disabled = WebSourceDefinition.fromJson(JSONObject("""
          {"id":"off","baseUrl":"https://off.tld","enabled":false,"browse":{"movie":"/p?page={page}"},
           "keywords":{"movie":"{title}"},"parser":{"rowSelector":"article",
             "title":{"selector":"h2","attr":"text"},"pageUrl":{"selector":"a","attr":"href"}}}
        """.trimIndent()))!!
        val f = MapFetcher(mapOf("https://off.tld/p?page=1" to html("NoDebeSalir")))
        assertEquals(0, WebSourceEngine(f) { listOf(disabled) }.browse("movie", 1).size)
    }

    @Test fun `searchFlow cachea y la segunda busqueda no re-consulta`() = runBlocking {
        val d = WebSourceDefinition.fromJson(JSONObject("""
          {"id":"a","baseUrl":"https://a.tld","search":"/s?q={query}","keywords":{"movie":"{title}"},
           "parser":{"rowSelector":"article","title":{"selector":"h2","attr":"text"},
             "pageUrl":{"selector":"a","attr":"href","resolve":"absolute"}}}
        """.trimIndent()))!!
        var fetches = 0
        val f = object : PageFetcher {
            override suspend fun fetch(url: String, charset: String, headers: Map<String, String>): String? {
                fetches++
                return if (url.contains("/s?q=")) html("Coco 2017") else null
            }
        }
        val engine = WebSourceEngine(f) { listOf(d) }
        val ctx = SearchContext(titles = listOf("Coco"), type = ContentType.MOVIE, year = "2017")
        val first = engine.searchFlow(ctx).toList().flatten()
        assertTrue(first.isNotEmpty())
        val afterFirst = fetches
        // Misma búsqueda otra vez -> cache hit: mismos resultados y CERO nuevos fetches.
        val second = engine.searchFlow(ctx).toList().flatten()
        assertEquals(first.map { it.title }, second.map { it.title })
        assertEquals("no debe re-consultar las webs (cache)", afterFirst, fetches)
    }
}
