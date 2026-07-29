package com.arkiv.player.data.catalog.web

import org.junit.Assert.assertEquals
import org.junit.Test

class WebSourceRegistryTest {
    private val a = """{"id":"a","baseUrl":"https://a.tld","priority":10,"browse":{"movie":"/p/{page}"},
        "keywords":{"movie":"{title}"},"parser":{"rowSelector":"i","title":{"selector":"h2","attr":"text"},
        "pageUrl":{"selector":"a","attr":"href"}}}"""
    private val b = """{"id":"b","baseUrl":"https://b.tld","priority":90,"browse":{"movie":"/p/{page}"},
        "keywords":{"movie":"{title}"},"parser":{"rowSelector":"i","title":{"selector":"h2","attr":"text"},
        "pageUrl":{"selector":"a","attr":"href"}}}"""

    @Test fun `parsea array descartando invalidas`() {
        val list = WebSourceRegistry.parseDefinitions("[$a, {\"id\":\"malo\"}, $b]")
        assertEquals(2, list.size)
    }

    @Test fun `merge la remota gana por id y ordena por prioridad desc`() {
        val bundled = WebSourceRegistry.parseDefinitions("[$a, $b]")
        val remoteA = a.replace("\"priority\":10", "\"priority\":99")
        val remote = WebSourceRegistry.parseDefinitions("[$remoteA]")
        val merged = WebSourceRegistry.merge(bundled, remote)
        assertEquals(2, merged.size)
        assertEquals("a", merged[0].id)      // ahora priority 99 → primero
        assertEquals(99, merged[0].priority) // la remota ganó
    }

    @Test fun `json ilegible devuelve lista vacia`() {
        assertEquals(0, WebSourceRegistry.parseDefinitions("no soy json").size)
    }
}
