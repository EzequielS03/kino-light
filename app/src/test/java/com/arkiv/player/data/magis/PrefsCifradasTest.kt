package com.arkiv.player.data.magis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.security.GeneralSecurityException
import javax.crypto.AEADBadTagException

/**
 * El caso real que motivó esto: un Galaxy A54 que restauró el respaldo de otro teléfono, se trajo
 * el XML cifrado pero NO la llave del Keystore (que no sale del aparato), y quedó tirando
 * `AEADBadTagException` en cada arranque — la app abría y se cerraba sola, sin salida.
 */
class PrefsCifradasTest {
    @Test
    fun `abre normal y no toca nada cuando la llave todavia sirve`() {
        var tirado = false
        var planoUsado = false

        val prefs = PrefsCifradas.abrirOReparar(
            crear = { "cifradas" },
            tirarLoIndescifrable = { tirado = true },
            sinCifrar = { planoUsado = true; "planas" },
        )

        assertEquals("cifradas", prefs)
        assertFalse("no puede borrar nada si abrió bien", tirado)
        assertFalse("no puede caer al plano si abrió bien", planoUsado)
    }

    @Test
    fun `si la llave ya no descifra tira el archivo y vuelve a empezar`() {
        var intentos = 0
        var tirado = false

        val prefs = PrefsCifradas.abrirOReparar(
            crear = {
                intentos++
                if (intentos == 1) throw AEADBadTagException() else "cifradas nuevas"
            },
            tirarLoIndescifrable = { tirado = true },
            sinCifrar = { fail("no debía caer al plano"); "" },
        )

        assertEquals("cifradas nuevas", prefs)
        assertEquals("reintenta exactamente una vez", 2, intentos)
        assertTrue("tiene que tirar lo indescifrable antes de reintentar", tirado)
    }

    @Test
    fun `tira lo indescifrable ANTES de reintentar, no despues`() {
        val orden = mutableListOf<String>()

        PrefsCifradas.abrirOReparar(
            crear = {
                orden += "crear"
                if (orden.count { it == "crear" } == 1) throw AEADBadTagException() else "ok"
            },
            tirarLoIndescifrable = { orden += "tirar" },
            sinCifrar = { "" },
        )

        assertEquals(listOf("crear", "tirar", "crear"), orden)
    }

    @Test
    fun `si ni despues de tirarlo abre, cae al plano en vez de dejar la app sin arrancar`() {
        var tirado = false

        val prefs = PrefsCifradas.abrirOReparar(
            crear = { throw GeneralSecurityException("Keystore roto") },
            tirarLoIndescifrable = { tirado = true },
            sinCifrar = { "planas" },
        )

        assertEquals("planas", prefs)
        assertTrue(tirado)
    }

    @Test
    fun `un IOException tambien cuenta como archivo inservible`() {
        var intentos = 0

        val prefs = PrefsCifradas.abrirOReparar(
            crear = { intentos++; if (intentos == 1) throw IOException("xml corrupto") else "ok" },
            tirarLoIndescifrable = {},
            sinCifrar = { fail("no debía caer al plano"); "" },
        )

        assertEquals("ok", prefs)
    }

    @Test
    fun `reconoce el cifrado roto aunque venga envuelto en otra excepcion`() {
        var intentos = 0

        val prefs = PrefsCifradas.abrirOReparar(
            crear = {
                intentos++
                if (intentos == 1) throw RuntimeException("al inicializar", AEADBadTagException())
                else "ok"
            },
            tirarLoIndescifrable = {},
            sinCifrar = { fail("no debía caer al plano"); "" },
        )

        assertEquals("ok", prefs)
    }

    @Test
    fun `un error que no es de cifrado sube y no borra la identidad de nadie`() {
        var tirado = false

        try {
            PrefsCifradas.abrirOReparar(
                crear = { throw IllegalArgumentException("nombre de prefs vacío") },
                tirarLoIndescifrable = { tirado = true },
                sinCifrar = { "planas" },
            )
            fail("tenía que propagar el error ajeno al cifrado")
        } catch (e: IllegalArgumentException) {
            assertEquals("nombre de prefs vacío", e.message)
        }

        assertFalse("un bug nuestro NO puede costarle la sesión a la gente", tirado)
    }
}
