package com.arkiv.player.pocketbase

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/** Guarda identidad + token del dispositivo en prefs cifradas. */
class SecureDeviceStore(context: Context) {
    private val prefs: SharedPreferences = run {
        val app = context.applicationContext
        val masterKey = MasterKey.Builder(app)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            app,
            "arkiv_pb_secure",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    fun save(identity: DeviceIdentity) {
        prefs.edit()
            .putString(K_ACCOUNT, identity.accountId)
            .putString(K_DEVICE, identity.deviceId)
            .putString(K_EMAIL, identity.email)
            .putString(K_PASSWORD, identity.password)
            .putString(K_KIND, identity.kind)
            .apply()
    }

    fun load(): DeviceIdentity? {
        val accountId = prefs.getString(K_ACCOUNT, null) ?: return null
        return DeviceIdentity(
            accountId = accountId,
            deviceId = prefs.getString(K_DEVICE, null) ?: return null,
            email = prefs.getString(K_EMAIL, null) ?: return null,
            password = prefs.getString(K_PASSWORD, null) ?: return null,
            kind = prefs.getString(K_KIND, null) ?: return null,
        )
    }

    fun saveToken(token: String) { prefs.edit().putString(K_TOKEN, token).apply() }
    fun token(): String? = prefs.getString(K_TOKEN, null)
    fun clear() { prefs.edit().clear().apply() }

    private companion object {
        const val K_ACCOUNT = "accountId"
        const val K_DEVICE = "deviceId"
        const val K_EMAIL = "email"
        const val K_PASSWORD = "password"
        const val K_KIND = "kind"
        const val K_TOKEN = "token"
    }
}
