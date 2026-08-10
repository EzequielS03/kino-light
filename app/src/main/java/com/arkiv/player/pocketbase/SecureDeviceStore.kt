package com.arkiv.player.pocketbase

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/** Guarda identidad + token del dispositivo en prefs cifradas. */
class SecureDeviceStore(context: Context) : DeviceStore {
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

    override fun save(identity: DeviceIdentity) {
        prefs.edit()
            .putString(K_ACCOUNT, identity.accountId)
            .putString(K_DEVICE, identity.deviceId)
            .putString(K_EMAIL, identity.email)
            .putString(K_PASSWORD, identity.password)
            .putString(K_KIND, identity.kind)
            .apply()
    }

    override fun load(): DeviceIdentity? {
        val accountId = prefs.getString(K_ACCOUNT, null) ?: return null
        return DeviceIdentity(
            accountId = accountId,
            deviceId = prefs.getString(K_DEVICE, null) ?: return null,
            email = prefs.getString(K_EMAIL, null) ?: return null,
            password = prefs.getString(K_PASSWORD, null) ?: return null,
            kind = prefs.getString(K_KIND, null) ?: return null,
        )
    }

    override fun saveToken(token: String) { prefs.edit().putString(K_TOKEN, token).apply() }
    override fun token(): String? = prefs.getString(K_TOKEN, null)
    override fun clear() { prefs.edit().clear().apply() }

    override fun savePersonEmail(email: String) { prefs.edit().putString(K_PERSON_EMAIL, email).apply() }
    override fun personEmail(): String? = prefs.getString(K_PERSON_EMAIL, null)
    override fun clearPersonEmail() { prefs.edit().remove(K_PERSON_EMAIL).apply() }

    private companion object {
        const val K_ACCOUNT = "accountId"
        const val K_DEVICE = "deviceId"
        const val K_EMAIL = "email"
        const val K_PASSWORD = "password"
        const val K_KIND = "kind"
        const val K_TOKEN = "token"
        const val K_PERSON_EMAIL = "personEmail"
    }
}
