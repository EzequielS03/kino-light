package com.arkiv.player.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.arkiv.player.data.magis.PrefsCifradas
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Ajustes simples persistidos en SharedPreferences. */
class SettingsStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("arkiv_settings", Context.MODE_PRIVATE)

    // Modo noche del reproductor: nivel del velo negro que va encima del video, de 0 (normal) a
    // DIM_MAX_LEVEL (negro total). Persistido a propósito (no por sesión): quien lo baja ve casi
    // siempre de noche. El nivel lo acota el reproductor; acá se guarda tal cual llega.
    private val _dimLevel = MutableStateFlow(prefs.getInt(KEY_DIM_LEVEL, 0))
    val dimLevel: StateFlow<Int> = _dimLevel

    // ¿Ya se reparó el arte que se resolvió antes del match exacto de TMDB? Ver
    // ArkivRepository.repairArtworkMatches. Se marca SOLO cuando la pasada termina entera, para que
    // un arranque sin internet no la dé por hecha y deje los títulos mal apuntados para siempre.
    private val _artworkRematchDone = MutableStateFlow(prefs.getBoolean(KEY_ARTWORK_REMATCH, false))
    val artworkRematchDone: StateFlow<Boolean> = _artworkRematchDone

    // "Ahora no" a la oferta de vincular Magis apenas se entra a la TV (Task 10, ver
    // `debeOfrecerVincularMagis` en ui/tv/TvOfertaVincularMagis.kt). Es una decisión del DISPOSITIVO,
    // no de la cuenta -mismo criterio que [artworkRematchDone] acá arriba-: este es un TV
    // de uso personal, no un kiosco compartido entre cuentas. Ya NO se resetea en ningún logout
    // -Task 8 (sub-proyecto 2B) sacó los botones de "Cerrar sesión" de las pantallas de Magis, que
    // eran los últimos llamadores de `AccountManager.logout()`, así que esa ruta quedó muerta-: sin
    // cuentas de Kino no hay logout que dispare el reseteo, y la oferta sigue accesible a mano desde
    // Ajustes (`TvSettingsCuenta`) para quien quiera volver a vincular Magis sin depender de este flag.
    private val _magisOfertaDescartada = MutableStateFlow(prefs.getBoolean(KEY_MAGIS_OFERTA_DESCARTADA, false))
    val magisOfertaDescartada: StateFlow<Boolean> = _magisOfertaDescartada

    // Task 7 (sub-proyecto 2B): candado 18+ del APARATO. Vivía en `SecureDeviceStore`, que la
    // Task 9 borra junto con las cuentas -- no es un dato de cuenta, así que se rescata acá antes.
    // Igual criterio que [magisOfertaDescartada]: por device, no por persona.
    private val _adultosDesbloqueado = MutableStateFlow(prefs.getBoolean(KEY_ADULTOS_DESBLOQUEADO, false))
    val adultosDesbloqueado: StateFlow<Boolean> = _adultosDesbloqueado

    // Marcador de la purga única de recientes del 2026-08-14 (ver `ArkivApp.onCreate`). Mismo
    // rescate que [adultosDesbloqueado]: si se pierde, la purga simplemente vuelve a correr una
    // vez más -- no hace falta un StateFlow porque nada la observa, solo se lee al arrancar.
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

    fun setRecientesPurgados(v: Boolean) {
        prefs.edit().putBoolean(KEY_RECIENTES_PURGADOS, v).apply()
    }

    /**
     * Trae el candado 18+ del store cifrado del aparato la primera vez que corre. `deStoreViejo`
     * es `null` cuando ese store no se pudo leer (ver `ArkivApp.onCreate`) -- ahí se queda con lo
     * que ya haya acá (o el default). Idempotente: en los arranques siguientes `prefs` ya tiene la
     * clave y `valorMigrado` la respeta sin volver a mirar el store viejo.
     *
     * Escribe directo a `prefs` en vez de pasar por [setAdultosDesbloqueado]: ese setter no
     * escribe si el valor no cambió (para no pisar el StateFlow con un `apply()` de más), pero acá
     * el caso más común es justo ese -- el store viejo nunca se desbloqueó y el resultado coincide
     * con el default en memoria. Si pasara por el guard, la clave nunca quedaría anotada y esta
     * función volvería a mirar `SecureDeviceStore` en cada arranque, que es lo que el comentario de
     * arriba dice que NO pasa.
     */
    fun migrarAdultosDesbloqueado(deStoreViejo: Boolean?) {
        val migrado = valorMigrado(leerNullable(KEY_ADULTOS_DESBLOQUEADO), deStoreViejo, false)
        prefs.edit().putBoolean(KEY_ADULTOS_DESBLOQUEADO, migrado).apply()
        _adultosDesbloqueado.value = migrado
    }

    /** Mismo rescate que [migrarAdultosDesbloqueado] para el marcador de la purga de recientes. */
    fun migrarRecientesPurgados(deStoreViejo: Boolean?) {
        setRecientesPurgados(valorMigrado(leerNullable(KEY_RECIENTES_PURGADOS), deStoreViejo, false))
    }

    /** `null` si `key` todavía no se escribió en estos ajustes -- distinto de que valga `false`. */
    private fun leerNullable(key: String): Boolean? = if (prefs.contains(key)) prefs.getBoolean(key, false) else null

    /**
     * Dispara [migrarAdultosDesbloqueado]/[migrarRecientesPurgados] leyendo el archivo cifrado
     * VIEJO directo (Task 9, sub-proyecto 2B).
     *
     * Ese archivo (`arkiv_pb_secure`) era de `SecureDeviceStore`, que la Task 9 borra junto con el
     * resto de `pocketbase/` -- las dos claves que interesan (`adultosDesbloqueado`,
     * `recientesPurgados2026_08_14`) NO son datos de cuenta, así que se rescatan leyendo el mismo
     * archivo con el mismo esquema (`EncryptedSharedPreferences` + `MasterKey` AES256_GCM +
     * AES256_SIV/AES256_GCM) que usaba esa clase, sin resucitarla. `PrefsCifradas.abrirOReparar`
     * sigue vivo porque lo usa `EncryptedMagisCredentialStore` -- se reusa acá para el mismo
     * problema (Keystore que ya no descifra el archivo).
     *
     * Si ya migraron las dos claves, ni se mira el archivo viejo: `EncryptedSharedPreferences.create`
     * cuesta Keystore + Tink, y esto se llama en CADA arranque. Y si el archivo ni existe -una
     * instalación limpia de esta rama, que nunca tuvo `SecureDeviceStore`-, tampoco se intenta
     * abrir: ver [archivoStoreDeCuentasViejoExiste].
     */
    fun migrarDelStoreDeCuentasViejo(context: Context) {
        if (leerNullable(KEY_ADULTOS_DESBLOQUEADO) != null && leerNullable(KEY_RECIENTES_PURGADOS) != null) return
        val app = context.applicationContext
        val viejas = if (archivoStoreDeCuentasViejoExiste(app)) {
            runCatching { abrirStoreDeCuentasViejo(app) }.getOrNull()
        } else {
            null
        }
        // Mismas keys de texto que el archivo viejo (ver el comentario junto a estas constantes,
        // más abajo): `SecureDeviceStore` las escribía tal cual.
        migrarAdultosDesbloqueado(viejas.leerBooleanoViejo(KEY_ADULTOS_DESBLOQUEADO))
        migrarRecientesPurgados(viejas.leerBooleanoViejo(KEY_RECIENTES_PURGADOS))
        if (viejas != null) {
            // Las dos claves de interés ya quedaron migradas arriba: borrar el archivo viejo saca
            // el email y la contraseña de la cuenta de Kino que seguían viviendo ahí, de un
            // subsistema que ya no existe. Va DESPUÉS de migrar, nunca antes. Si el archivo era
            // indescifrable, `tirarLoIndescifrable` (ver [abrirStoreDeCuentasViejo]) ya lo borró y
            // `PrefsCifradas` reintentó: `viejas` queda apuntando a un archivo recién creado y
            // vacío, del que no hay nada que migrar, y este borrado lo saca de nuevo. Es un borrado
            // de más sin consecuencia -- el estado final es el mismo. Esto solo toca el archivo de
            // shared_prefs -- JAMÁS la llave maestra del Keystore, que es la MISMA que usa
            // `EncryptedMagisCredentialStore` para la sesión de Magis.
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

        // Task 7: mismas keys de texto que usaba `SecureDeviceStore` (`K_ADULTOS`,
        // `K_PURGA_RECIENTES`) para el nombre, aunque el valor viva en otro archivo de prefs --
        // así el histórico del código sigue siendo buscable por ese nombre. Task 9:
        // [migrarDelStoreDeCuentasViejo] lee esas mismas dos keys del archivo original.
        private const val KEY_ADULTOS_DESBLOQUEADO = "adultosDesbloqueado"
        private const val KEY_RECIENTES_PURGADOS = "recientesPurgados2026_08_14"

        /** El archivo cifrado que escribía `SecureDeviceStore` (borrado en la Task 9). */
        private const val ARCHIVO_STORE_DE_CUENTAS_VIEJO = "arkiv_pb_secure"

        /**
         * `true` si el archivo existe en disco. Chequearlo ANTES de [abrirStoreDeCuentasViejo] es
         * la diferencia entre leer algo y CREARLO: `EncryptedSharedPreferences.create` escribe el
         * keyset de Tink la primera vez, así que sin este chequeo una instalación limpia -que nunca
         * tuvo `SecureDeviceStore`- terminaría generando `arkiv_pb_secure` y tocando el Keystore en
         * el hilo principal de `Application.onCreate`, para rescatar un archivo que nunca existió.
         */
        private fun archivoStoreDeCuentasViejoExiste(app: Context): Boolean =
            java.io.File(app.dataDir, "shared_prefs/$ARCHIVO_STORE_DE_CUENTAS_VIEJO.xml").exists()

        /**
         * Abre `arkiv_pb_secure` con el mismo esquema con el que `SecureDeviceStore.cifradas()` lo
         * escribía, para [migrarDelStoreDeCuentasViejo]. Solo lectura: acá nunca se le vuelve a
         * escribir nada, así que si el Keystore no lo descifra no hace falta reparar nada -- alcanza
         * con borrar el archivo (JAMÁS la llave maestra: es la MISMA que usa
         * `EncryptedMagisCredentialStore` para `arkiv_magis_secure`, `MasterKey.Builder(app)` sin
         * alias propio, así que tocarla de paso rompería la sesión de Magis sin necesidad) y dejar
         * que el segundo intento abra un archivo vacío -- que para una migración es exactamente
         * "no había nada que migrar".
         */
        private fun abrirStoreDeCuentasViejo(app: Context): SharedPreferences? =
            PrefsCifradas.abrirOReparar<SharedPreferences?>(
                crear = {
                    EncryptedSharedPreferences.create(
                        app,
                        ARCHIVO_STORE_DE_CUENTAS_VIEJO,
                        MasterKey.Builder(app).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
                        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
                    )
                },
                tirarLoIndescifrable = {
                    Log.w(TAG_MIGRACION, "store de cuentas viejo indescifrable: se abandona sin migrar")
                    runCatching { app.deleteSharedPreferences(ARCHIVO_STORE_DE_CUENTAS_VIEJO) }
                },
                sinCifrar = { null },
            )

        private const val TAG_MIGRACION = "ArkivMigracion"
        // La key del `POST /api/refresh` del mirror ya no existe acá: ese endpoint pasó a pedirse
        // por el gateway (`/v1/catalog/refresh`), que es quien pone la credencial. Con eso el APK
        // dejó de llevarla — que era lo que decía el comentario que estaba en este lugar: sacarla de
        // git no la sacaba del binario, y un secreto embebido en un cliente distribuido no es un
        // secreto. Ver `MirrorApiClient.refresh`.
        //
        // Task 8 (Paso 3): `DEFAULT_ARKIV_API_KEY`/`ARKIV_API_KEY` (la última llave de build que
        // quedaba) salió del todo por el mismo motivo -- ver `docs/INVENTARIO_DE_LLAVES.md`.
        //
        // Sub-proyecto 2A: se fueron `KEY_GATEWAY_CONFIG_SOURCE` (de qué venía la config del
        // gateway: lo leía el mensaje de error del vivo, que ahora pregunta por la cuenta de Magis)
        // y `KEY_USE_GATEWAY` (el flag para "caer al camino viejo", que ya no existe).
    }
}

/**
 * Qué valor queda tras mudar una preferencia del store cifrado del aparato a estos ajustes.
 * Lo que ya esté acá MANDA: si la persona cambió el valor después de migrar, el viejo no puede
 * resucitar en el próximo arranque.
 */
internal fun valorMigrado(deSettings: Boolean?, deStoreViejo: Boolean?, default: Boolean): Boolean =
    deSettings ?: deStoreViejo ?: default
