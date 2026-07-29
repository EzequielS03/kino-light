package com.arkiv.player.pairing

import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Cripto del pareo. El `code` (alta entropía, solo en el QR) es el secreto compartido.
 * Separación de dominio: el hash de lookup y la clave de cifrado derivan del code con
 * prefijos DISTINTOS, así el codeHash guardado no revela la clave.
 *
 * NOTA testabilidad: usa java.util.Base64 (JVM) para poder testear sin Android. En Android
 * java.util.Base64 existe desde API 26; el minSdk del proyecto lo soporta.
 */
object PairCrypto {
    private const val LOOKUP_PREFIX = "arkiv-pair-lookup:"
    private const val KEY_PREFIX = "arkiv-pair-key:"
    private const val IV_LEN = 12
    private const val TAG_BITS = 128

    fun codeHash(code: String): String = sha256(LOOKUP_PREFIX + code).toHex()

    private fun encKey(code: String): ByteArray = sha256(KEY_PREFIX + code) // 32 bytes

    fun encrypt(plaintext: String, code: String): String {
        val iv = ByteArray(IV_LEN).also { java.security.SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(encKey(code), "AES"), GCMParameterSpec(TAG_BITS, iv))
        val ct = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        return b64encode(iv + ct)
    }

    fun decrypt(b64: String, code: String): String {
        val all = b64decode(b64)
        val iv = all.copyOfRange(0, IV_LEN)
        val ct = all.copyOfRange(IV_LEN, all.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(encKey(code), "AES"), GCMParameterSpec(TAG_BITS, iv))
        return String(cipher.doFinal(ct), Charsets.UTF_8)
    }

    private fun sha256(s: String): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8))

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    // Base64 sin depender de Android para los unit tests.
    private fun b64encode(b: ByteArray): String = java.util.Base64.getEncoder().encodeToString(b)
    private fun b64decode(s: String): ByteArray = java.util.Base64.getDecoder().decode(s)
}
