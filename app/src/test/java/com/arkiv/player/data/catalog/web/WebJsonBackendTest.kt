package com.arkiv.player.data.catalog.web

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WebJsonBackendTest {
    // Definición modo-API estilo lamovie (data.posts con original_title/_id).
    private val def = WebSourceDefinition.fromJson(
        JSONObject(
            """
            {"id":"lamovie","name":"LaMovie","baseUrl":"https://la.movie",
             "languageTokens":["latino"],
             "api":{
               "browse":{"movie":"/wp-api/v1/listing/movies?page={page}&postType=movies"},
               "search":"/wp-api/v1/search?q={query}&postType=any",
               "listPath":"data.posts",
               "title":"original_title",
               "pageUrl":"/wp-api/v1/player?postId={_id}&demo=0"
             }}
            """.trimIndent()
        )
    )

    @Test fun `definicion en modo api parsea sin parser html`() {
        assertNotNull("una def con api pero sin parser debe ser válida", def)
        assertNotNull(def!!.api)
        assertEquals("data.posts", def.api!!.listPath)
    }

    @Test fun `parse navega data posts y arma pageUrl desde _id`() {
        val json = JSONObject(
            """
            {"data":{"posts":[
              {"_id":29285,"original_title":"Duna","type":"movies","lang":"lat"},
              {"_id":7606,"original_title":"Loki","type":"tvshows","lang":"cast"}
            ]}}
            """.trimIndent()
        )
        val rows = WebJsonBackend.parse(def!!, def.api!!, json, "movie")
        assertEquals(2, rows.size)
        assertEquals("Duna", rows[0].title)
        assertEquals("https://la.movie/wp-api/v1/player?postId=29285&demo=0", rows[0].pageUrl)
        assertEquals("LAT", rows[0].language)
        assertEquals("Loki", rows[1].title)
        assertTrue("poster/year vacíos → los llena TMDB", rows[0].posterUrl.isEmpty())
    }

    @Test fun `parse descarta items sin titulo y lista vacia si la ruta no existe`() {
        val sinTitulo = JSONObject("""{"data":{"posts":[{"_id":1}]}}""")
        assertEquals(0, WebJsonBackend.parse(def!!, def.api!!, sinTitulo, "movie").size)
        val rutaMala = JSONObject("""{"otra":{"cosa":[]}}""")
        assertEquals(0, WebJsonBackend.parse(def, def.api!!, rutaMala, "movie").size)
    }

    @Test fun `jsonPath navega puntos y devuelve null en ruta inexistente`() {
        val o = JSONObject("""{"a":{"b":{"c":"ok"}}}""")
        assertEquals("ok", WebJsonBackend.jsonPath(o, "a.b.c"))
        assertEquals(null, WebJsonBackend.jsonPath(o, "a.x.y"))
    }

    @Test fun `allcalidad parsea data posts con titulo espanol y year de release_date`() {
        val defJson = """
        {"id":"allcalidad","name":"AllCalidad","enabled":true,"priority":55,
         "baseUrl":"https://allcalidad.re","hostAlt":[],"needsCloudflare":false,
         "languageTokens":["latino"],
         "api":{"search":"/api/rest/search?query={query}","browse":{},
                "listPath":"data.posts","title":"title",
                "pageUrl":"/api/rest/player?post_id={_id}&_any=1",
                "poster":"images.poster","year":"release_date"}}
        """.trimIndent()
        val def = WebSourceRegistry.parseDefinitions("[$defJson]").firstOrNull()
        assertNotNull(def); assertNotNull(def!!.api)
        val json = org.json.JSONObject(
            """{"data":{"posts":[
                 {"_id":27622,"title":"Matrix recargado (2003)","original_title":"The Matrix Reloaded",
                  "release_date":"2003-05-15","images":{"poster":"/thumbs/x.webp"}}
               ]}}"""
        )
        val rows = WebJsonBackend.parse(def, def.api!!, json, "movie")
        assertEquals(1, rows.size)
        assertEquals("Matrix recargado (2003)", rows[0].title)   // campo español, NO original_title
        assertEquals("2003", rows[0].year)                       // regex 4 dígitos sobre release_date
        assertEquals("https://allcalidad.re/api/rest/player?post_id=27622&_any=1", rows[0].pageUrl)
        assertEquals("LAT", rows[0].language)
    }
}
