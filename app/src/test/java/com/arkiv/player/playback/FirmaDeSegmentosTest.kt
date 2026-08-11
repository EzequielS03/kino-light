package com.arkiv.player.playback

import com.arkiv.player.data.gateway.LiveSignature
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FirmaDeSegmentosTest {
    private class Contadora(private val marca: String) : FirmaDeSegmentos {
        var veces = 0
        override suspend fun firmar(token: String): LiveSignature {
            veces++
            return LiveSignature(veces.toLong(), marca)
        }
    }

    @Test
    fun `la firma local coincide con el algoritmo verificado`() = runBlocking {
        val token = "941d98961990d67e249dcd1ac57378c8"
        val f = FirmaLocal().firmar(token)
        assertEquals(TweakedMd5.signO3(token, f.moment), f.sign2)
    }

    @Test
    fun `mientras nadie rechace, no se le pide nada al gateway`() = runBlocking {
        val remota = Contadora("remota")
        val f = FirmaConRespaldo(Contadora("local"), remota)
        repeat(10) { f.firmar("t") }
        assertEquals(0, remota.veces)
        assertFalse(f.usandoRespaldo)
    }

    @Test
    fun `tras dos rechazos seguidos conmuta al gateway`() = runBlocking {
        val local = Contadora("local")
        val remota = Contadora("remota")
        val f = FirmaConRespaldo(local, remota, umbral = 2)
        f.firmar("t"); f.rechazada()
        f.firmar("t"); f.rechazada()
        assertEquals("remota", f.firmar("t").sign2)
        assertTrue(f.usandoRespaldo)
    }

    @Test
    fun `un rechazo suelto entre firmas buenas no conmuta`() = runBlocking {
        // Un 403 aislado es una firma que llegó tarde, no un algoritmo roto.
        val remota = Contadora("remota")
        val f = FirmaConRespaldo(Contadora("local"), remota, umbral = 2)
        f.firmar("t"); f.rechazada()
        f.firmar("t")            // esta salió bien: el contador vuelve a cero
        f.firmar("t"); f.rechazada()
        assertEquals(0, remota.veces)
    }

    @Test
    fun `una vez en el respaldo se queda ahi`() = runBlocking {
        val remota = Contadora("remota")
        val f = FirmaConRespaldo(Contadora("local"), remota, umbral = 1)
        f.firmar("t"); f.rechazada()
        repeat(3) { f.firmar("t") }
        assertEquals(3, remota.veces)
    }
}
