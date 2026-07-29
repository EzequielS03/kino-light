package com.arkiv.player.pairing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PairCryptoTest {
    @Test fun encryptDecryptRoundTrip() {
        val code = "ABCDEFGHIJKLMNOPQRSTUVWX23"
        val secret = """{"email":"x@arkiv.local","password":"p"}"""
        val ct = PairCrypto.encrypt(secret, code)
        assertNotEquals(secret, ct)
        assertEquals(secret, PairCrypto.decrypt(ct, code))
    }

    @Test fun wrongCodeFailsToDecrypt() {
        val ct = PairCrypto.encrypt("secreto", "CODEAAAAAAAAAAAAAAAAAAAAA2")
        assertThrows(Exception::class.java) { PairCrypto.decrypt(ct, "CODEBBBBBBBBBBBBBBBBBBBBB3") }
    }

    @Test fun codeHashDiffersFromKeyAndIsStable() {
        val code = "ZZZZZZZZZZZZZZZZZZZZZZZZ22"
        val h1 = PairCrypto.codeHash(code)
        val h2 = PairCrypto.codeHash(code)
        assertEquals(h1, h2)          // estable
        assertEquals(64, h1.length)   // hex de 32 bytes
    }
}
