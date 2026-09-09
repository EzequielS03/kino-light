package com.arkiv.player.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * De dónde salió el `gatewayUrl` que este dispositivo tiene ahora mismo.
 *
 * Existe para resolver la precedencia cuando el vínculo TV↔celu (ver [com.arkiv.player.pairing.PairingManager])
 * intenta propagar la config del celu al TV: [MANUAL] gana siempre (alguien la fijó a propósito
 * en ESTE dispositivo, p.ej. para apuntar a un gateway de pruebas) y nunca se pisa por sync;
 * [DEFAULT] (el default de [DEFAULT_GATEWAY_URL], nunca tocado) SÍ se puede reemplazar; [SYNCED]
 * es lo que dejó el último pareo -- también reemplazable por un pareo posterior, para no quedar
 * pegado a una config vieja para siempre.
 */
enum class GatewayConfigSource { DEFAULT, MANUAL, SYNCED }

/** Ajustes simples persistidos en SharedPreferences. */
class SettingsStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("arkiv_settings", Context.MODE_PRIVATE)

    // Modo noche del reproductor: nivel del velo negro que va encima del video, de 0 (normal) a
    // DIM_MAX_LEVEL (negro total). Persistido a propósito (no por sesión): quien lo baja ve casi
    // siempre de noche. El nivel lo acota el reproductor; acá se guarda tal cual llega.
    private val _dimLevel = MutableStateFlow(prefs.getInt(KEY_DIM_LEVEL, 0))
    val dimLevel: StateFlow<Int> = _dimLevel

    // --- gateway unificado -------------------------------------------------
    // Task 8 (Paso 3): acá vivía `arkivApiKey`, la credencial única de build para TODO el gateway.
    // Salió del todo -- la app ya se autentica con la sesión de la persona (Authorization +
    // X-Arkiv-Device), así que no queda ningún secreto que persistir ni propagar por pareo.
    private val _gatewayUrl = MutableStateFlow(prefs.getString(KEY_GATEWAY_URL, DEFAULT_GATEWAY_URL)!!)
    val gatewayUrl: StateFlow<String> = _gatewayUrl

    // Ver KDoc de [GatewayConfigSource]. Arranca en DEFAULT: un install nuevo (celu o TV) todavía
    // no tiene ni override manual ni config sincronizada por pareo.
    private val _gatewayConfigSource = MutableStateFlow(readGatewayConfigSource())
    val gatewayConfigSource: StateFlow<GatewayConfigSource> = _gatewayConfigSource

    // Arranca encendido, con caída al camino viejo si el gateway no responde: si el NUC se cae,
    // la búsqueda tiene que seguir funcionando igual.
    private val _useGateway = MutableStateFlow(prefs.getBoolean(KEY_USE_GATEWAY, true))
    val useGateway: StateFlow<Boolean> = _useGateway

    // ¿Ya se reparó el arte que se resolvió antes del match exacto de TMDB? Ver
    // ArkivRepository.repairArtworkMatches. Se marca SOLO cuando la pasada termina entera, para que
    // un arranque sin internet no la dé por hecha y deje los títulos mal apuntados para siempre.
    private val _artworkRematchDone = MutableStateFlow(prefs.getBoolean(KEY_ARTWORK_REMATCH, false))
    val artworkRematchDone: StateFlow<Boolean> = _artworkRematchDone

    // Interruptor manual del respaldo de TV en vivo (Tarea 8, LiveHlsProxy): fuerza
    // FirmaDelGateway en vez de FirmaConRespaldo. Un camino de respaldo que nunca se ejerce se
    // pudre en silencio y falla justo el día que Magis cambia el algoritmo; con esto se puede
    // comprobar en un minuto que el camino del gateway sigue sirviendo, sin esperar a que pase.
    private val _liveSignRemote = MutableStateFlow(prefs.getBoolean(KEY_LIVE_SIGN_REMOTE, false))
    val liveSignRemote: StateFlow<Boolean> = _liveSignRemote

    // "Ahora no" a la oferta de vincular Magis apenas se entra a la TV (Task 10, ver
    // `debeOfrecerVincularMagis` en ui/tv/TvOfertaVincularMagis.kt). Es una decisión del DISPOSITIVO,
    // no de la cuenta -mismo criterio que [artworkRematchDone] acá arriba-: este es un TV
    // de uso personal, no un kiosco compartido entre cuentas. Se resetea en `AccountManager.logout()`
    // (ver `onLocalWipe` en AppGraph): la sesión que se está yendo ya no importa, y si otra persona
    // entra después en este mismo aparato tiene sentido que la oferta le aparezca de nuevo.
    private val _magisOfertaDescartada = MutableStateFlow(prefs.getBoolean(KEY_MAGIS_OFERTA_DESCARTADA, false))
    val magisOfertaDescartada: StateFlow<Boolean> = _magisOfertaDescartada

    fun setDimLevel(v: Int) { prefs.edit().putInt(KEY_DIM_LEVEL, v).apply(); _dimLevel.value = v }

    // Fija la config a mano en ESTE dispositivo (p.ej. un ajuste de debug): marca la fuente como
    // MANUAL.
    fun setGatewayUrl(v: String) { prefs.edit().putString(KEY_GATEWAY_URL, v).apply(); _gatewayUrl.value = v; marcarGatewayManual() }
    fun setUseGateway(v: Boolean) { prefs.edit().putBoolean(KEY_USE_GATEWAY, v).apply(); _useGateway.value = v }

    private fun marcarGatewayManual() {
        prefs.edit().putString(KEY_GATEWAY_CONFIG_SOURCE, GatewayConfigSource.MANUAL.name).apply()
        _gatewayConfigSource.value = GatewayConfigSource.MANUAL
    }

    fun setArtworkRematchDone(v: Boolean) {
        if (_artworkRematchDone.value == v) return
        prefs.edit().putBoolean(KEY_ARTWORK_REMATCH, v).apply()
        _artworkRematchDone.value = v
    }

    fun setLiveSignRemote(v: Boolean) {
        prefs.edit().putBoolean(KEY_LIVE_SIGN_REMOTE, v).apply()
        _liveSignRemote.value = v
    }

    fun setMagisOfertaDescartada(v: Boolean) {
        if (_magisOfertaDescartada.value == v) return
        prefs.edit().putBoolean(KEY_MAGIS_OFERTA_DESCARTADA, v).apply()
        _magisOfertaDescartada.value = v
    }

    private fun readGatewayConfigSource(): GatewayConfigSource =
        runCatching {
            GatewayConfigSource.valueOf(prefs.getString(KEY_GATEWAY_CONFIG_SOURCE, GatewayConfigSource.DEFAULT.name)!!)
        }.getOrDefault(GatewayConfigSource.DEFAULT)

    companion object {
        const val PREFS_NAME = "arkiv_settings"
        private const val KEY_DIM_LEVEL = "dim_level"
        private const val KEY_GATEWAY_URL = "gateway_url"
        private const val KEY_GATEWAY_CONFIG_SOURCE = "gateway_config_source"
        private const val KEY_USE_GATEWAY = "use_gateway"
        private const val KEY_ARTWORK_REMATCH = "artwork_rematch_done"
        private const val KEY_LIVE_SIGN_REMOTE = "live_sign_remote"
        private const val KEY_MAGIS_OFERTA_DESCARTADA = "magis_oferta_descartada"
        const val DEFAULT_GATEWAY_URL = "https://api.comparadorinternet.co"
        // La key del `POST /api/refresh` del mirror ya no existe acá: ese endpoint pasó a pedirse
        // por el gateway (`/v1/catalog/refresh`), que es quien pone la credencial. Con eso el APK
        // dejó de llevarla — que era lo que decía el comentario que estaba en este lugar: sacarla de
        // git no la sacaba del binario, y un secreto embebido en un cliente distribuido no es un
        // secreto. Ver `MirrorApiClient.refresh`.
        //
        // Task 8 (Paso 3): `DEFAULT_ARKIV_API_KEY`/`ARKIV_API_KEY` (la última llave de build que
        // quedaba) salió del todo por el mismo motivo -- ver `docs/INVENTARIO_DE_LLAVES.md`.
    }
}
