package com.arkiv.player.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Calidad preferida para streaming/descarga. */
enum class Quality { ORIGINAL, DERIVATIVE }

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

/**
 * Calidad de las fuentes WEB (HLS adaptativo). AUTO = decide por camino (directo del CDN → sube a HD;
 * proxy por el túnel angosto → 480p para no cortarse). SD/HD/MÁX = fijo, la elección del usuario manda.
 */
enum class WebQuality { AUTO, SD, HD, MAX }

/** Ajustes simples persistidos en SharedPreferences. */
class SettingsStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("arkiv_settings", Context.MODE_PRIVATE)

    private val _streamQuality = MutableStateFlow(readQuality(KEY_STREAM, Quality.ORIGINAL))
    val streamQuality: StateFlow<Quality> = _streamQuality

    private val _downloadQuality = MutableStateFlow(readQuality(KEY_DOWNLOAD, Quality.DERIVATIVE))
    val downloadQuality: StateFlow<Quality> = _downloadQuality

    // Tamaño máximo de torrent (GB) a mostrar en la búsqueda; 0 = sin límite. Los packs de
    // temporada quedan exentos (de un pack solo se streamea un episodio). Default 21 GB: deja
    // pasar todo el 1080p (incluso bitrate alto) y algún 4K liviano; corta los remux gigantes.
    private val _maxTorrentSizeGb = MutableStateFlow(prefs.getInt(KEY_MAX_SIZE, 21))
    val maxTorrentSizeGb: StateFlow<Int> = _maxTorrentSizeGb

    private val _providersUrl = MutableStateFlow(prefs.getString(KEY_PROVIDERS_URL, DEFAULT_PROVIDERS_URL)!!)
    val providersUrl: StateFlow<String> = _providersUrl

    private val _webSourcesUrl = MutableStateFlow(prefs.getString(KEY_WEB_SOURCES_URL, DEFAULT_WEB_SOURCES_URL)!!)
    val webSourcesUrl: StateFlow<String> = _webSourcesUrl

    private val _webResolverUrl = MutableStateFlow(prefs.getString(KEY_WEB_RESOLVER_URL, DEFAULT_WEB_RESOLVER_URL)!!)
    val webResolverUrl: StateFlow<String> = _webResolverUrl

    private val _torrentApiUrl = MutableStateFlow(prefs.getString(KEY_TORRENT_API_URL, DEFAULT_TORRENT_API_URL)!!)
    val torrentApiUrl: StateFlow<String> = _torrentApiUrl

    private val _nucLanBaseUrl = MutableStateFlow(prefs.getString(KEY_NUC_LAN_URL, DEFAULT_NUC_LAN_URL)!!)
    val nucLanBaseUrl: StateFlow<String> = _nucLanBaseUrl

    private val _nucTunnelBaseUrl = MutableStateFlow(prefs.getString(KEY_NUC_TUNNEL_URL, DEFAULT_NUC_TUNNEL_URL)!!)
    val nucTunnelBaseUrl: StateFlow<String> = _nucTunnelBaseUrl

    private val _nucApiKey = MutableStateFlow(prefs.getString(KEY_NUC_API_KEY, "")!!)
    val nucApiKey: StateFlow<String> = _nucApiKey

    private val _cloudflareSolverEnabled = MutableStateFlow(prefs.getBoolean(KEY_CF_ENABLED, true))
    val cloudflareSolverEnabled: StateFlow<Boolean> = _cloudflareSolverEnabled

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

    private val _webQuality = MutableStateFlow(readWebQuality())
    val webQuality: StateFlow<WebQuality> = _webQuality

    // ¿Este teléfono pareó una TV alguna vez? Persistido porque el descubrimiento LAN es anónimo
    // (cualquier Arkiv de la red responde) y no sirve para decidir si mostrar el "enviar a la TV".
    // Ver tvTargetAvailable().
    private val _tvLinked = MutableStateFlow(prefs.getBoolean(KEY_TV_LINKED, false))
    val tvLinked: StateFlow<Boolean> = _tvLinked

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
    // no de la cuenta -mismo criterio que [tvLinked]/[artworkRematchDone] acá arriba-: este es un TV
    // de uso personal, no un kiosco compartido entre cuentas. Se resetea en `AccountManager.logout()`
    // (ver `onLocalWipe` en AppGraph): la sesión que se está yendo ya no importa, y si otra persona
    // entra después en este mismo aparato tiene sentido que la oferta le aparezca de nuevo.
    private val _magisOfertaDescartada = MutableStateFlow(prefs.getBoolean(KEY_MAGIS_OFERTA_DESCARTADA, false))
    val magisOfertaDescartada: StateFlow<Boolean> = _magisOfertaDescartada

    fun setStreamQuality(q: Quality) {
        prefs.edit().putString(KEY_STREAM, q.name).apply()
        _streamQuality.value = q
    }

    fun setDownloadQuality(q: Quality) {
        prefs.edit().putString(KEY_DOWNLOAD, q.name).apply()
        _downloadQuality.value = q
    }

    fun setMaxTorrentSizeGb(gb: Int) {
        prefs.edit().putInt(KEY_MAX_SIZE, gb).apply()
        _maxTorrentSizeGb.value = gb
    }

    fun setDimLevel(v: Int) { prefs.edit().putInt(KEY_DIM_LEVEL, v).apply(); _dimLevel.value = v }

    // Fija la config a mano en ESTE dispositivo (p.ej. un ajuste de debug): marca la fuente como
    // MANUAL para que el pareo nunca la pise en silencio (ver [applySyncedGatewayConfig]).
    fun setGatewayUrl(v: String) { prefs.edit().putString(KEY_GATEWAY_URL, v).apply(); _gatewayUrl.value = v; marcarGatewayManual() }
    fun setUseGateway(v: Boolean) { prefs.edit().putBoolean(KEY_USE_GATEWAY, v).apply(); _useGateway.value = v }

    private fun marcarGatewayManual() {
        prefs.edit().putString(KEY_GATEWAY_CONFIG_SOURCE, GatewayConfigSource.MANUAL.name).apply()
        _gatewayConfigSource.value = GatewayConfigSource.MANUAL
    }

    /**
     * Aplica una config de gateway que llegó por el vínculo de cuenta (pareo TV↔celu, ver
     * `PairingManager.aplicarRespuesta`): el TV adopta la URL EFECTIVA del celu en ese momento,
     * para no depender de que alguien la tipee a mano en cada aparato -- ese es justo el fallo de
     * diseño que esto resuelve. Task 8 (Paso 3): antes también propagaba `arkivApiKey`; esa llave
     * salió del todo -- la TV ya recibe la sesión de la persona en el mismo pareo (ver
     * `PairingManager.aplicarRespuesta`), así que no hace falta ninguna credencial extra acá.
     *
     * Respeta [GatewayConfigSource.MANUAL] (ver [SettingsStore.shouldApplySyncedGateway] para la
     * regla exacta, extraída aparte porque es pura y así se puede testear sin Context).
     *
     * Devuelve si se aplicó, para que el llamador pueda loguear el RESULTADO.
     */
    fun applySyncedGatewayConfig(gatewayUrl: String): Boolean {
        if (!shouldApplySyncedGateway(_gatewayConfigSource.value, gatewayUrl)) return false
        prefs.edit()
            .putString(KEY_GATEWAY_URL, gatewayUrl)
            .putString(KEY_GATEWAY_CONFIG_SOURCE, GatewayConfigSource.SYNCED.name)
            .apply()
        _gatewayUrl.value = gatewayUrl
        _gatewayConfigSource.value = GatewayConfigSource.SYNCED
        return true
    }

    fun setProvidersUrl(v: String) { prefs.edit().putString(KEY_PROVIDERS_URL, v).apply(); _providersUrl.value = v }
    fun setWebSourcesUrl(v: String) { prefs.edit().putString(KEY_WEB_SOURCES_URL, v).apply(); _webSourcesUrl.value = v }
    fun setWebResolverUrl(v: String) { prefs.edit().putString(KEY_WEB_RESOLVER_URL, v).apply(); _webResolverUrl.value = v }
    fun setCloudflareSolverEnabled(v: Boolean) { prefs.edit().putBoolean(KEY_CF_ENABLED, v).apply(); _cloudflareSolverEnabled.value = v }
    fun setTorrentApiUrl(v: String) { prefs.edit().putString(KEY_TORRENT_API_URL, v).apply(); _torrentApiUrl.value = v }
    fun setNucLanBaseUrl(v: String) { prefs.edit().putString(KEY_NUC_LAN_URL, v).apply(); _nucLanBaseUrl.value = v }
    fun setNucTunnelBaseUrl(v: String) { prefs.edit().putString(KEY_NUC_TUNNEL_URL, v).apply(); _nucTunnelBaseUrl.value = v }
    fun setNucApiKey(v: String) { prefs.edit().putString(KEY_NUC_API_KEY, v).apply(); _nucApiKey.value = v }

    fun setWebQuality(q: WebQuality) { prefs.edit().putString(KEY_WEB_QUALITY, q.name).apply(); _webQuality.value = q }

    fun setTvLinked(v: Boolean) {
        if (_tvLinked.value == v) return
        prefs.edit().putBoolean(KEY_TV_LINKED, v).apply()
        _tvLinked.value = v
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

    private fun readQuality(key: String, default: Quality): Quality =
        runCatching { Quality.valueOf(prefs.getString(key, default.name)!!) }.getOrDefault(default)

    private fun readWebQuality(): WebQuality =
        runCatching { WebQuality.valueOf(prefs.getString(KEY_WEB_QUALITY, WebQuality.AUTO.name)!!) }.getOrDefault(WebQuality.AUTO)

    private fun readGatewayConfigSource(): GatewayConfigSource =
        runCatching {
            GatewayConfigSource.valueOf(prefs.getString(KEY_GATEWAY_CONFIG_SOURCE, GatewayConfigSource.DEFAULT.name)!!)
        }.getOrDefault(GatewayConfigSource.DEFAULT)

    companion object {
        /**
         * Regla de precedencia para [applySyncedGatewayConfig], pura a propósito -- sin
         * SharedPreferences ni Context de por medio -- para poder testearla en un unit test JVM
         * plano (SettingsStore no se puede instanciar en ese entorno: pide un Context real).
         *
         * NUNCA pisa [GatewayConfigSource.MANUAL]: si el dispositivo tiene una config fijada a
         * mano (p.ej. un TV de pruebas apuntando a un gateway de staging), un pareo no debe
         * pisarla en silencio -- el usuario la puso ahí a propósito.
         *
         * Tampoco aplica una config a medio llenar: una URL en blanco es peor que el default (que
         * al menos apunta al gateway real), así que tiene que venir con contenido para que valga
         * la pena reemplazar lo que ya hay.
         */
        fun shouldApplySyncedGateway(
            currentSource: GatewayConfigSource,
            gatewayUrl: String,
        ): Boolean {
            if (currentSource == GatewayConfigSource.MANUAL) return false
            return gatewayUrl.isNotBlank()
        }

        const val PREFS_NAME = "arkiv_settings"
        const val KEY_WEB_QUALITY = "web_quality"
        private const val KEY_STREAM = "stream_quality"
        private const val KEY_DOWNLOAD = "download_quality"
        private const val KEY_MAX_SIZE = "max_torrent_size_gb"
        private const val KEY_PROVIDERS_URL = "providers_url"
        private const val KEY_WEB_SOURCES_URL = "web_sources_url"
        private const val KEY_WEB_RESOLVER_URL = "web_resolver_url"
        private const val KEY_CF_ENABLED = "cloudflare_solver_enabled"
        private const val KEY_DIM_LEVEL = "dim_level"
        private const val KEY_GATEWAY_URL = "gateway_url"
        private const val KEY_GATEWAY_CONFIG_SOURCE = "gateway_config_source"
        private const val KEY_USE_GATEWAY = "use_gateway"
        private const val KEY_TORRENT_API_URL = "torrent_api_url"
        private const val KEY_TV_LINKED = "tv_linked"
        private const val KEY_ARTWORK_REMATCH = "artwork_rematch_done"
        private const val KEY_LIVE_SIGN_REMOTE = "live_sign_remote"
        private const val KEY_MAGIS_OFERTA_DESCARTADA = "magis_oferta_descartada"
        private const val KEY_NUC_LAN_URL = "nuc_lan_base_url"
        private const val KEY_NUC_TUNNEL_URL = "nuc_tunnel_base_url"
        private const val KEY_NUC_API_KEY = "nuc_api_key"
        const val DEFAULT_PROVIDERS_URL = "https://raw.githubusercontent.com/lordmacu/arkiv-providers/main/providers.json"
        const val DEFAULT_WEB_SOURCES_URL = "https://jackett.comparadorinternet.co/web_sources.json"
        const val DEFAULT_WEB_RESOLVER_URL = "https://jackett.comparadorinternet.co/resolve"
        const val DEFAULT_TORRENT_API_URL = "https://torrents.comparadorinternet.co"
        const val DEFAULT_GATEWAY_URL = "https://api.comparadorinternet.co"
        const val DEFAULT_NUC_LAN_URL = "http://192.168.1.100:8099"
        const val DEFAULT_NUC_TUNNEL_URL = "https://arkiv-offline.comparadorinternet.co"
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
