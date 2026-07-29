package com.arkiv.player.data.catalog.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WebResolverApiTest {
    @Test fun `parsea ok con streamUrl headers y subs`() {
        val r = WebResolverApi.parse("""
            {"ok":true,"streamUrl":"https://cdn/x.m3u8","headers":{"Referer":"https://h/"},
             "subtitles":[{"lang":"es","url":"https://cdn/es.vtt"}]}
        """.trimIndent())!!
        assertEquals("https://cdn/x.m3u8", r.streamUrl)
        assertEquals("https://h/", r.headers["Referer"])
        assertEquals(1, r.subtitles.size)
        assertEquals("es", r.subtitles[0].lang)
    }

    @Test fun `ok false o sin streamUrl devuelve null`() {
        assertNull(WebResolverApi.parse("""{"ok":false,"error":"no stream"}"""))
        assertNull(WebResolverApi.parse("""{"ok":true}"""))
        assertNull(WebResolverApi.parse("no json"))
    }
}
