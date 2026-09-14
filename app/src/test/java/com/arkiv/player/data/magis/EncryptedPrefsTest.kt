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
        var discarded = false
        var usedPlain = false

        val prefs = EncryptedPrefs.openOrRepair(
            create = { "encrypted" },
            discardUndecryptable = { discarded = true },
            unencrypted = { usedPlain = true; "plain" },
        )

        assertEquals("encrypted", prefs)
        assertFalse("must not discard anything if it opened fine", discarded)
        assertFalse("must not fall back to plain if it opened fine", usedPlain)
    }

    @Test
    fun `if the key can no longer decrypt, it discards the file and starts over`() {
        var attempts = 0
        var discarded = false

        val prefs = EncryptedPrefs.openOrRepair(
            create = {
                attempts++
                if (attempts == 1) throw AEADBadTagException() else "new encrypted"
            },
            discardUndecryptable = { discarded = true },
            unencrypted = { fail("must not fall back to plain"); "" },
        )

        assertEquals("new encrypted", prefs)
        assertEquals("retries exactly once", 2, attempts)
        assertTrue("must discard what's undecryptable before retrying", discarded)
    }

    @Test
    fun `discards the undecryptable file BEFORE retrying, not after`() {
        val order = mutableListOf<String>()

        EncryptedPrefs.openOrRepair(
            create = {
                order += "create"
                if (order.count { it == "create" } == 1) throw AEADBadTagException() else "ok"
            },
            discardUndecryptable = { order += "discard" },
            unencrypted = { "" },
        )

        assertEquals(listOf("create", "discard", "create"), order)
    }

    @Test
    fun `if it still doesn't open after discarding it, it falls back to plain instead of leaving the app unable to start`() {
        var discarded = false

        val prefs = EncryptedPrefs.openOrRepair(
            create = { throw GeneralSecurityException("broken Keystore") },
            discardUndecryptable = { discarded = true },
            unencrypted = { "plain" },
        )

        assertEquals("plain", prefs)
        assertTrue(discarded)
    }

    @Test
    fun `an IOException also counts as an unusable file`() {
        var attempts = 0

        val prefs = EncryptedPrefs.openOrRepair(
            create = { attempts++; if (attempts == 1) throw IOException("corrupt xml") else "ok" },
            discardUndecryptable = {},
            unencrypted = { fail("must not fall back to plain"); "" },
        )

        assertEquals("ok", prefs)
    }

    @Test
    fun `recognizes broken encryption even wrapped in another exception`() {
        var attempts = 0

        val prefs = EncryptedPrefs.openOrRepair(
            create = {
                attempts++
                if (attempts == 1) throw RuntimeException("during init", AEADBadTagException())
                else "ok"
            },
            discardUndecryptable = {},
            unencrypted = { fail("must not fall back to plain"); "" },
        )

        assertEquals("ok", prefs)
    }

    @Test
    fun `an error that isn't encryption-related propagates and deletes nobody's identity`() {
        var discarded = false

        try {
            EncryptedPrefs.openOrRepair(
                create = { throw IllegalArgumentException("empty prefs name") },
                discardUndecryptable = { discarded = true },
                unencrypted = { "plain" },
            )
            fail("had to propagate the error unrelated to encryption")
        } catch (e: IllegalArgumentException) {
            assertEquals("empty prefs name", e.message)
        }

        assertFalse("a bug of ours must NEVER cost people their session", discarded)
    }
}
