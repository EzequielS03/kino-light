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

    /**
     * Hallazgo F1 de la revisión final: la versión anterior de esta clase intentaba inferir "la
     * firma anterior fue aceptada" del SILENCIO del llamante -si a un `firmar()` no le seguía un
     * `rechazada()` antes del próximo `firmar()` del MISMO hilo, se asumía aceptada, con el estado
     * de "pendiente" en un `ThreadLocal`-. Ese camino de reinicio era INALCANZABLE en producción:
     * `LiveHlsProxy` abre un hilo real por conexión, cada conexión pide UNA firma y su hilo muere
     * -el `ThreadLocal` se iba con él-, así que dos 403 en TODA la sesión (sin importar cuánto
     * tiempo ni cuántas firmas buenas hubiera en el medio) conmutaban al gateway para siempre.
     *
     * El fix reemplaza la inferencia por señales EXPLÍCITAS -`aceptada()`/`rechazada()`- que
     * `LiveHlsProxy.pedirAlOrigen` emite según el código de respuesta real del CDN. Este test
     * ejercita el modelo de hilos REAL (una conexión = un hilo que pide su firma y avisa el
     * veredicto, y muere) en vez de uno secuencial en un solo hilo como hacían los tests viejos:
     * demuestra que el reinicio ahora es alcanzable AUNQUE cada aviso venga de un hilo distinto.
     */
    @Test
    fun `un exito en un hilo distinto reinicia el contador (modelo real hilo por conexion)`() {
        val remota = Contadora("remota")
        val f = FirmaConRespaldo(Contadora("local"), remota, umbral = 2)

        // Hilo 1: una conexión que pide su firma y el CDN la rechaza (403).
        Thread { runBlocking { f.firmar("t") }; f.rechazada() }.apply { start(); join() }
        // Hilo 2: una conexión DISTINTA, con veredicto exitoso -reinicia el contador compartido-.
        Thread { runBlocking { f.firmar("t") }; f.aceptada() }.apply { start(); join() }
        // Hilo 3: otro rechazo aislado. Si el reinicio del hilo 2 no hubiera contado, esto ya
        // sería el segundo rechazo seguido (umbral) y conmutaría -- no debería.
        Thread { runBlocking { f.firmar("t") }; f.rechazada() }.apply { start(); join() }

        assertEquals(0, remota.veces)
        assertFalse("el reinicio via aceptada() en OTRO hilo debe contar", f.usandoRespaldo)
    }

    @Test
    fun `una vez en el respaldo se queda ahi aunque lleguen exitos`() = runBlocking {
        val remota = Contadora("remota")
        val f = FirmaConRespaldo(Contadora("local"), remota, umbral = 1)
        f.firmar("t"); f.rechazada()
        f.firmar("t"); f.aceptada()  // un éxito NO debería sacarlo del respaldo
        repeat(3) { f.firmar("t") }
        assertEquals(4, remota.veces)
        assertTrue(f.usandoRespaldo)
    }

    /**
     * "En la misma ola" de la revisión final: `AppGraph` armaba `liveHlsProxy` como `by lazy`
     * leyendo `settings.liveSignRemote.value` UNA sola vez, así que tocar el interruptor "Forzar
     * servidor" de Ajustes no tenía ningún efecto hasta reiniciar la app. [FirmaSegunAjustes] lee
     * el lambda en CADA llamada -acá el test simula eso con una variable mutable en vez del
     * `StateFlow` real de Ajustes-.
     */
    @Test
    fun `FirmaSegunAjustes respeta el interruptor en cada llamada, sin reiniciar nada`() = runBlocking {
        var forzado = false
        val remota = Contadora("remota")
        val conRespaldo = FirmaConRespaldo(Contadora("local"), remota, umbral = 100)
        val f = FirmaSegunAjustes(conRespaldo, remota, forzarRemoto = { forzado })

        f.firmar("t")
        assertEquals("con el interruptor apagado, usa el camino local+respaldo", 0, remota.veces)

        forzado = true  // el usuario lo prende en Ajustes MIENTRAS la reproducción sigue andando
        f.firmar("t")
        assertEquals("apenas se prende, la SIGUIENTE llamada ya usa el gateway", 1, remota.veces)

        forzado = false  // y lo vuelve a apagar
        f.firmar("t")
        assertEquals("al apagarlo vuelve al camino local+respaldo, sin pedir nada mas", 1, remota.veces)
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
     * rechaza TODO" el que necesita que el respaldo se dispare.
     *
     * El fix de F1 (señales explícitas `aceptada()`/`rechazada()` en vez de `ThreadLocal`, ver el
     * docstring de [FirmaConRespaldo]) también resuelve esto de raíz: ya no hay ningún estado
     * "pendiente" que un hilo ajeno pueda pisar -solo el contador compartido, protegido por
     * [estado]-.
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

    // ---- Que respuesta del CDN cuenta como firma rechazada ----

    /**
     * MEDIDO EN EL GOOGLE TV el 2026-08-14: ningun canal cargaba, y el gateway resolvia PERFECTO
     * (`live resolve OK ... direcciones=2` y 200 en todos). El log del aparato lo destapo:
     *
     * ```
     * 12:56:58.653  playlist → 401 en 346ms
     * 12:56:58.654  502 al reproductor: playlist con codigo 401
     * ```
     *
     * El CDN rechaza con **401**, no con 403. Y `pedirAlOrigen` solo trataba el 403 como rechazo:
     * con un 401 llamaba a `aceptada()`, el contador de rechazos seguidos se reseteaba y
     * [FirmaConRespaldo] **nunca conmutaba al firmador del gateway**. Tampoco se llegaba a dar la
     * sesion por muerta, que es el otro camino de recuperacion. O sea que la defensa entera estaba
     * mirando el codigo equivocado.
     */
    @Test
    fun `el 401 tambien es una firma rechazada, no solo el 403`() {
        assertTrue("401 = no autorizado", esRechazoDeFirma(401))
        assertTrue("403 = prohibido", esRechazoDeFirma(403))
    }

    /** Lo que NO es un rechazo de firma: si contara, el respaldo conmutaria por cualquier cosa. */
    @Test
    fun `el resto de los codigos no son rechazo de firma`() {
        listOf(200, 206, 404, 500, 502, 503, -1).forEach {
            assertFalse("codigo $it", esRechazoDeFirma(it))
        }
    }
}
