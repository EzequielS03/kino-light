package com.arkiv.player.pocketbase

import java.util.Random
import java.util.UUID

/** Identidad local de un dispositivo Arkiv frente a PocketBase. */
data class DeviceIdentity(
    val accountId: String,
    val deviceId: String,
    val email: String,
    val password: String,
    val kind: String,
)

object DeviceIdentityFactory {
    private const val PASSWORD_LEN = 32
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"

    /** Crea una cuenta nueva con su dispositivo teléfono. */
    fun newPhoneAccount(random: Random = java.security.SecureRandom()): DeviceIdentity =
        build(accountId = UUID.randomUUID().toString(), kind = "phone", random = random)

    /** Crea un dispositivo TV dentro de una cuenta existente. */
    fun newTvDevice(accountId: String, random: Random = java.security.SecureRandom()): DeviceIdentity =
        build(accountId = accountId, kind = "tv", random = random)

    private fun build(accountId: String, kind: String, random: Random): DeviceIdentity {
        val deviceId = UUID.randomUUID().toString()
        val password = buildString {
            repeat(PASSWORD_LEN) { append(ALPHABET[random.nextInt(ALPHABET.length)]) }
        }
        return DeviceIdentity(
            accountId = accountId,
            deviceId = deviceId,
            email = "$deviceId@arkiv.local",
            password = password,
            kind = kind,
        )
    }
}
