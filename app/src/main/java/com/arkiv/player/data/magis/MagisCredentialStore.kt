package com.arkiv.player.data.magis

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Sesión del portal de Magis tal como se guarda en el aparato. El [userToken] es EFÍMERO: el
 * portal lo mata cuando quiere y sin avisar, así que esto es un caché, no una verdad — quien
 * detecta que murió es [MagisSession.conSesionValida].
 *
 * El [sn] es el device acuñado y es lo ÚNICO realmente valioso acá: un `sn` inventado no activa
 * (`snToken已经失效`), y dos identidades sobre el mismo `sn` se expulsan mutuamente del portal.
 */
internal data class SesionGuardada(
    val userId: String,
    val userToken: String,
    val jwtToken: String,
    val sn: String,
)

internal interface MagisCredentialStore {
    fun guardarSesion(s: SesionGuardada)
    fun leerSesion(): SesionGuardada?
    fun guardarCuenta(email: String, password: String)
    fun leerCuenta(): Pair<String, String>?
    fun borrarCuenta()
}

/**
 * Real implementation over `EncryptedSharedPreferences`, in its own file -- separate from the
 * legacy `arkiv_pb_secure` that `SecureDeviceStore` used to write (removed in Task 9 along with
 * PocketBase; `SettingsStore` still reads it once to migrate whatever was there). Magis's
 * password travels in the clear to the portal on every relogin, so it has to be saved, and saved
 * encrypted. Uses [PrefsCifradas] for the same reason `SecureDeviceStore` did: a file the
 * Keystore can no longer decrypt shouldn't be able to keep the app from starting.
 */
internal class EncryptedMagisCredentialStore(context: Context) : MagisCredentialStore {

    private val prefs: SharedPreferences = run {
        val app = context.applicationContext
        PrefsCifradas.abrirOReparar(
            crear = { cifradas(app) },
            tirarLoIndescifrable = {
                Log.w(TAG, "Magis prefs undecryptable: starting fresh (need to re-link)")
                runCatching { app.deleteSharedPreferences(PREFS) }
            },
            sinCifrar = {
                Log.e(TAG, "the Keystore won't even work freshly thrown: Magis prefs left UNENCRYPTED")
                app.getSharedPreferences(PREFS_PLANAS, Context.MODE_PRIVATE)
            },
        )
    }

    override fun guardarSesion(s: SesionGuardada) {
        prefs.edit()
            .putString(K_USER_ID, s.userId)
            .putString(K_USER_TOKEN, s.userToken)
            .putString(K_JWT, s.jwtToken)
            .putString(K_SN, s.sn)
            .apply()
    }

    override fun leerSesion(): SesionGuardada? {
        val sn = prefs.getString(K_SN, null) ?: return null
        return SesionGuardada(
            userId = prefs.getString(K_USER_ID, null).orEmpty(),
            userToken = prefs.getString(K_USER_TOKEN, null).orEmpty(),
            jwtToken = prefs.getString(K_JWT, null).orEmpty(),
            sn = sn,
        )
    }

    override fun guardarCuenta(email: String, password: String) {
        prefs.edit().putString(K_EMAIL, email).putString(K_PASSWORD, password).apply()
    }

    override fun leerCuenta(): Pair<String, String>? {
        val email = prefs.getString(K_EMAIL, null)?.takeIf { it.isNotBlank() } ?: return null
        val password = prefs.getString(K_PASSWORD, null)?.takeIf { it.isNotBlank() } ?: return null
        return email to password
    }

    override fun borrarCuenta() {
        prefs.edit().remove(K_EMAIL).remove(K_PASSWORD).apply()
    }

    private companion object {
        const val TAG = "MagisStore"
        const val PREFS = "arkiv_magis_secure"

        /** Solo si el Keystore está roto de raíz. Ver [PrefsCifradas]. */
        const val PREFS_PLANAS = "arkiv_magis_plano"

        const val K_USER_ID = "userId"
        const val K_USER_TOKEN = "userToken"
        const val K_JWT = "jwtToken"
        const val K_SN = "sn"
        const val K_EMAIL = "email"
        const val K_PASSWORD = "password"

        fun cifradas(app: Context): SharedPreferences = EncryptedSharedPreferences.create(
            app,
            PREFS,
            MasterKey.Builder(app).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }
}
