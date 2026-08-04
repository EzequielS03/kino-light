package com.arkiv.player.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Calidad preferida para streaming/descarga. */
enum class Quality { ORIGINAL, DERIVATIVE }

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

    private val _webQuality = MutableStateFlow(readWebQuality())
    val webQuality: StateFlow<WebQuality> = _webQuality

    // ¿Este teléfono pareó una TV alguna vez? Persistido porque el descubrimiento LAN es anónimo
    // (cualquier Arkiv de la red responde) y no sirve para decidir si mostrar el "enviar a la TV".
    // Ver tvTargetAvailable().
    private val _tvLinked = MutableStateFlow(prefs.getBoolean(KEY_TV_LINKED, false))
    val tvLinked: StateFlow<Boolean> = _tvLinked

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

    private fun readQuality(key: String, default: Quality): Quality =
        runCatching { Quality.valueOf(prefs.getString(key, default.name)!!) }.getOrDefault(default)

    private fun readWebQuality(): WebQuality =
        runCatching { WebQuality.valueOf(prefs.getString(KEY_WEB_QUALITY, WebQuality.AUTO.name)!!) }.getOrDefault(WebQuality.AUTO)

    companion object {
        const val PREFS_NAME = "arkiv_settings"
        const val KEY_WEB_QUALITY = "web_quality"
        private const val KEY_STREAM = "stream_quality"
        private const val KEY_DOWNLOAD = "download_quality"
        private const val KEY_MAX_SIZE = "max_torrent_size_gb"
        private const val KEY_PROVIDERS_URL = "providers_url"
        private const val KEY_WEB_SOURCES_URL = "web_sources_url"
        private const val KEY_WEB_RESOLVER_URL = "web_resolver_url"
        private const val KEY_CF_ENABLED = "cloudflare_solver_enabled"
        private const val KEY_TORRENT_API_URL = "torrent_api_url"
        private const val KEY_TV_LINKED = "tv_linked"
        private const val KEY_NUC_LAN_URL = "nuc_lan_base_url"
        private const val KEY_NUC_TUNNEL_URL = "nuc_tunnel_base_url"
        private const val KEY_NUC_API_KEY = "nuc_api_key"
        const val DEFAULT_PROVIDERS_URL = "https://raw.githubusercontent.com/lordmacu/arkiv-providers/main/providers.json"
        const val DEFAULT_WEB_SOURCES_URL = "https://jackett.comparadorinternet.co/web_sources.json"
        const val DEFAULT_WEB_RESOLVER_URL = "https://jackett.comparadorinternet.co/resolve"
        const val DEFAULT_TORRENT_API_URL = "https://torrents.comparadorinternet.co"
        const val DEFAULT_NUC_LAN_URL = "http://192.168.1.100:8099"
        const val DEFAULT_NUC_TUNNEL_URL = "https://arkiv-offline.comparadorinternet.co"
    }
}
