package com.arkiv.player.data.magis

import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * Encryption for the Magis portal's bodies: hex(base64(3DES-EDE/ECB/PKCS5(json))).
 * [keyHex] is the 24-byte master key in hex (BuildConfig.IPTV_3DES_KEY).
 */
internal class MagisCrypto(keyHex: String) {

    private val key = SecretKeySpec(hexToBytes(keyHex), "DESede")

    fun encryptBody(plain: String): String {
        val cipher = Cipher.getInstance("DESede/ECB/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val ciphertext = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        val b64 = Base64.getEncoder().encodeToString(ciphertext)
        return bytesToHex(b64.toByteArray(Charsets.US_ASCII))
    }

    fun decryptBlob(wire: String): String {
        val inner = String(hexToBytes(wire), Charsets.US_ASCII)
        val ciphertext = Base64.getDecoder().decode(inner)
        val cipher = Cipher.getInstance("DESede/ECB/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, key)
        return String(cipher.doFinal(ciphertext), Charsets.UTF_8)
    }

    private fun hexToBytes(hex: String): ByteArray =
        ByteArray(hex.length / 2) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }

    private fun bytesToHex(bytes: ByteArray): String =
        bytes.joinToString("") { "%02x".format(it) }
}
