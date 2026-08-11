package com.arkiv.player.playback

import com.arkiv.player.data.gateway.LiveSignature
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CyclicBarrier

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

    /**
     * `LiveHlsProxy` abre un hilo REAL por conexión aceptada: uno para el poll del playlist,
     * uno por cada segmento en vuelo. Todos comparten la MISMA instancia de [FirmaConRespaldo].
     *
     * Este test cubre el hallazgo C1 de la revisión: la primera versión de esta clase (con un
     * único `Boolean pendiente` global) NO conmutaba nunca bajo esta carga — ni siquiera
     * envolviendo `firmar()`/`rechazada()` en un lock. No es (solo) un lost-update de memoria:
     * un hilo A deja "pendiente=true" mientras espera su propio veredicto, y un hilo C —
     * completamente ajeno, con su PROPIA firma rechazada de camino— lee ese `true` compartido y
     * resetea el contador, borrando rechazos de A que ya habían contado. Es el peor de los dos
     * modos de falla posibles, porque es justo el caso "Magis cambió el algoritmo y el CDN
     * rechaza TODO" el que necesita que el respaldo se dispare. Ver el docstring de
     * [FirmaConRespaldo] para el detalle completo y por qué el fix usa un `ThreadLocal` en vez
     * de un lock alrededor del flag compartido.
     *
     * `CyclicBarrier` alinea a los hilos para maximizar la superposición real (no alcanza con
     * lanzarlos y esperar: sin la barrera casi todos corren serializados por el scheduler y la
     * condición de carrera casi no aparece).
     */
    @Test
    fun `bajo rechazos simultaneos de varios hilos, el contador no pierde incrementos y conmuta`() {
        val hilos = 64
        val rondas = 30
        var rondasQueNoConmutaron = 0
        repeat(rondas) {
            val f = FirmaConRespaldo(Contadora("local"), Contadora("remota"), umbral = hilos)
            val barrera = CyclicBarrier(hilos)
            val threads = (1..hilos).map {
                Thread {
                    barrera.await()
                    runBlocking { f.firmar("t") }
                    f.rechazada()
                }
            }
            threads.forEach { it.start() }
            threads.forEach { it.join() }
            if (!f.usandoRespaldo) rondasQueNoConmutaron++
        }
        assertEquals(
            "con $hilos rechazos simultaneos (umbral=$hilos) TODAS las rondas deberian conmutar; " +
                "si esto falla es la carrera de C1, no un problema del test",
            0,
            rondasQueNoConmutaron,
        )
    }
}
