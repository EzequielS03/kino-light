package com.arkiv.player.remote

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TvDeviceSelectorTest {

    private fun tv(id: String, online: Boolean, lastSeen: String) = JSONObject()
        .put("id", id).put("online", online).put("lastSeen", lastSeen)

    @Test
    fun `sin candidatos devuelve null`() {
        assertNull(TvDeviceSelector.pick(emptyList()))
    }

    @Test
    fun `prefiere el que esta online aunque otro se haya visto despues`() {
        val elegido = TvDeviceSelector.pick(
            listOf(
                tv("viejo", online = false, lastSeen = "2026-07-27T20:00:00Z"),
                tv("vivo", online = true, lastSeen = "2026-07-27T10:00:00Z"),
            ),
        )
        assertEquals("vivo", elegido?.getString("id"))
    }

    @Test
    fun `entre dos online gana el visto mas recientemente`() {
        val elegido = TvDeviceSelector.pick(
            listOf(
                tv("a", online = true, lastSeen = "2026-07-27T10:00:00Z"),
                tv("b", online = true, lastSeen = "2026-07-27T20:00:00Z"),
            ),
        )
        assertEquals("b", elegido?.getString("id"))
    }

    @Test
    fun `un unico candidato se elige aunque este offline`() {
        val elegido = TvDeviceSelector.pick(listOf(tv("solo", online = false, lastSeen = "")))
        assertEquals("solo", elegido?.getString("id"))
    }
}
