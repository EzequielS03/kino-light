package com.arkiv.player.data.magis

/**
 * MD5 modificado de Magis TV (framework coolx), usado para firmar segmentos de canal en vivo
 * (ver [Sign2]). Puerto exacto de `tweaked_md5.py` en arkiv-api — NO es MD5 estándar: el
 * message-schedule de la 1a vuelta y 4 constantes K están alterados respecto al MD5 original.
 * Validar cualquier cambio contra los 5 vectores de [Sign2Test], capturados de la app real.
 */
internal object TweakedMd5 {

    private val K: IntArray = intArrayOf(
        0xd76aa478u.toInt(), 0xe8c7b756u.toInt(), 0x242070dbu.toInt(), 0xc1bdceeeu.toInt(),
        0xf57c0fafu.toInt(), 0x4787c62au.toInt(), 0xa8304613u.toInt(), 0xfd469501u.toInt(),
        0x698098d8u.toInt(), 0x8b44f7afu.toInt(), 0xffff5bb1u.toInt(), 0x895cd7beu.toInt(),
        0x6b901122u.toInt(), 0xfd987193u.toInt(), 0xa679438eu.toInt(), 0x49b40821u.toInt(),
        0xf61e2562u.toInt(), 0xc040b340u.toInt(), 0x265e5a51u.toInt(), 0xe9b6c7aau.toInt(),
        0xd62f105du.toInt(), 0x02441453u.toInt(), 0xd8a1e681u.toInt(), 0xe7d3fbc8u.toInt(),
        0x21e1cde6u.toInt(), 0xc33707d6u.toInt(), 0xf4d50d87u.toInt(), 0x455a14edu.toInt(),
        0xa9e3e905u.toInt(), 0xfcefa3f8u.toInt(), 0x676f02d9u.toInt(), 0x8d2a4c8au.toInt(),
        0xfffa3942u.toInt(), 0x8771f681u.toInt(), 0x6d9d6122u.toInt(), 0xfde5380cu.toInt(),
        0xa4beea44u.toInt(), 0x4bdecfa9u.toInt(), 0xf6bb4b60u.toInt(), 0xbebfbc70u.toInt(),
        0x289b7ec6u.toInt(), 0xeaa127fau.toInt(), 0xd46f3085u.toInt(), 0x04881d05u.toInt(), // [42] tweak: estandar seria d4ef3085
        0xd9d4d039u.toInt(), 0xe6bd99e5u.toInt(), 0x1fa27cf8u.toInt(), 0xc4ac5665u.toInt(), // [45] tweak: estandar seria e6db99e5
        0xf4292244u.toInt(), 0x432aff97u.toInt(), 0xab9423a7u.toInt(), 0xfc93a039u.toInt(),
        0x655b59c3u.toInt(), 0x8f0ccc92u.toInt(), 0xffecc47du.toInt(), 0x85845dd1u.toInt(), // [54] tweak: estandar seria ffeff47d
        0x6fa87e4fu.toInt(), 0xfe2ce6e0u.toInt(), 0xa3014314u.toInt(), 0x4e0811a1u.toInt(),
        0xf7537e82u.toInt(), 0xbd3af235u.toInt(), 0x2da7d2bbu.toInt(), 0xeb86d391u.toInt(), // [62] tweak: estandar seria 2ad7d2bb
    )

    private val S: IntArray = intArrayOf(
        7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22,
        5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20,
        4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23,
        6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21,
    )

    // Round 1 usa este schedule (el tweak); rounds 2/3/4 usan las formulas estandar de MD5.
    private val ROUND1_SCHEDULE = intArrayOf(10, 11, 12, 13, 14, 15, 6, 7, 8, 9, 0, 1, 2, 3, 4, 5)

    private val G: IntArray = ROUND1_SCHEDULE +
        IntArray(16) { i -> (5 * (i + 16) + 1) % 16 } +
        IntArray(16) { i -> (3 * (i + 32) + 5) % 16 } +
        IntArray(16) { i -> (7 * (i + 48)) % 16 }

    // IV estandar de MD5: words 0x67452301, 0xefcdab89, 0x98badcfe, 0x10325476 (little-endian).
    private val MD5_IV = byteArrayOf(
        0x01, 0x23, 0x45, 0x67, 0x89.toByte(), 0xab.toByte(), 0xcd.toByte(), 0xef.toByte(),
        0xfe.toByte(), 0xdc.toByte(), 0xba.toByte(), 0x98.toByte(), 0x76, 0x54, 0x32, 0x10,
    )

    private fun le32(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xff) or
            ((bytes[offset + 1].toInt() and 0xff) shl 8) or
            ((bytes[offset + 2].toInt() and 0xff) shl 16) or
            ((bytes[offset + 3].toInt() and 0xff) shl 24)

    private fun rotl(x: Int, s: Int): Int = (x shl s) or (x ushr (32 - s))

    /** Comprime un bloque de 64 bytes (`block`, desde `blockOffset`) contra el estado de 4 words. */
    private fun compress(state: IntArray, block: ByteArray, blockOffset: Int): IntArray {
        val m = IntArray(16) { i -> le32(block, blockOffset + i * 4) }
        val a0 = state[0]; val b0 = state[1]; val c0 = state[2]; val d0 = state[3]
        var a = a0; var b = b0; var c = c0; var d = d0

        for (i in 0 until 64) {
            val f = when {
                i < 16 -> (b and c) or (b.inv() and d)
                i < 32 -> (d and b) or (d.inv() and c)
                i < 48 -> b xor c xor d
                else -> c xor (b or d.inv())
            } + a + K[i] + m[G[i]]
            val nuevaB = b + rotl(f, S[i])
            a = d; d = c; c = b; b = nuevaB
        }

        return intArrayOf(a + a0, b + b0, c + c0, d + d0)
    }

    /** Digest completo de [msg]. Padding little-endian estándar de MD5. Devuelve hex minúsculas. */
    fun digestHex(msg: ByteArray): String {
        var state = intArrayOf(le32(MD5_IV, 0), le32(MD5_IV, 4), le32(MD5_IV, 8), le32(MD5_IV, 12))

        val fullBlocks = msg.size / 64
        for (i in 0 until fullBlocks) {
            state = compress(state, msg, i * 64)
        }

        val remainder = msg.copyOfRange(fullBlocks * 64, msg.size)
        val bitLen = msg.size.toLong() * 8
        // OJO: el % de Kotlin puede dar negativo con divisores positivos si el dividendo es
        // negativo (a diferencia de Python, donde % siempre da no-negativo con modulo positivo)
        // -- normalizar con +64 antes del segundo % o esto rompe el padding en ciertos tamaños.
        val padLen = ((56 - (remainder.size + 1)) % 64 + 64) % 64
        val final = remainder + byteArrayOf(0x80.toByte()) + ByteArray(padLen) +
            ByteArray(8) { i -> ((bitLen ushr (i * 8)) and 0xffL).toByte() }

        for (i in final.indices step 64) {
            state = compress(state, final, i)
        }

        return state.joinToString("") { word ->
            (0 until 4).joinToString("") { b -> "%02x".format((word ushr (b * 8)) and 0xff) }
        }
    }
}
