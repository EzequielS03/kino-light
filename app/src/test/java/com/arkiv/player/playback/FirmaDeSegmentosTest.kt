package com.arkiv.player.playback

import com.arkiv.player.data.magis.TweakedMd5
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Quedó una sola implementación: la firma se calcula en el aparato. El respaldo en el gateway
 * (`FirmaDelGateway`/`FirmaConRespaldo`/`FirmaSegunAjustes`) se fue con el servidor, así que lo que
 * queda por fijar es que la firma local es la correcta y qué cuenta como rechazo del CDN.
 */
class FirmaDeSegmentosTest {

    @Test
    fun `la firma local coincide con el algoritmo verificado`() = runBlocking {
        val token = "941d98961990d67e249dcd1ac57378c8"

        val f = FirmaLocal().firmar(token)

        assertEquals(TweakedMd5.signO3(token, f.moment), f.sign2)
    }

    @Test
    fun `cada firma usa su propio momento`() = runBlocking {
        val local = FirmaLocal()
        val token = "941d98961990d67e249dcd1ac57378c8"

        val primera = local.firmar(token)
        val segunda = local.firmar(token)

        // Mismo momento ⇒ misma firma; distinto momento ⇒ distinta. Lo que no puede pasar es que
        // la firma se calcule con un momento que no sea el que se reporta.
        assertEquals(TweakedMd5.signO3(token, segunda.moment), segunda.sign2)
        assertTrue(segunda.moment >= primera.moment)
    }

    /** Este CDN rechaza con 401, no con 403, y el código solo miraba el 403: ningún canal cargaba. */
    @Test
    fun `el 401 tambien es una firma rechazada, no solo el 403`() {
        assertTrue(esRechazoDeFirma(401))
        assertTrue(esRechazoDeFirma(403))
    }

    @Test
    fun `el resto de los codigos no son rechazo de firma`() {
        // Un 5xx o un timeout es el CDN con un problema, no la firma: contarlos haría descartar
        // firmas buenas por cualquier bache de red.
        listOf(200, 206, 302, 404, 500, 502, 504).forEach {
            assertFalse("codigo $it", esRechazoDeFirma(it))
        }
    }
}
