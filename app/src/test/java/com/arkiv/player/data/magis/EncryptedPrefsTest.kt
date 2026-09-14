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
 * The real case that motivated this: a Galaxy A54 that restored another phone's backup, brought
 * along the encrypted XML but NOT the Keystore key (which never leaves the device), and was left
 * throwing `AEADBadTagException` on every startup — the app opened and closed itself, with no way out.
 */
class EncryptedPrefsTest {
    @Test
    fun `opens normally and touches nothing when the key still works`() {
        var tirado = false
        var planoUsado = false

        val prefs = EncryptedPrefs.openOrRepair(
            create = { "cifradas" },
            discardUndecryptable = { tirado = true },
            unencrypted = { planoUsado = true; "planas" },
        )

        assertEquals("cifradas", prefs)
        assertFalse("no puede borrar nada si abrió bien", tirado)
        assertFalse("no puede caer al plano si abrió bien", planoUsado)
    }

    @Test
    fun `if the key can no longer decrypt, it discards the file and starts over`() {
        var intentos = 0
        var tirado = false

        val prefs = EncryptedPrefs.openOrRepair(
            create = {
                intentos++
                if (intentos == 1) throw AEADBadTagException() else "cifradas nuevas"
            },
            discardUndecryptable = { tirado = true },
            unencrypted = { fail("no debía caer al plano"); "" },
        )

        assertEquals("cifradas nuevas", prefs)
        assertEquals("reintenta exactamente una vez", 2, intentos)
        assertTrue("tiene que tirar lo indescifrable antes de reintentar", tirado)
    }

    @Test
    fun `discards the undecryptable file BEFORE retrying, not after`() {
        val orden = mutableListOf<String>()

        EncryptedPrefs.openOrRepair(
            create = {
                orden += "crear"
                if (orden.count { it == "crear" } == 1) throw AEADBadTagException() else "ok"
            },
            discardUndecryptable = { orden += "tirar" },
            unencrypted = { "" },
        )

        assertEquals(listOf("crear", "tirar", "crear"), orden)
    }

    @Test
    fun `if it still doesn't open after discarding it, it falls back to plain instead of leaving the app unable to start`() {
        var tirado = false

        val prefs = EncryptedPrefs.openOrRepair(
            create = { throw GeneralSecurityException("Keystore roto") },
            discardUndecryptable = { tirado = true },
            unencrypted = { "planas" },
        )

        assertEquals("planas", prefs)
        assertTrue(tirado)
    }

    @Test
    fun `an IOException also counts as an unusable file`() {
        var intentos = 0

        val prefs = EncryptedPrefs.openOrRepair(
            create = { intentos++; if (intentos == 1) throw IOException("xml corrupto") else "ok" },
            discardUndecryptable = {},
            unencrypted = { fail("no debía caer al plano"); "" },
        )

        assertEquals("ok", prefs)
    }

    @Test
    fun `recognizes broken encryption even wrapped in another exception`() {
        var intentos = 0

        val prefs = EncryptedPrefs.openOrRepair(
            create = {
                intentos++
                if (intentos == 1) throw RuntimeException("al inicializar", AEADBadTagException())
                else "ok"
            },
            discardUndecryptable = {},
            unencrypted = { fail("no debía caer al plano"); "" },
        )

        assertEquals("ok", prefs)
    }

    @Test
    fun `an error that isn't encryption-related propagates and deletes nobody's identity`() {
        var tirado = false

        try {
            EncryptedPrefs.openOrRepair(
                create = { throw IllegalArgumentException("nombre de prefs vacío") },
                discardUndecryptable = { tirado = true },
                unencrypted = { "planas" },
            )
            fail("tenía que propagar el error ajeno al cifrado")
        } catch (e: IllegalArgumentException) {
            assertEquals("nombre de prefs vacío", e.message)
        }

        assertFalse("un bug nuestro NO puede costarle la sesión a la gente", tirado)
    }
}
