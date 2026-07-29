package com.arkiv.player.data.catalog.web

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WebHtmlParserTest {
    private fun def(json: String) = WebSourceDefinition.fromJson(JSONObject(json))!!

    private val cssDef = def("""
      {"id":"cine","baseUrl":"https://cine.tld","browse":{"movie":"/p/{page}"},
       "keywords":{"movie":"{title}"},
       "parser":{"rowSelector":"article.item",
         "title":{"selector":"h2","attr":"text"},
         "pageUrl":{"selector":"a","attr":"href","resolve":"absolute"},
         "poster":{"selector":"img","attr":"src","resolve":"absolute"},
         "year":{"selector":".year","attr":"text","regex":"\\d{4}"},
         "quality":{"selector":".cal","attr":"text"}}}
    """.trimIndent())

    @Test fun `extrae filas por rowSelector con campos`() {
        val html = """
          <div>
            <article class="item"><a href="/pelicula/coco"><img src="/c.jpg"></a>
               <h2>Coco</h2><span class="year">Estreno 2017</span><span class="cal">HD</span></article>
            <article class="item"><a href="/pelicula/up"><img src="/u.jpg"></a>
               <h2>Up</h2><span class="year">2009</span></article>
          </div>
        """.trimIndent()
        val rows = WebHtmlParser.parse(cssDef, html, "movie")
        assertEquals(2, rows.size)
        assertEquals("Coco", rows[0].title)
        assertEquals("https://cine.tld/pelicula/coco", rows[0].pageUrl)
        assertEquals("https://cine.tld/c.jpg", rows[0].posterUrl)
        assertEquals("2017", rows[0].year)
        assertEquals("HD", rows[0].quality)
        assertEquals("movie", rows[0].kind)
        assertEquals("cine", rows[0].siteId)
        assertEquals("", rows[1].quality) // sin nodo .cal
    }

    @Test fun `descarta filas sin title o sin pageUrl`() {
        val html = """<article class="item"><h2>SinLink</h2></article>"""
        assertEquals(0, WebHtmlParser.parse(cssDef, html, "movie").size)
    }

    @Test fun `extrae filas por rowRegex mapeando grupos a campos`() {
        val rxDef = def("""
          {"id":"rx","baseUrl":"https://rx.tld","browse":{"movie":"/p/{page}"},
           "keywords":{"movie":"{title}"},
           "parser":{"rowRegex":"<a href=\"([^\"]+)\"><h3>([^<]+)</h3><i>(\\d{4})",
             "rowRegexFields":["pageUrl","title","year"]}}
        """.trimIndent())
        val html = """<a href="/pelicula/x"><h3>Peli X</h3><i>2020</i>
                      <a href="/pelicula/y"><h3>Peli Y</h3><i>2021</i>"""
        val rows = WebHtmlParser.parse(rxDef, html, "movie")
        assertEquals(2, rows.size)
        assertEquals("Peli X", rows[0].title)
        assertEquals("https://rx.tld/pelicula/x", rows[0].pageUrl) // resuelto absoluto
        assertEquals("2020", rows[0].year)
        assertEquals("Peli Y", rows[1].title)
    }

    @Test fun `rowSelector que no matchea devuelve vacio`() {
        assertTrue(WebHtmlParser.parse(cssDef, "<div>nada</div>", "movie").isEmpty())
    }
}
