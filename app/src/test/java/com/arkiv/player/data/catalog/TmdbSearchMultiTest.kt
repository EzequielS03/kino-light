package com.arkiv.player.data.catalog

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TmdbSearchMultiTest {
    private val api = TmdbApi(gatewayUrl = { "http://gateway" }, arkivKey = { "llave" })

    @Test fun `mapea movie con titulo y year`() {
        val o = JSONObject("""{"media_type":"movie","id":1,"title":"Superman","release_date":"2025-07-11","poster_path":"/p.jpg"}""")
        val item = api.parseMultiItem(o)!!
        assertEquals(1, item.id)
        assertEquals("movie", item.type)
        assertEquals("Superman", item.title)
        assertEquals("2025", item.year)
        assertEquals(false, item.isSeries)
    }

    @Test fun `mapea tv usando name y first_air_date`() {
        val o = JSONObject("""{"media_type":"tv","id":2,"name":"Superman & Lois","first_air_date":"2021-02-23"}""")
        val item = api.parseMultiItem(o)!!
        assertEquals("tv", item.type)
        assertEquals("Superman & Lois", item.title)
        assertEquals("2021", item.year)
        assertEquals(true, item.isSeries)
    }

    @Test fun `descarta person y media_type desconocido`() {
        assertNull(api.parseMultiItem(JSONObject("""{"media_type":"person","id":3,"name":"Actor X"}""")))
        assertNull(api.parseMultiItem(JSONObject("""{"media_type":"collection","id":4,"name":"Colección"}""")))
    }
}
