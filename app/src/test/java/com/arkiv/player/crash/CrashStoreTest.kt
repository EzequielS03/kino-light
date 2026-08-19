package com.arkiv.player.crash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * La cola en disco es lo que hace que el reporte llegue "sí o sí": mandar la petición desde el
 * handler de crash pierde la carrera contra el proceso muriéndose, así que ahí solo se escribe un
 * archivo y el envío queda para el arranque siguiente.
 */
class CrashStoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private var reloj = 1_000L

    private fun store(maxPendientes: Int = 20) =
        CrashStore(dir = tmp.newFolder("crashes-${reloj}-${maxPendientes}"), maxPendientes = maxPendientes, ahora = { reloj })

    @Test
    fun `guardar deja un pendiente con el contenido intacto`() {
        val store = store()

        store.guardar("""{"mensaje":"se cayo"}""")

        val pendientes = store.pendientes()
        assertEquals(1, pendientes.size)
        assertEquals("""{"mensaje":"se cayo"}""", pendientes.single().readText())
    }

    @Test
    fun `borrar lo saca de la cola`() {
        val store = store()
        store.guardar("uno")

        store.borrar(store.pendientes().single())

        assertTrue(store.pendientes().isEmpty())
    }

    @Test
    fun `los pendientes salen del mas viejo al mas nuevo`() {
        val store = store()
        store.guardar("viejo")
        reloj = 2_000L
        store.guardar("nuevo")

        assertEquals(listOf("viejo", "nuevo"), store.pendientes().map { it.readText() })
    }

    @Test
    fun `dos reportes en el mismo milisegundo no se pisan`() {
        val store = store()

        store.guardar("primero")
        store.guardar("segundo")

        assertEquals(listOf("primero", "segundo"), store.pendientes().map { it.readText() })
    }

    @Test
    fun `pasado el tope se descarta el mas viejo`() {
        val store = store(maxPendientes = 2)
        store.guardar("uno")
        reloj = 2_000L
        store.guardar("dos")
        reloj = 3_000L

        store.guardar("tres")

        assertEquals(listOf("dos", "tres"), store.pendientes().map { it.readText() })
    }

    @Test
    fun `guardar crea la carpeta si todavia no existe`() {
        val store = CrashStore(dir = tmp.root.resolve("sin/crear"), maxPendientes = 20, ahora = { reloj })

        store.guardar("uno")

        assertEquals("uno", store.pendientes().single().readText())
    }

    @Test
    fun `un archivo ajeno en la carpeta no se toma como pendiente`() {
        val dir = tmp.newFolder("mezclada")
        dir.resolve("basura.txt").writeText("no es un reporte")
        val store = CrashStore(dir = dir, maxPendientes = 20, ahora = { reloj })

        store.guardar("uno")

        assertEquals(listOf("uno"), store.pendientes().map { it.readText() })
        assertTrue(dir.resolve("basura.txt").exists())
    }

    @Test
    fun `pendientes en una carpeta que no existe es lista vacia`() {
        val store = CrashStore(dir = tmp.root.resolve("nunca/creada"), maxPendientes = 20, ahora = { reloj })

        assertTrue(store.pendientes().isEmpty())
        assertFalse(tmp.root.resolve("nunca/creada").exists())
    }

    /**
     * Un `.json.tmp` es lo que deja un proceso muerto a mitad de escritura. Ese pedazo NO puede
     * entrar a la cola: el servidor lo rechazaría por JSON inválido y el drenador lo reintentaría
     * en cada arranque para siempre.
     */
    @Test
    fun `un resto a medio escribir de un crash anterior no entra a la cola`() {
        val dir = tmp.newFolder("a-medias")
        dir.resolve("0000000000999-0000.json.tmp").writeText("""{"mensaje":"corta""")
        val store = CrashStore(dir = dir, maxPendientes = 20, ahora = { reloj })

        store.guardar("entero")

        assertEquals(listOf("entero"), store.pendientes().map { it.readText() })
    }

    /** Que el temporal se renombre y no quede al lado del bueno ocupando disco para siempre. */
    @Test
    fun `guardar no deja restos temporales`() {
        val dir = tmp.newFolder("sin-restos")
        val store = CrashStore(dir = dir, maxPendientes = 20, ahora = { reloj })

        store.guardar("uno")

        assertEquals(1, dir.listFiles()!!.size)
        assertTrue(dir.listFiles()!!.single().name.endsWith(".json"))
    }
}
