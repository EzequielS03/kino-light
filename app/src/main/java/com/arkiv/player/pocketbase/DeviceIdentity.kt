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

    /**
     * Cuenta nueva con el aparato de ESTE fierro, sea teléfono o TV.
     *
     * El `kind` no es cosmético: el gateway cuenta el cupo de la licencia por ese campo
     * (`maxCelulares` / `maxTvs`). Darse de alta siempre como `"phone"` hacía que una TV recién
     * instalada consumiera el cupo de celulares y el pareo fallara con "tope alcanzado".
     */
    fun newAnonimo(esTv: Boolean, random: Random = java.security.SecureRandom()): DeviceIdentity =
        build(
            accountId = UUID.randomUUID().toString(),
            kind = if (esTv) "tv" else "phone",
            random = random,
        )

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
