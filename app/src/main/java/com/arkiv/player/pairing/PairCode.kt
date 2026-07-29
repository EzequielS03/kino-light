package com.arkiv.player.pairing

import java.util.Random

/** Genera códigos de pareo de alta entropía (base32, ~130 bits). */
object PairCode {
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567" // base32 RFC4648 sin 0/1/8/9
    private const val LEN = 26

    fun generate(random: Random = java.security.SecureRandom()): String = buildString {
        repeat(LEN) { append(ALPHABET[random.nextInt(ALPHABET.length)]) }
    }
}
