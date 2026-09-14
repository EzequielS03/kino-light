package com.arkiv.player.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.arkiv.player.data.magis.EncryptedPrefs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Simple settings persisted in SharedPreferences. */
class SettingsStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("arkiv_settings", Context.MODE_PRIVATE)

    // Player's night mode: level of the black scrim over the video, from 0 (normal) to
    // DIM_MAX_LEVEL (full black). Persisted on purpose (not per session): whoever lowers it
    // watches almost always at night. The player clamps the level; this saves it exactly as it
    // arrives.
    private val _dimLevel = MutableStateFlow(prefs.getInt(KEY_DIM_LEVEL, 0))
    val dimLevel: StateFlow<Int> = _dimLevel

    // Has the artwork resolved before TMDB's exact match already been repaired? See
    // ArkivRepository.repairArtworkMatches. Marked ONLY when the whole pass finishes, so a launch
    // with no internet doesn't count it as done and leave titles mismatched forever.
    private val _artworkRematchDone = MutableStateFlow(prefs.getBoolean(KEY_ARTWORK_REMATCH, false))
    val artworkRematchDone: StateFlow<Boolean> = _artworkRematchDone

    // "Not now" to the offer to link Magis as soon as the TV is opened (Task 10, see
    // `shouldOfferMagisLink` in ui/tv/TvMagisLinkOffer.kt). It's a DEVICE decision, not an
    // account one -same criterion as [artworkRematchDone] above-: this is a TV for personal use,
    // not a kiosk shared between accounts. NO LONGER reset on any logout -Task 8 (sub-project 2B)
    // removed the "Log out" buttons from the Magis screens, which were the last callers of
    // `AccountManager.logout()`, so that path went dead-: with no Kino accounts there's no logout
    // to trigger the reset, and the offer stays reachable by hand from Settings
    // (`TvSettingsCuenta`) for anyone who wants to link Magis again without depending on this flag.
    private val _magisOfertaDescartada = MutableStateFlow(prefs.getBoolean(KEY_MAGIS_OFERTA_DESCARTADA, false))
    val magisOfertaDescartada: StateFlow<Boolean> = _magisOfertaDescartada

    // Task 7 (sub-project 2B): the DEVICE's 18+ lock. Used to live in `SecureDeviceStore`, which
    // Task 9 deletes along with the accounts -- it isn't account data, so it's rescued here first.
    // Same criterion as [magisOfertaDescartada]: per device, not per person.
    private val _adultosDesbloqueado = MutableStateFlow(prefs.getBoolean(KEY_ADULTOS_DESBLOQUEADO, false))
    val adultosDesbloqueado: StateFlow<Boolean> = _adultosDesbloqueado

    // The code that opens that lock, chosen from Ajustes. `null` = none was ever chosen and the
    // default rules; who decides that is `AdultsLock.effectiveCode`, not this store -- this only
    // saves what the person typed. It's plain text on purpose: the lock stops someone with the
    // remote, not someone with `adb` (see `AdultsLock`'s KDoc), so encrypting it would give a
    // sense of security the rest of the design doesn't back up.
    private val _codigoAdultos = MutableStateFlow(prefs.getString(KEY_CODIGO_ADULTOS, null))
    val codigoAdultos: StateFlow<String?> = _codigoAdultos

    // Marker for the 2026-08-14 one-time recents purge (see `ArkivApp.onCreate`). Same rescue as
    // [adultosDesbloqueado]: if it's lost, the purge simply runs once more -- no StateFlow needed
    // since nothing observes it, it's only read on launch.
    val recientesPurgados: Boolean
        get() = prefs.getBoolean(KEY_RECIENTES_PURGADOS, false)

    fun setDimLevel(v: Int) { prefs.edit().putInt(KEY_DIM_LEVEL, v).apply(); _dimLevel.value = v }

    fun setArtworkRematchDone(v: Boolean) {
        if (_artworkRematchDone.value == v) return
        prefs.edit().putBoolean(KEY_ARTWORK_REMATCH, v).apply()
        _artworkRematchDone.value = v
    }


    fun setMagisOfertaDescartada(v: Boolean) {
        if (_magisOfertaDescartada.value == v) return
        prefs.edit().putBoolean(KEY_MAGIS_OFERTA_DESCARTADA, v).apply()
        _magisOfertaDescartada.value = v
    }

    fun setAdultosDesbloqueado(v: Boolean) {
        if (_adultosDesbloqueado.value == v) return
        prefs.edit().putBoolean(KEY_ADULTOS_DESBLOQUEADO, v).apply()
        _adultosDesbloqueado.value = v
    }

    /** `null` deletes the key and returns the lock to its default code. */
    fun setCodigoAdultos(v: String?) {
        if (_codigoAdultos.value == v) return
        prefs.edit().apply { if (v == null) remove(KEY_CODIGO_ADULTOS) else putString(KEY_CODIGO_ADULTOS, v) }.apply()
        _codigoAdultos.value = v
    }

    fun setRecientesPurgados(v: Boolean) {
        prefs.edit().putBoolean(KEY_RECIENTES_PURGADOS, v).apply()
    }

    /** When "For you" was last attempted (0 = never). See `ForYouGate`. */
    val paraTiUltimoIntentoMs: Long get() = prefs.getLong(KEY_PARA_TI_ULTIMO_INTENTO, 0L)

    /** Whether that attempt failed on the model: then it's retried after 15 min, not 24 h. */
    val paraTiUltimoFueFalloDelModelo: Boolean get() = prefs.getBoolean(KEY_PARA_TI_FALLO_MODELO, false)

    fun marcarIntentoDeParaTi(ahoraMs: Long, falloDelModelo: Boolean) {
        prefs.edit()
            .putLong(KEY_PARA_TI_ULTIMO_INTENTO, ahoraMs)
            .putBoolean(KEY_PARA_TI_FALLO_MODELO, falloDelModelo)
            .apply()
    }

    /**
     * Pulls the 18+ lock from the device's encrypted store the first time it runs. `deStoreViejo`
     * is `null` when that store couldn't be read (see `ArkivApp.onCreate`) -- then it's left with
     * whatever's already here (or the default). Idempotent: on later launches `prefs` already has
     * the key and `valorMigrado` respects it without looking at the old store again.
     *
     * Writes straight to `prefs` instead of going through [setAdultosDesbloqueado]: that setter
     * doesn't write if the value didn't change (to avoid an extra `apply()` overwriting the
     * StateFlow), but here the most common case is exactly that -- the old store was never
     * unlocked and the result matches the in-memory default. If it went through the guard, the
     * key would never end up recorded and this function would look at `SecureDeviceStore` again
     * on every launch, which the comment above says does NOT happen.
     */
    fun migrarAdultosDesbloqueado(deStoreViejo: Boolean?) {
        val migrado = valorMigrado(leerNullable(KEY_ADULTOS_DESBLOQUEADO), deStoreViejo, false)
        prefs.edit().putBoolean(KEY_ADULTOS_DESBLOQUEADO, migrado).apply()
        _adultosDesbloqueado.value = migrado
    }

    /** Same rescue as [migrarAdultosDesbloqueado] for the recents-purge marker. */
    fun migrarRecientesPurgados(deStoreViejo: Boolean?) {
        setRecientesPurgados(valorMigrado(leerNullable(KEY_RECIENTES_PURGADOS), deStoreViejo, false))
    }

    /** `null` if `key` hasn't been written to these settings yet -- different from being `false`. */
    private fun leerNullable(key: String): Boolean? = if (prefs.contains(key)) prefs.getBoolean(key, false) else null

    /**
     * Fires [migrarAdultosDesbloqueado]/[migrarRecientesPurgados] by reading the OLD encrypted
     * file directly (Task 9, sub-project 2B).
     *
     * That file (`arkiv_pb_secure`) belonged to `SecureDeviceStore`, which Task 9 deletes along
     * with the rest of `pocketbase/` -- the two keys that matter (`adultosDesbloqueado`,
     * `recientesPurgados2026_08_14`) are NOT account data, so they're rescued by reading the same
     * file with the same scheme (`EncryptedSharedPreferences` + `MasterKey` AES256_GCM +
     * AES256_SIV/AES256_GCM) that class used, without resurrecting it. `EncryptedPrefs.openOrRepair`
     * is still alive because `EncryptedMagisCredentialStore` uses it -- reused here for the same
     * problem (a Keystore that no longer decrypts the file).
     *
     * If both keys already migrated, the old file isn't even looked at:
     * `EncryptedSharedPreferences.create` costs Keystore + Tink, and this is called on EVERY
     * launch. And if the file doesn't even exist -a clean install of this branch, which never had
     * `SecureDeviceStore`-, opening it isn't attempted either: see [archivoStoreDeCuentasViejoExiste].
     */
    fun migrarDelStoreDeCuentasViejo(context: Context) {
        if (leerNullable(KEY_ADULTOS_DESBLOQUEADO) != null && leerNullable(KEY_RECIENTES_PURGADOS) != null) return
        val app = context.applicationContext
        val viejas = if (archivoStoreDeCuentasViejoExiste(app)) {
            runCatching { abrirStoreDeCuentasViejo(app) }.getOrNull()
        } else {
            null
        }
        // Same text keys as the old file (see the comment next to these constants, further
        // below): `SecureDeviceStore` wrote them verbatim.
        migrarAdultosDesbloqueado(viejas.leerBooleanoViejo(KEY_ADULTOS_DESBLOQUEADO))
        migrarRecientesPurgados(viejas.leerBooleanoViejo(KEY_RECIENTES_PURGADOS))
        if (viejas != null) {
            // Both keys that matter are already migrated above: deleting the old file removes the
            // Kino account's email and password that were still living there, from a subsystem
            // that no longer exists. Goes AFTER migrating, never before. If the file was
            // undecryptable, `discardUndecryptable` (see [abrirStoreDeCuentasViejo]) already
            // deleted it and `EncryptedPrefs` retried: `viejas` ends up pointing at a freshly
            // created, empty file with nothing to migrate from, and this delete removes it again.
            // It's a redundant delete with no consequence -- the end state is the same. This only
            // touches the shared_prefs file -- NEVER the Keystore's master key, which is the SAME
            // one `EncryptedMagisCredentialStore` uses for the Magis session.
            runCatching { app.deleteSharedPreferences(ARCHIVO_STORE_DE_CUENTAS_VIEJO) }
        }
    }

    private fun SharedPreferences?.leerBooleanoViejo(key: String): Boolean? =
        this?.let { if (it.contains(key)) it.getBoolean(key, false) else null }

    companion object {
        const val PREFS_NAME = "arkiv_settings"
        private const val KEY_DIM_LEVEL = "dim_level"
        private const val KEY_ARTWORK_REMATCH = "artwork_rematch_done"
        private const val KEY_MAGIS_OFERTA_DESCARTADA = "magis_oferta_descartada"

        // Task 7: same text keys `SecureDeviceStore` used (`K_ADULTOS`, `K_PURGA_RECIENTES`) for
        // the name, even though the value lives in a different prefs file -- this way the code's
        // history stays searchable by that name. Task 9: [migrarDelStoreDeCuentasViejo] reads
        // those same two keys from the original file.
        private const val KEY_ADULTOS_DESBLOQUEADO = "adultosDesbloqueado"
        private const val KEY_CODIGO_ADULTOS = "codigoAdultos"
        private const val KEY_RECIENTES_PURGADOS = "recientesPurgados2026_08_14"

        private const val KEY_PARA_TI_ULTIMO_INTENTO = "para_ti_ultimo_intento"
        private const val KEY_PARA_TI_FALLO_MODELO = "para_ti_fallo_modelo"

        /** The encrypted file `SecureDeviceStore` used to write (deleted in Task 9). */
        private const val ARCHIVO_STORE_DE_CUENTAS_VIEJO = "arkiv_pb_secure"

        /**
         * `true` if the file exists on disk. Checking this BEFORE [abrirStoreDeCuentasViejo] is
         * the difference between reading something and CREATING it: `EncryptedSharedPreferences.create`
         * writes the Tink keyset the first time, so without this check a clean install -which
         * never had `SecureDeviceStore`- would end up generating `arkiv_pb_secure` and touching
         * the Keystore on `Application.onCreate`'s main thread, to rescue a file that never existed.
         */
        private fun archivoStoreDeCuentasViejoExiste(app: Context): Boolean =
            java.io.File(app.dataDir, "shared_prefs/$ARCHIVO_STORE_DE_CUENTAS_VIEJO.xml").exists()

        /**
         * Opens `arkiv_pb_secure` with the same scheme `SecureDeviceStore.cifradas()` used to
         * write it, for [migrarDelStoreDeCuentasViejo]. Read-only: nothing is ever written back to
         * it here, so if the Keystore can't decrypt it there's nothing to repair -- deleting the
         * file is enough (NEVER the master key: it's the SAME one
         * `EncryptedMagisCredentialStore` uses for `arkiv_magis_secure`, `MasterKey.Builder(app)`
         * with no alias of its own, so touching it in passing would break the Magis session for no
         * reason) and letting the second attempt open an empty file -- which for a migration is
         * exactly "there was nothing to migrate".
         */
        private fun abrirStoreDeCuentasViejo(app: Context): SharedPreferences? =
            EncryptedPrefs.openOrRepair<SharedPreferences?>(
                create = {
                    EncryptedSharedPreferences.create(
                        app,
                        ARCHIVO_STORE_DE_CUENTAS_VIEJO,
                        MasterKey.Builder(app).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
                        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
                    )
                },
                discardUndecryptable = {
                    Log.w(TAG_MIGRACION, "old accounts store undecryptable: abandoning without migrating")
                    runCatching { app.deleteSharedPreferences(ARCHIVO_STORE_DE_CUENTAS_VIEJO) }
                },
                unencrypted = { null },
            )

        private const val TAG_MIGRACION = "ArkivMigracion"
        // The mirror's `POST /api/refresh` key no longer lives here: that endpoint moved to being
        // requested through the gateway (`/v1/catalog/refresh`), which is the one that supplies
        // the credential. With that, the APK stopped carrying it -- which is what the comment that
        // used to be in this spot said: removing it from git didn't remove it from the binary,
        // and a secret embedded in a distributed client isn't a secret. See `MirrorApiClient.refresh`.
        //
        // Task 8 (Step 3): `DEFAULT_ARKIV_API_KEY`/`ARKIV_API_KEY` (the last remaining build key)
        // left entirely for the same reason -- see `docs/INVENTARIO_DE_LLAVES.md`.
        //
        // Sub-project 2A: `KEY_GATEWAY_CONFIG_SOURCE` (where the gateway's config came from: read
        // by live's error message, which now asks about the Magis account) and `KEY_USE_GATEWAY`
        // (the flag to "fall back to the old path", which no longer exists) are both gone.
    }
}

/**
 * What value is left after moving a preference from the device's encrypted store to these
 * settings. Whatever's already here WINS: if the person changed the value after migrating, the
 * old one can't come back to life on the next launch.
 */
internal fun valorMigrado(deSettings: Boolean?, deStoreViejo: Boolean?, default: Boolean): Boolean =
    deSettings ?: deStoreViejo ?: default
