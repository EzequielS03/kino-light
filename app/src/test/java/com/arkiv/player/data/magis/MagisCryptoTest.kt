package com.arkiv.player.data.magis

import org.junit.Assert.assertEquals
import org.junit.Test

class MagisCryptoTest {

    // Llave real de IPTV_3DES_KEY (BuildConfig), vector calculado con pycryptodome fuera de
    // este repo para no depender circularmente del propio código bajo test.
    private val key = "e7af1ed7de1ffddd7bd3fe37ebdffde9ef3fe1ae39edfeb8"

    @Test
    fun `encryptBody produces the exact wire for a known vector`() {
        val plain = """{"hola":"mundo"}"""
        val esperado = "336b6e7968596c346c48552f313276566c5134474951646642336151306b4f32"
        assertEquals(esperado, MagisCrypto(key).encryptBody(plain))
    }

    @Test
    fun `decryptBlob reverses encryptBody`() {
        val plain = """{"hola":"mundo"}"""
        val crypto = MagisCrypto(key)
        assertEquals(plain, crypto.decryptBlob(crypto.encryptBody(plain)))
    }
}
