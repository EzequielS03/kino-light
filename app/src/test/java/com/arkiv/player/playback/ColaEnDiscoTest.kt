package com.arkiv.player.playback

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * La cola del archivo (sus últimos KB), guardada en disco para que sobreviva al reinicio de la app.
 *
 * MEDIDO EN EL FIRE TV el 2026-08-14, abriendo una película en MPEG-TS por primera vez:
 *
 * ```
 * 09:35:11.012  loadMedia formato=mpegts
 * 09:35:12.667  ← pide rango=bytes=660308868-           ← libVLC quiere el FINAL del archivo
 * 09:35:13.921  origen rechazó ... con -1 (intento 1/3)
 * 09:35:15.121  origen rechazó ... con -1 (intento 1/3)
 * 09:35:16.320  precalentada la cola: 256KB en 6205ms
 * 09:35:16.386  ⏱ abrió en 5376ms
 * ```
 *
 * Con un TS, libVLC sondea el final del archivo para deducir la duración, y hasta que ese rango no
 * llega no hay imagen. La comparación del mismo día lo deja sin dudas: el mp4 nuevo pidió SOLO
 * `bytes=0-` y abrió en 1525 ms; el mpegts nuevo pidió también el final y tardó 5376 ms.
 *
 * [ColaCaliente] ya resuelve el sondeo —lo sirvió de memoria, sin red— y `abrirConDuplicado` ya
 * ataca los rechazos del CDN. Lo que faltaba es que esos 256 KB no se tiren: hasta ahora vivían en
 * un mapa en memoria, así que CADA arranque de la app volvía a pagar la primera vez. En un Fire TV,
 * que mata la app en cuanto se va al fondo, eso es casi siempre.
 */
class ColaEnDiscoTest {

    @get:Rule val carpeta = TemporaryFolder()

    private fun cola() = ColaEnDisco(carpeta.root)

    private val bytes = ByteArray(4096) { (it % 251).toByte() }

    @Test fun `lo guardado se puede volver a leer igual`() {
        val c = cola()
        c.guardar("clave1", inicio = 660_296_724L, total = 660_558_868L, bytes = bytes)

        val leida = c.leer("clave1")!!
        assertEquals(660_296_724L, leida.inicio)
        assertEquals(660_558_868L, leida.total)
        assertArrayEquals(bytes, leida.bytes)
    }

    @Test fun `una clave que no se guardo no devuelve nada`() {
        assertNull(cola().leer("nunca-vista"))
    }

    @Test fun `sobrevive a otra instancia, que es el punto de guardarla en disco`() {
        cola().guardar("clave1", 100L, 200L, bytes)
        // Otra instancia sobre la misma carpeta = la app reiniciada.
        assertArrayEquals(bytes, ColaEnDisco(carpeta.root).leer("clave1")!!.bytes)
    }

    /** Un archivo a medio escribir (la app murió guardando) no puede reventar ni servir basura. */
    @Test fun `un archivo truncado se descarta en vez de reventar`() {
        val c = cola()
        c.guardar("clave1", 100L, 200L, bytes)
        val f = carpeta.root.listFiles()!!.first { it.name.startsWith("clave1") }
        f.writeBytes(byteArrayOf(1, 2, 3))
        assertNull(c.leer("clave1"))
    }

    @Test fun `un archivo con solo la cabecera y sin cuerpo se descarta`() {
        val c = cola()
        c.guardar("clave1", 100L, 200L, bytes)
        val f = carpeta.root.listFiles()!!.first { it.name.startsWith("clave1") }
        f.writeBytes(ByteArray(ColaEnDisco.BYTES_DE_CABECERA))
        assertNull(c.leer("clave1"))
    }

    @Test fun `guardar dos veces la misma clave deja la ultima`() {
        val c = cola()
        c.guardar("clave1", 100L, 200L, ByteArray(10) { 1 })
        c.guardar("clave1", 300L, 400L, ByteArray(10) { 2 })

        val leida = c.leer("clave1")!!
        assertEquals(300L, leida.inicio)
        assertEquals(2.toByte(), leida.bytes[0])
    }

    /**
     * No puede crecer para siempre: son ~256 KB por título y la caché de video ya tiene su propio
     * presupuesto. Se tira la más vieja, que es la que menos probable es que se vuelva a abrir.
     */
    @Test fun `no guarda mas colas que el tope`() {
        val c = ColaEnDisco(carpeta.root, maxColas = 3)
        repeat(5) { i ->
            c.guardar("clave$i", i.toLong(), 999L, ByteArray(64) { i.toByte() })
            // El descarte va por fecha de modificación, que en un test corre demasiado rápido.
            carpeta.root.listFiles()!!.forEach { it.setLastModified(1_000_000L + i * 1000L) }
        }
        assertEquals(3, carpeta.root.listFiles()!!.size)
        // Las dos primeras se tiraron; las últimas siguen.
        assertNull(c.leer("clave0"))
        assertNull(c.leer("clave1"))
    }

    @Test fun `guardar una cola vacia no deja nada`() {
        val c = cola()
        c.guardar("clave1", 100L, 200L, ByteArray(0))
        assertNull(c.leer("clave1"))
    }
}
