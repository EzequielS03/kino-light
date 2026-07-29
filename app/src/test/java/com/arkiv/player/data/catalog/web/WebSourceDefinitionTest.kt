package com.arkiv.player.data.catalog.web

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WebSourceDefinitionTest {
    private fun def(json: String) = WebSourceDefinition.fromJson(JSONObject(json))

    @Test fun `parsea definicion valida con rowSelector`() {
        val d = def("""
          {"id":"cine","name":"Cine","priority":60,"baseUrl":"https://cine.tld/",
           "hostAlt":["https://cine2.tld"],"needsCloudflare":true,
           "languageTokens":["latino","castellano"],
           "browse":{"movie":"/peliculas?page={page}","tv":"/series?page={page}"},
           "search":"/?s={query}","keywords":{"movie":"{title} {year}","tv":"{title}"},
           "parser":{"rowSelector":"article.item",
             "title":{"selector":"h2","attr":"text"},
             "pageUrl":{"selector":"a","attr":"href","resolve":"absolute"},
             "poster":{"selector":"img","attr":"src"}}}
        """.trimIndent())!!
        assertEquals("cine", d.id)
        assertEquals("https://cine.tld", d.baseUrl) // trailing slash recortado
        assertTrue(d.enabled) // default true
        assertEquals(listOf("https://cine2.tld"), d.hostAlt)
        assertEquals("/peliculas?page={page}", d.browse["movie"])
        assertEquals("article.item", d.parser.rowSelector)
        assertEquals("h2", d.parser.title!!.selector)
    }

    @Test fun `parsea definicion con rowRegex y rowRegexFields`() {
        val d = def("""
          {"id":"rx","baseUrl":"https://rx.tld","browse":{"movie":"/p/{page}"},
           "keywords":{"movie":"{title}"},
           "parser":{"rowRegex":"<a href=\"([^\"]+)\".*?<h3>([^<]+)",
             "rowRegexFields":["pageUrl","title"]}}
        """.trimIndent())!!
        assertEquals("<a href=\"([^\"]+)\".*?<h3>([^<]+)", d.parser.rowRegex)
        assertEquals(listOf("pageUrl", "title"), d.parser.rowRegexFields)
    }

    @Test fun `rechaza sin id o baseUrl o parser`() {
        assertNull(def("""{"baseUrl":"https://x.tld","parser":{"rowSelector":"a"}}"""))
        assertNull(def("""{"id":"x","parser":{"rowSelector":"a"}}"""))
        assertNull(def("""{"id":"x","baseUrl":"https://x.tld"}"""))
    }

    @Test fun `rechaza baseUrl no http`() {
        assertNull(def("""{"id":"x","baseUrl":"ftp://x.tld","parser":{"rowSelector":"a"}}"""))
    }

    @Test fun `rechaza parser sin rowSelector ni rowRegex`() {
        assertNull(def("""{"id":"x","baseUrl":"https://x.tld","parser":{"title":{"selector":"h2","attr":"text"}}}"""))
    }
}
