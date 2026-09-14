package com.arkiv.player.data.magis

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Magis portal session as saved on the device. The [userToken] is EPHEMERAL: the portal kills it
 * whenever it wants with no warning, so this is a cache, not a source of truth — the one that
 * detects it died is [MagisSession.withValidSession].
 *
 * The [sn] is the minted device and is the ONLY thing that's really valuable here: a made-up `sn`
 * doesn't activate (`snToken已经失效`), and two identities over the same `sn` mutually kick each
 * other out of the portal.
 */
internal data class StoredSession(
    val userId: String,
    val userToken: String,
    val jwtToken: String,
    val sn: String,
)

internal interface MagisCredentialStore {
    fun saveSession(s: StoredSession)
    fun readSession(): StoredSession?
    fun saveAccount(email: String, password: String)
    fun readAccount(): Pair<String, String>?
    fun clearAccount()
}

/**
 * Real implementation over `EncryptedSharedPreferences`, in its own file -- separate from the
 * legacy `arkiv_pb_secure` that `SecureDeviceStore` used to write (removed in Task 9 along with
 * PocketBase; `SettingsStore` still reads it once to migrate whatever was there). Magis's
 * password travels in the clear to the portal on every relogin, so it has to be saved, and saved
 * encrypted. Uses [EncryptedPrefs] for the same reason `SecureDeviceStore` did: a file the
 * Keystore can no longer decrypt shouldn't be able to keep the app from starting.
 */
internal class EncryptedMagisCredentialStore(context: Context) : MagisCredentialStore {

    private val prefs: SharedPreferences = run {
        val app = context.applicationContext
        EncryptedPrefs.openOrRepair(
            create = { encrypted(app) },
            discardUndecryptable = {
                Log.w(TAG, "Magis prefs undecryptable: starting fresh (need to re-link)")
                runCatching { app.deleteSharedPreferences(PREFS) }
            },
            unencrypted = {
                Log.e(TAG, "the Keystore won't even work freshly thrown: Magis prefs left UNENCRYPTED")
                app.getSharedPreferences(PREFS_PLAIN, Context.MODE_PRIVATE)
            },
        )
    }

    override fun saveSession(s: StoredSession) {
        prefs.edit()
            .putString(K_USER_ID, s.userId)
            .putString(K_USER_TOKEN, s.userToken)
            .putString(K_JWT, s.jwtToken)
            .putString(K_SN, s.sn)
            .apply()
    }

    override fun readSession(): StoredSession? {
        val sn = prefs.getString(K_SN, null) ?: return null
        return StoredSession(
            userId = prefs.getString(K_USER_ID, null).orEmpty(),
            userToken = prefs.getString(K_USER_TOKEN, null).orEmpty(),
            jwtToken = prefs.getString(K_JWT, null).orEmpty(),
            sn = sn,
        )
    }

    override fun saveAccount(email: String, password: String) {
        prefs.edit().putString(K_EMAIL, email).putString(K_PASSWORD, password).apply()
    }

    override fun readAccount(): Pair<String, String>? {
        val email = prefs.getString(K_EMAIL, null)?.takeIf { it.isNotBlank() } ?: return null
        val password = prefs.getString(K_PASSWORD, null)?.takeIf { it.isNotBlank() } ?: return null
        return email to password
    }

    override fun clearAccount() {
        prefs.edit().remove(K_EMAIL).remove(K_PASSWORD).apply()
    }

    private companion object {
        const val TAG = "MagisStore"
        const val PREFS = "arkiv_magis_secure"

        /** Only if the Keystore is broken at the root. See [EncryptedPrefs]. */
        const val PREFS_PLAIN = "arkiv_magis_plano"

        const val K_USER_ID = "userId"
        const val K_USER_TOKEN = "userToken"
        const val K_JWT = "jwtToken"
        const val K_SN = "sn"
        const val K_EMAIL = "email"
        const val K_PASSWORD = "password"

        fun encrypted(app: Context): SharedPreferences = EncryptedSharedPreferences.create(
            app,
            PREFS,
            MasterKey.Builder(app).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }
}
