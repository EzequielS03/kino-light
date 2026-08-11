package com.arkiv.player.playback

import org.junit.Assert.assertEquals
import org.junit.Test

class TweakedMd5Test {
    /** Los mismos vectores que verifican la implementación de Python, capturados de la app real. */
    private val vectores = listOf(
        Triple("941d98961990d67e249dcd1ac57378c8", 1786228951248L, "42eda1217c11706f8034f00831f11645"),
        Triple("941d98961990d67e249dcd1ac57378c8", 1786229028826L, "7b7a1751bd8dc9fa4cb38bcc8dd8acb3"),
        Triple("941d98961990d67e249dcd1ac57378c8", 1786229709567L, "0cccdfc85f900a6ee407eedd13003494"),
        Triple("c3ec544b53a526c59ab677ffbdffa1e0", 1786223278615L, "2e055d6f2c0407c82017286e8f4a31ad"),
        Triple("c3ec544b53a526c59ab677ffbdffa1e0", 1786225491689L, "095a0c6ebc25e6570705fd9d16c6b67b"),
    )

    @Test
    fun `los cinco vectores reales`() {
        vectores.forEach { (token, momento, esperado) ->
            assertEquals("momento $momento", esperado, TweakedMd5.signO3(token, momento))
        }
    }

    @Test
    fun `las fronteras del padding no se corren`() {
        // 55 y 56 bytes son el borde donde el padding pasa a necesitar un bloque extra;
        // un error de un byte ahí no lo detectan los vectores, que miden ~120 bytes.
        listOf(0, 55, 56, 63, 64, 65).forEach { n ->
            assertEquals("largo $n", 32, TweakedMd5.digestHex(ByteArray(n)).length)
        }
    }

    @Test
    fun `no es MD5 estandar`() {
        // Si alguien "arregla" las constantes tweakeadas creyendo que son erratas, esto lo caza.
        val md5 = java.security.MessageDigest.getInstance("MD5")
            .digest(ByteArray(64)).joinToString("") { "%02x".format(it) }
        org.junit.Assert.assertNotEquals(md5, TweakedMd5.digestHex(ByteArray(64)))
    }
}
