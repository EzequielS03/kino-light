package com.arkiv.player.data.credentials

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.arkiv.player.data.magis.EncryptedPrefs

interface RemoteCredentialsStore {
    fun read(): RemoteCredentials?
    fun save(credentials: RemoteCredentials)
    fun clear()
}

/**
 * Where the five downloaded-and-recombined third-party credentials live on the device, once
 * activation succeeds. Own preferences file, separate from
 * [com.arkiv.player.data.magis.MagisCredentialStore]: a different concern (app-level third-party
 * secrets vs. a per-device Magis session). Same [EncryptedPrefs.openOrRepair] pattern: a Keystore
 * that can no longer decrypt this file must not be able to brick the app -- it's treated the same
 * as "never activated", so the activation screen reappears and the person re-consents once.
 */
class EncryptedRemoteCredentialsStore(context: Context) : RemoteCredentialsStore {

    private val prefs: SharedPreferences = run {
        val app = context.applicationContext
        EncryptedPrefs.openOrRepair(
            create = { encrypted(app) },
            discardUndecryptable = {
                Log.w(TAG, "Credentials prefs undecryptable: starting fresh (need to re-activate)")
                runCatching { app.deleteSharedPreferences(PREFS) }
            },
            unencrypted = {
                Log.e(TAG, "the Keystore won't even work freshly thrown: credentials left UNENCRYPTED")
                app.getSharedPreferences(PREFS_PLAIN, Context.MODE_PRIVATE)
            },
        )
    }

    override fun read(): RemoteCredentials? {
        val key = prefs.getString(K_3DES_KEY, null) ?: return null
        return RemoteCredentials(
            iptv3desKey = key,
            iptvHosts = prefs.getString(K_HOSTS, null).orEmpty(),
            iptvAppId = prefs.getString(K_APP_ID, null).orEmpty(),
            iptvApkVersion = prefs.getString(K_APK_VERSION, null).orEmpty(),
            tmdbApiKey = prefs.getString(K_TMDB_KEY, null).orEmpty(),
        )
    }

    override fun save(credentials: RemoteCredentials) {
        prefs.edit()
            .putString(K_3DES_KEY, credentials.iptv3desKey)
            .putString(K_HOSTS, credentials.iptvHosts)
            .putString(K_APP_ID, credentials.iptvAppId)
            .putString(K_APK_VERSION, credentials.iptvApkVersion)
            .putString(K_TMDB_KEY, credentials.tmdbApiKey)
            .apply()
    }

    override fun clear() {
        prefs.edit().clear().apply()
    }

    private companion object {
        const val TAG = "CredentialsStore"
        const val PREFS = "arkiv_credentials_secure"

        /** Only if the Keystore is broken at the root. See [EncryptedPrefs]. */
        const val PREFS_PLAIN = "arkiv_credentials_plano"

        const val K_3DES_KEY = "iptv3desKey"
        const val K_HOSTS = "iptvHosts"
        const val K_APP_ID = "iptvAppId"
        const val K_APK_VERSION = "iptvApkVersion"
        const val K_TMDB_KEY = "tmdbApiKey"

        fun encrypted(app: Context): SharedPreferences = EncryptedSharedPreferences.create(
            app,
            PREFS,
            MasterKey.Builder(app).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }
}
