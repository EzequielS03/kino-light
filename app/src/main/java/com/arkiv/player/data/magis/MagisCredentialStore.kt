package com.arkiv.player.data.magis

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.arkiv.player.pocketbase.PrefsCifradas

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
 * Implementación real sobre `EncryptedSharedPreferences`, en su propio archivo (aparte del de
 * PocketBase): la contraseña de Magis viaja en claro al portal en cada relogin, así que hay que
 * guardarla, y guardarla cifrada. Usa [PrefsCifradas] por lo mismo que `SecureDeviceStore`: un
 * archivo que el Keystore ya no descifra no puede dejar la app sin arrancar.
 */
internal class EncryptedMagisCredentialStore(context: Context) : MagisCredentialStore {

    private val prefs: SharedPreferences = run {
        val app = context.applicationContext
        PrefsCifradas.abrirOReparar(
            crear = { cifradas(app) },
            tirarLoIndescifrable = {
                Log.w(TAG, "prefs de Magis indescifrables: de cero (hay que volver a vincular)")
                runCatching { app.deleteSharedPreferences(PREFS) }
            },
            sinCifrar = {
                Log.e(TAG, "el Keystore no da ni recien tirado: prefs de Magis SIN cifrar")
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
