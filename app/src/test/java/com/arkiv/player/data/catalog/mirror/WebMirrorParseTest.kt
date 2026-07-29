package com.arkiv.player.data.catalog.mirror

import org.junit.Assert.assertEquals
import org.junit.Test

class WebMirrorParseTest {
    @Test fun `parsea web_sources del detalle`() {
        val json = """{"web_sources":[{"site_id":"serieskao",
          "page_url":"https://serieskao.top/anime/naruto/temporada/1/capitulo/1",
          "season":1,"episode":1,"name":"e1","quality":"","lang_norm":"latino"}]}"""
        val list = MirrorApiClient.parseWebSources(json)
        assertEquals(1, list.size)
        assertEquals("serieskao", list[0].siteId)
        assertEquals(1 to 1, list[0].season to list[0].episode)
    }

    // Guarda de regresión de la lectura null-safe: en Android optString() devuelve el literal "null"
    // para un JSON null (en la JVM devuelve ""), así que este test NO puede fallar en rojo aquí; sirve
    // para fijar el contrato "null → cadena vacía" que la app depende para no renderizar "null".
    @Test fun `null en name quality lang_norm no se convierte en el string literal null`() {
        val json = """{"web_sources":[{"site_id":"serieskao",
          "page_url":"https://serieskao.top/anime/naruto/temporada/1/capitulo/1",
          "season":1,"episode":1,"name":null,"quality":null,"lang_norm":null}]}"""
        val list = MirrorApiClient.parseWebSources(json)
        assertEquals(1, list.size)
        assertEquals("", list[0].name)
        assertEquals("", list[0].quality)
        assertEquals("", list[0].langNorm)
    }
}
