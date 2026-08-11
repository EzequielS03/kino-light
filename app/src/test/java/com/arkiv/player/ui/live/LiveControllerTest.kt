package com.arkiv.player.ui.live

import com.arkiv.player.data.gateway.LiveSession
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.atomic.AtomicInteger

class LiveControllerTest {
    @Test
    fun `abrir un canal devuelve una url local para VLC`() = runBlocking {
        val ctrl = LiveController(
            resolver = { code -> LiveSession("h", "http://x/?token=${"A".repeat(32)}", "L", code, 0) },
            urlPara = { s -> "http://127.0.0.1:9999/live.m3u8?c=${s.channel}" },
        )
        assertTrue(ctrl.abrir("c1").startsWith("http://127.0.0.1:"))
    }

    @Test
    fun `precalentar el vecino no vuelve a resolver cuando se abre`() = runBlocking {
        var resoluciones = 0
        val ctrl = LiveController(
            resolver = { code -> resoluciones++; LiveSession("h", "http://x/?token=${"A".repeat(32)}", "L", code, 0) },
            urlPara = { "http://127.0.0.1:9999/live.m3u8" },
        )
        ctrl.precalentar("c2")
        ctrl.abrir("c2")
        assertEquals("el canal precalentado ya estaba resuelto", 1, resoluciones)
    }

    @Test
    fun `una sesion vencida se vuelve a resolver`() = runBlocking {
        var resoluciones = 0
        val ctrl = LiveController(
            resolver = { code ->
                resoluciones++
                LiveSession("h", "http://x/?token=${"A".repeat(32)}", "L", code, expiresAt = 1)
            },
            urlPara = { "http://127.0.0.1:9999/live.m3u8" },
            ahora = { 999_999 },
        )
        ctrl.precalentar("c3")
        ctrl.abrir("c3")
        assertEquals(2, resoluciones)
    }

    @Test
    fun `precalentar que falla no propaga la excepcion ni deja nada cacheado`() = runBlocking {
        // Riesgo del brief: "precalentar es best-effort y cancelable: no debe propagar
        // excepciones ni dejar el estado inconsistente si falla". Si la resolución que dispara
        // el precalentado revienta, abrir() debe re-resolver desde cero, sin heredar ningún
        // estado parcial ni ver la excepción original.
        var intentos = 0
        val ctrl = LiveController(
            resolver = { code ->
                intentos++
                if (intentos == 1) throw RuntimeException("el portal esta caido")
                LiveSession("h", "http://x/?token=${"A".repeat(32)}", "L", code, 0)
            },
            urlPara = { "http://127.0.0.1:9999/live.m3u8" },
        )
        ctrl.precalentar("c4") // no debe lanzar
        val url = ctrl.abrir("c4")
        assertEquals(2, intentos)
        assertTrue(url.startsWith("http://127.0.0.1:"))
    }

    @Test
    fun `precalentar canales distintos no se serializa entre si`() = runBlocking {
        // Riesgo del brief: "varias corrutinas pueden pedir el mismo canal a la vez". Un
        // candado GLOBAL envolviendo resolver() (como sugiere ingenuamente un primer borrador)
        // serializaría el precalentado del vecino anterior y el siguiente entre sí -y
        // bloquearía un abrir() de un tercer canal detrás de un precalentado ajeno-, justo lo
        // que el precalentado existe para evitar (ver la intro del brief: "mientras el overlay
        // está quieto, se resuelve por lo bajo el canal siguiente y el anterior"). Con delay()
        // dentro de resolver(), dos canales DISTINTOS deben resolver en paralelo: si un candado
        // global los serializara, esto tardaría ~2x demora en vez de ~1x.
        val demora = 200L
        val ctrl = LiveController(
            resolver = { code -> delay(demora); LiveSession("h", "http://x/?token=${"A".repeat(32)}", "L", code, 0) },
            urlPara = { "http://127.0.0.1:9999/live.m3u8" },
        )
        val inicio = System.currentTimeMillis()
        coroutineScope {
            launch { ctrl.precalentar("prev") }
            launch { ctrl.precalentar("next") }
        }
        val transcurrido = System.currentTimeMillis() - inicio
        assertTrue(
            "precalentar de dos canales distintos tardo ${transcurrido}ms; " +
                "si esto anda cerca de ${demora * 2}ms es que un candado global los serializo",
            transcurrido < demora * 3 / 2,
        )
    }

    @Test(timeout = 10_000)
    fun `abrir el mismo canal desde varios hilos a la vez resuelve una sola vez`() {
        // Complementa el test anterior desde el otro lado: canales DISTINTOS no deben
        // compartir candado, pero el MISMO canal pedido a la vez por varias corrutinas -en
        // hilos reales, no solo interleaving cooperativo- sí debe resolverse una sola vez.
        // CyclicBarrier alinea a los hilos para maximizar la superposición real (igual patrón
        // que FirmaDeSegmentosTest).
        val resoluciones = AtomicInteger(0)
        val ctrl = LiveController(
            resolver = { code ->
                delay(80)
                resoluciones.incrementAndGet()
                LiveSession("h", "http://x/?token=${"A".repeat(32)}", "L", code, 0)
            },
            urlPara = { "http://127.0.0.1:9999/live.m3u8" },
        )
        val hilos = 16
        val barrera = CyclicBarrier(hilos)
        val threads = (1..hilos).map {
            Thread {
                barrera.await()
                runBlocking { ctrl.abrir("mismo-canal") }
            }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }
        assertEquals(1, resoluciones.get())
    }

    @Test(timeout = 10_000)
    fun `cerrar concurrente con abrir no corrompe el mapa de sesiones`() {
        // Riesgo del brief: la caché sigue viva mientras el usuario zapea. cerrar() es `fun`,
        // no `suspend`: se puede llamar desde cualquier hilo (p.ej. al salir de la pantalla de
        // vivo) mientras OTRO hilo está a mitad de un abrir()/precalentar() que escribe en el
        // mismo mapa. Un mutableMapOf plano (HashMap) mutado así, sin la MISMA sincronización
        // en ambos lados, es modificación concurrente no protegida -comportamiento indefinido,
        // no solo una escritura perdida-. El UncaughtExceptionHandler es el mismo patrón que ya
        // usa LiveHlsProxyTest para pescar excepciones que escapan de OTRO hilo (un assert
        // normal en este hilo no las vería).
        val ctrl = LiveController(
            resolver = { code -> LiveSession("h", "http://x/?token=${"A".repeat(32)}", "L", code, 0) },
            urlPara = { "http://127.0.0.1:9999/live.m3u8" },
        )
        val previo = Thread.getDefaultUncaughtExceptionHandler()
        val excepciones = java.util.Collections.synchronizedList(mutableListOf<Throwable>())
        Thread.setDefaultUncaughtExceptionHandler { _, e -> excepciones.add(e) }
        try {
            val lectores = (1..8).map { i ->
                Thread {
                    repeat(200) { j -> runBlocking { ctrl.abrir("c${(i + j) % 4}") } }
                }
            }
            val cerrador = Thread { repeat(200) { ctrl.cerrar() } }
            (lectores + cerrador).forEach { it.start() }
            (lectores + cerrador).forEach { it.join() }
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(previo)
        }
        assertTrue(
            "cerrar() concurrente con abrir() no deberia tirar excepciones: $excepciones",
            excepciones.isEmpty(),
        )
    }
}
