package com.arkiv.player.playback

import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.file.Files
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Ciclo de vida del proxy: arrancar, parar y volver a arrancar. Usa sockets de verdad. */
class ArchiveCacheProxyLifecycleTest {

    private fun dirTemporal(): File = Files.createTempDirectory("arkiv-proxy").toFile()

    /** ¿Hay alguien escuchando y aceptando en ese puerto? */
    private fun aceptaConexiones(puerto: Int): Boolean = runCatching {
        Socket().use { it.connect(InetSocketAddress("127.0.0.1", puerto), 800) }
        true
    }.getOrDefault(false)

    @Test fun arrancar_deja_el_puerto_aceptando() {
        val p = ArchiveCacheProxy(dirTemporal())
        try {
            val puerto = p.start()
            assertTrue("nadie escucha en $puerto tras start()", aceptaConexiones(puerto))
        } finally {
            p.stop()
        }
    }

    /**
     * EL BUG: el botón de parar de la barra llama a `stop()`, que cerraba el ServerSocket creado en
     * el constructor. Como el proxy es singleton y vive todo el proceso, y un ServerSocket cerrado
     * no se reabre, `start()` devolvía un puerto donde ya no escuchaba nadie: **archive quedaba
     * cargando para siempre** hasta matar la app. Parar tiene que ser reversible.
     */
    @Test fun volver_a_arrancar_despues_de_parar_revive_el_proxy() {
        val p = ArchiveCacheProxy(dirTemporal())
        try {
            val primero = p.start()
            assertTrue(aceptaConexiones(primero))
            p.stop()
            val segundo = p.start()
            assertTrue("el proxy no revivió: nadie escucha en $segundo", aceptaConexiones(segundo))
        } finally {
            p.stop()
        }
    }

    /** Parar tiene que cortar de verdad: si siguiera aceptando, el botón no serviría de nada. */
    @Test fun parar_deja_de_aceptar() {
        val p = ArchiveCacheProxy(dirTemporal())
        val puerto = p.start()
        assertTrue(aceptaConexiones(puerto))
        p.stop()
        assertFalse("sigue aceptando en $puerto después de stop()", aceptaConexiones(puerto))
    }

    /** La URL que se le pasa a VLC tiene que apuntar al puerto VIVO, no al de la sesión anterior. */
    @Test fun la_url_apunta_al_puerto_que_esta_escuchando() {
        val p = ArchiveCacheProxy(dirTemporal())
        try {
            p.start()
            p.stop()
            val vivo = p.start()
            assertTrue(p.proxyUrl("https://archive.org/download/x/y.mp4").contains(":$vivo/"))
        } finally {
            p.stop()
        }
    }

    /** Llamar start() dos veces no debe abrir un segundo socket ni cambiar el puerto en uso. */
    @Test fun arrancar_dos_veces_es_idempotente() {
        val p = ArchiveCacheProxy(dirTemporal())
        try {
            val a = p.start()
            val b = p.start()
            assertNotEquals(-1, a)
            assertTrue("start() repetido cambió el puerto: $a -> $b", a == b)
        } finally {
            p.stop()
        }
    }
}
