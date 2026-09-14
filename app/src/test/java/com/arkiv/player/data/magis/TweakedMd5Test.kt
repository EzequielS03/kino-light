package com.arkiv.player.data.magis

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
    fun `the five real vectors`() {
        vectores.forEach { (token, momento, esperado) ->
            assertEquals("momento $momento", esperado, TweakedMd5.signO3(token, momento))
        }
    }

    @Test
    fun `the padding boundaries don't shift`() {
        // 55 y 56 bytes son el borde donde el padding pasa a necesitar un bloque extra (63/64/65
        // cubren el borde análogo un bloque más adelante); un error de un byte ahí no lo detectan
        // los vectores de arriba, que miden ~120 bytes -siempre del mismo lado del borde-.
        //
        // Antes esto solo comprobaba `.length == 32`, que es cierto SIEMPRE (hexa de un digest de
        // 16 bytes), acierte o no el padding: no era una red, era un test que no podía fallar
        // (hallazgo "en la misma ola" de la revisión final). Estos valores son la salida REAL de
        // `digest_hex(bytes(n))` en `/Users/cristian/magia/tweaked_md5.py` -la referencia
        // verificada contra el `.so` propietario, ver el KDoc de [TweakedMd5]- para cada largo,
        // capturada así:
        //   python3 -c "from tweaked_md5 import digest_hex
        //                [print(n, digest_hex(bytes(n))) for n in [0,55,56,63,64,65]]"
        val vectoresDePadding = mapOf(
            0 to "788eb771bc499f0bc7f00fdb08c397aa",
            55 to "3df0dbf8fb79a50d50d4d1d95a40942c",
            56 to "0e0b477553c03363f907a303756fb565",
            63 to "16b54eb04d82dee39edc72de0532523d",
            64 to "acd46d59775f5cd639b96b2d1a4dc020",
            65 to "233f868f6402130ab977de8bd5d2b943",
        )
        vectoresDePadding.forEach { (n, esperado) ->
            assertEquals("largo $n", esperado, TweakedMd5.digestHex(ByteArray(n)))
        }
    }

    @Test
    fun `it's not standard MD5`() {
        // Si alguien "arregla" las constantes tweakeadas creyendo que son erratas, esto lo caza.
        val md5 = java.security.MessageDigest.getInstance("MD5")
            .digest(ByteArray(64)).joinToString("") { "%02x".format(it) }
        org.junit.Assert.assertNotEquals(md5, TweakedMd5.digestHex(ByteArray(64)))
    }
}
