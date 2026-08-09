package com.arkiv.player.data.gateway

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NdjsonParserTest {

    @Test
    fun `parsea source_start`() {
        val ev = parseSearchEvent("""{"type":"source_start","source":"torrent"}""")
        assertEquals(SearchEvent.SourceStart("torrent"), ev)
    }

    @Test
    fun `parsea un result con todos sus campos`() {
        val linea = """{"type":"result","source":"torrent","item":{"source":"torrent",""" +
            """"title":"Duna (2021) WEB-DL 4k HDR","ref":"abc.def","kind":"movie","lang":"LATINO",""" +
            """"quality":"WEB-DL 4k HDR","size_bytes":123,"seeders":12,"year":"2021","season":0,""" +
            """"episode":0,"extra":{"infohash":"aa","backend":"mirror","is_pack":false}}}"""
        val ev = parseSearchEvent(linea) as SearchEvent.ResultEvent
        assertEquals("Duna (2021) WEB-DL 4k HDR", ev.item.title)
        assertEquals("abc.def", ev.item.ref)
        assertEquals("LATINO", ev.item.lang)
        assertEquals(12, ev.item.seeders)
        assertEquals(123L, ev.item.sizeBytes)
        assertEquals("aa", ev.item.extra["infohash"])
    }

    @Test
    fun `parsea source_done`() {
        val done = parseSearchEvent("""{"type":"source_done","source":"web","count":3,"ms":120}""")
        assertEquals(SearchEvent.SourceDone("web", 3, 120), done)
    }

    @Test
    fun `source_error lleva lo que alcanzo a entregar`() {
        val err = parseSearchEvent(
            """{"type":"source_error","source":"magis","error":"PortalError","ms":315,"count":2}"""
        )
        assertEquals(SearchEvent.SourceError("magis", "PortalError", 315, 2), err)
    }

    @Test
    fun `source_error sin count asume cero`() {
        val err = parseSearchEvent("""{"type":"source_error","source":"m","error":"timeout","ms":4000}""")
        assertEquals(0, (err as SearchEvent.SourceError).count)
    }

    @Test
    fun `parsea done`() {
        assertEquals(SearchEvent.Done(4100), parseSearchEvent("""{"type":"done","ms":4100}"""))
    }

    @Test
    fun `un tipo desconocido no explota`() {
        // El servidor puede sumar eventos nuevos sin que un APK viejo se caiga.
        assertTrue(parseSearchEvent("""{"type":"algo_nuevo","x":1}""") is SearchEvent.Unknown)
    }

    @Test
    fun `una linea corrupta no explota`() {
        assertTrue(parseSearchEvent("{no es json") is SearchEvent.Unknown)
    }

    @Test
    fun `un result sin campos opcionales toma los valores por defecto`() {
        val ev = parseSearchEvent(
            """{"type":"result","source":"magis","item":{"title":"Duna","ref":"r"}}"""
        ) as SearchEvent.ResultEvent
        assertEquals(0, ev.item.seeders)
        assertEquals("", ev.item.lang)
        assertEquals(emptyMap<String, String>(), ev.item.extra)
    }
}
