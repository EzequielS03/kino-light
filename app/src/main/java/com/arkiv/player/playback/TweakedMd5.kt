package com.arkiv.player.playback

/**
 * El MD5 modificado con el que Magis firma cada segmento de TV en vivo.
 *
 * Respecto de un MD5 de manual cambian **solo dos cosas** (derivadas emulando el binario
 * propietario; ver `magia/tweaked_md5.py`, que es la referencia y está verificada contra
 * el `.so` en 200 bloques aleatorios):
 *
 * 1. El message schedule de la 1ª vuelta es [ROUND1], no `0..15`. Las vueltas 2–4 son estándar.
 * 2. Cuatro constantes K distintas — rondas 42, 45, 54 y 62 — con pinta de erratas de
 *    transcripción del MD5 original.
 *
 * IV, funciones F/G/H/I, shifts, padding little-endian y feed-forward son los de MD5.
 * Los cinco vectores capturados de la app son el test de aceptación.
 */
object TweakedMd5 {
    private val SALT = "salt3333=4".toByteArray() +
        byteArrayOf(0x98.toByte(), 0x0d, 0x0a, 0x15, 0x32, 0xc9.toByte(),
                    0xc3.toByte(), 0x82.toByte(), 0x17, 0x08, 0xc0.toByte())

    private val K = intArrayOf(
        -0x28955b88, -0x173848aa, 0x242070db, -0x3e423112,
        -0x0a83f051, 0x4787c62a, -0x57cfb9ed, -0x02b96aff,
        0x698098d8, -0x74bb0851, -0x0000a44f, -0x76a32842,
        0x6b901122, -0x02678e6d, -0x5986bc72, 0x49b40821,
        -0x09e1da9e, -0x3fbf4cc0, 0x265e5a51, -0x16493856,
        -0x29d0efa3, 0x02441453, -0x275e197f, -0x182c0438,
        0x21e1cde6, -0x3cc8f82a, -0x0b2af279, 0x455a14ed,
        -0x561c16fb, -0x03105c08, 0x676f02d9, -0x72d5b376,
        -0x0005c6be, -0x788e097f, 0x6d9d6122, -0x021ac7f4,
        -0x5b4115bc, 0x4bdecfa9, -0x0944b4a0, -0x41404390,
        0x289b7ec6, -0x155ed806, -0x2b10cf7b, 0x04881d05,
        -0x262b2fc7, -0x1924661b, 0x1fa27cf8, -0x3b53a99b,
        -0x0bd6ddbc, 0x432aff97, -0x546bdc59, -0x036c5fc7,
        0x655b59c3, -0x70f3336e, -0x00100b83, -0x7a7ba22f,
        0x6fa87e4f, -0x01d31920, -0x5cfebcec, 0x4e0811a1,
        -0x08ac817e, -0x42c50dcb, 0x2ad7d2bb, -0x14792c6f,
    )

    // El tweak: cuatro constantes cambiadas. Se escriben en hexadecimal literal para que
    // se puedan cotejar de un vistazo contra la tabla del docstring de tweaked_md5.py.
    private val KT = K.copyOf().also {
        it[42] = 0xd46f3085.toInt()   // estándar d4ef3085
        it[45] = 0xe6bd99e5.toInt()   // estándar e6db99e5
        it[54] = 0xffecc47d.toInt()   // estándar ffeff47d
        it[62] = 0x2da7d2bb.toInt()   // estándar 2ad7d2bb
    }

    private val S = intArrayOf(
        7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22,
        5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20,
        4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23,
        6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21,
    )

    /** El tweak: el schedule de la 1ª vuelta. Las otras tres son las fórmulas estándar. */
    private val ROUND1 = intArrayOf(10, 11, 12, 13, 14, 15, 6, 7, 8, 9, 0, 1, 2, 3, 4, 5)

    private val G = IntArray(64) { i ->
        when {
            i < 16 -> ROUND1[i]
            i < 32 -> (5 * i + 1) % 16
            i < 48 -> (3 * i + 5) % 16
            else -> (7 * i) % 16
        }
    }

    private fun rotl(x: Int, n: Int) = (x shl n) or (x ushr (32 - n))

    private fun compress(estado: IntArray, bloque: ByteArray, off: Int) {
        val m = IntArray(16) { j ->
            val p = off + j * 4
            (bloque[p].toInt() and 0xff) or
                ((bloque[p + 1].toInt() and 0xff) shl 8) or
                ((bloque[p + 2].toInt() and 0xff) shl 16) or
                ((bloque[p + 3].toInt() and 0xff) shl 24)
        }
        var a = estado[0]; var b = estado[1]; var c = estado[2]; var d = estado[3]
        for (i in 0 until 64) {
            val f = when {
                i < 16 -> (b and c) or (b.inv() and d)
                i < 32 -> (d and b) or (d.inv() and c)
                i < 48 -> b xor c xor d
                else -> c xor (b or d.inv())
            }
            val suma = f + a + KT[i] + m[G[i]]
            a = d; d = c; c = b
            b += rotl(suma, S[i])
        }
        estado[0] += a; estado[1] += b; estado[2] += c; estado[3] += d
    }

    fun digestHex(msg: ByteArray): String {
        // IV de MD5: 67452301 efcdab89 98badcfe 10325476, en little-endian.
        val estado = intArrayOf(0x67452301, -0x10325477, -0x67452302, 0x10325476)
        val resto = msg.size % 64
        var i = 0
        while (i + 64 <= msg.size - resto) { compress(estado, msg, i); i += 64 }

        val cola = msg.copyOfRange(msg.size - resto, msg.size)
        val relleno = ByteArray(((56 - (cola.size + 1)) % 64 + 64) % 64)
        val bits = msg.size.toLong() * 8
        val largo = ByteArray(8) { ((bits ushr (it * 8)) and 0xff).toByte() }
        val final = cola + byteArrayOf(0x80.toByte()) + relleno + largo
        var j = 0
        while (j < final.size) { compress(estado, final, j); j += 64 }

        val sb = StringBuilder(32)
        estado.forEach { palabra ->
            for (b in 0 until 4) sb.append("%02x".format((palabra ushr (b * 8)) and 0xff))
        }
        return sb.toString()
    }

    /** `sign2` para un token de sesión y un momento en milisegundos. */
    fun signO3(token: String, startMoment: Long): String = digestHex(
        "token=$token&sign2_method=sign_o3&instance=0&start_moment=$startMoment"
            .toByteArray() + SALT
    )
}
