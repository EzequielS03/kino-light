package com.arkiv.player.ui.live

import com.arkiv.player.data.db.LiveChannelCacheEntity
import com.arkiv.player.data.db.LiveRecentEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecentLiveChannelsTest {

    @Test
    fun `sin recientes no hay canales -- la fila del home no debe dibujarse`() {
        assertTrue(canalesRecientesParaHome(emptyList(), emptyMap()).isEmpty())
    }

    @Test
    fun `no reordena -- respeta el orden con el que llega (ya es vistoAt DESC)`() {
        val recientes = listOf(
            LiveRecentEntity(code = "b", nombre = "Canal B", vistoAt = 200),
            LiveRecentEntity(code = "a", nombre = "Canal A", vistoAt = 100),
        )
        val resultado = canalesRecientesParaHome(recientes, emptyMap())
        assertEquals(listOf("b", "a"), resultado.map { it.code })
    }

    @Test
    fun `completa logo y numero cuando el canal esta en la cache`() {
        val recientes = listOf(LiveRecentEntity(code = "a", nombre = "Canal A", vistoAt = 100))
        val cache = mapOf(
            "a" to LiveChannelCacheEntity(
                code = "a", categoria = 3, nombre = "Canal A", numero = 5,
                logo = "https://logo/a.png", guardadoAt = 0,
            ),
        )
        val canal = canalesRecientesParaHome(recientes, cache).single()
        assertEquals(5, canal.numero)
        assertEquals("https://logo/a.png", canal.logo)
    }

    @Test
    fun `canal ausente de la cache cae a numero 0 y logo null, no se rompe`() {
        val recientes = listOf(LiveRecentEntity(code = "a", nombre = "Canal A", vistoAt = 100))
        val canal = canalesRecientesParaHome(recientes, emptyMap()).single()
        assertEquals(0, canal.numero)
        assertNull(canal.logo)
    }

    @Test
    fun `el nombre sale de recientes, no de la cache`() {
        val recientes = listOf(LiveRecentEntity(code = "a", nombre = "Nombre actual", vistoAt = 100))
        val cache = mapOf(
            "a" to LiveChannelCacheEntity(
                code = "a", categoria = 1, nombre = "Nombre viejo de la caché", numero = 9,
                logo = null, guardadoAt = 0,
            ),
        )
        val canal = canalesRecientesParaHome(recientes, cache).single()
        assertEquals("Nombre actual", canal.nombre)
    }
}
