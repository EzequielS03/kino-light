package com.arkiv.player.data.catalog

import android.util.Log
import com.arkiv.player.data.catalog.mirror.MirrorFilter
import com.arkiv.player.data.catalog.mirror.MirrorWebFilter
import com.arkiv.player.data.catalog.providers.ContentType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

private val TRACKERS = listOf(
    "udp://tracker.opentrackr.org:1337/announce",
    "udp://open.tracker.cl:1337/announce",
    "udp://open.demonii.com:1337/announce",
    "udp://tracker.torrent.eu.org:451/announce",
    "udp://exodus.desync.com:6969/announce",
    "udp://tracker.openbittorrent.com:6969/announce",
    "udp://explodie.org:6969/announce",
    "udp://opentracker.i2p.rocks:6969/announce",
)

/**
 * Tope duro de la consulta web al mirror (resolveSlug + titleWebSources son secuenciales y sus
 * timeouts HTTP se apilan). Peor caso ~6 s antes de dejar paso al scraping en vivo.
 */
private const val MIRROR_WEB_TIMEOUT_MS = 6_000L

/** Idioma detectado de un release. */
enum class TorrentLang(val label: String) {
    LATINO("Latino"),
    DUAL("Dual"),
    CASTELLANO("Castellano"),
    JAP_SUB("Jap. sub"),
    OTHER("Otros"),
    ENGLISH("Inglés"),
}

/**
 * Prioridad de idioma para la ruta de ANIME (Latino > Castellano > Dual > Jap > Otros > Inglés).
 * Explícita a propósito: el `ordinal` del enum sirve a la ruta de Cine (Dual arriba de Castellano)
 * y no se reordena para no cambiarla.
 */
fun animeLangPriority(lang: TorrentLang): Int = when (lang) {
    TorrentLang.LATINO -> 0
    TorrentLang.CASTELLANO -> 1
    TorrentLang.DUAL -> 2
    TorrentLang.JAP_SUB -> 3
    TorrentLang.OTHER -> 4
    TorrentLang.ENGLISH -> 5
}

/** Un resultado de búsqueda de torrent, ya clasificado por idioma. */
data class TorrentResult(
    val name: String,
    val seeders: Int,
    val sizeBytes: Long,
    val lang: TorrentLang,
    val infoHash: String? = null,
    val magnetUri: String? = null,
    // Link de descarga del proveedor cuando no hay magnet directo; se resuelve al reproducir.
    val downloadUrl: String? = null,
    /** Ref opaco del gateway, cuando el resultado vino de ahi. Se manda tal cual a `/v1/resolve`
     *  y la app nunca lo interpreta: asi una fuente puede cambiar por dentro sin obligar a un APK. */
    val gatewayRef: String? = null,
) {
    val sizeLabel: String get() = when {
        sizeBytes <= 0 -> ""
        sizeBytes >= 1L shl 30 -> String.format("%.1f GB", sizeBytes / (1L shl 30).toDouble())
        else -> String.format("%.0f MB", sizeBytes / (1L shl 20).toDouble())
    }

    val dedupKey: String get() = infoHash?.lowercase() ?: magnetUri ?: downloadUrl ?: name

    /**
     * Identidad del resultado para las keys de las listas.
     *
     * El `ref` del gateway manda cuando existe: sus resultados llegan sin magnet ni URL (se
     * resuelven al reproducir) y los de Jackett solo-link tampoco traen infohash, así que
     * [dedupKey] caía al NOMBRE — y dos indexers que publican el mismo release hacían crashear
     * la lista de Compose por key duplicada.
     */
    val identity: String get() = gatewayRef ?: dedupKey
}

/**
 * Bucket de "salud" por número de seeds (0 = más sano, 3 = más débil). Ordena ASCENDENTE junto al
 * resto del comparator, para que un release bien seedeado gane a uno casi muerto aunque su calidad
 * sea algo menor (arranque y streaming más fiables desde el origen). Los resultados ya vienen
 * filtrados a seeders>0, así que el bucket 3 es "1-2 seeds", no basura de 0 seeds.
 */
internal fun seedBucket(seeders: Int): Int = when {
    seeders >= 50 -> 0
    seeders >= 10 -> 1
    seeders >= 3 -> 2
    else -> 3
}

/** UA de navegador para pasar el "Bot Fight Mode" de Cloudflare en los links /dl de los proveedores. */
internal const val BROWSER_UA =
    "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36"

/** Fuente resuelta para reproducir: un magnet, o los bytes de un archivo .torrent. */
sealed interface TorrentSource {
    data class Magnet(val uri: String) : TorrentSource
    data class TorrentFile(val bytes: ByteArray) : TorrentSource
}

/**
 * Busca torrents para un capítulo o película específicos, en varios idiomas, delegando en el
 * backend Mirror (catálogo pre-scrapeado en servidor): resuelve el `slug` del título (por tmdb_id,
 * con fallback a texto), trae sus torrents y los filtra/clasifica/ordena localmente.
 */
class TorrentSearchApi(
    private val mirror: com.arkiv.player.data.catalog.mirror.MirrorApiClient,
) {

    // Marca de pack (varios episodios): se exime del corte por tamaño.
    private val PACK_MARKERS = Regex(
        """complete|collection|colecci[oó]n|todas las temporadas|all seasons|""" +
            """season\s*\d|temporada\s*\d|s\d{1,2}\s*-\s*s?\d{1,2}|e\d{1,3}\s*-\s*e?\d{1,3}|""" +
            """\bcap\b.*\d{3}\s+\d{3}|\d{3}\s*-\s*\d{3}""",
    )
    // Formatos pesados y/o difíciles de decodificar por streaming (van al fondo del orden).
    private val HEAVY_MARKERS =
        Regex("""2160p|\b4k\b|\buhd\b|remux|bd[- ]?remux|dolby.?vision|\bdovi\b|\bdv\b|\bhdr\b""")
    // Baja calidad (cams/screeners/SD): también hacia abajo, pero por encima de los pesados.
    private val LOWQ_MARKERS = Regex("""480p|\bsd\b|dvdrip|dvdscr|\bcam\b|hdts|ts screener|\bhdtv\b""")

    /** Pipeline final compartido: MirrorTorrent → TorrentResult mapeado/dedupeado/filtrado por
     *  idioma/ordenado. NO descarta seeders=0: el backend Mirror no reporta seeders fiables. */
    private fun finalizeMirror(
        torrents: List<com.arkiv.player.data.catalog.mirror.MirrorTorrent>,
        langs: Set<TorrentLang>,
        langPriority: (TorrentLang) -> Int,
    ): List<TorrentResult> {
        val mapped = torrents.map { com.arkiv.player.data.catalog.mirror.MirrorMapper.toResult(it) }
        val deduped = mapped.distinctBy { it.dedupKey }
        val afterLang = deduped.filter { langs.isEmpty() || it.lang in langs || it.lang == TorrentLang.OTHER }
        val ranked = afterLang.sortedWith(
            compareBy<TorrentResult> { langPriority(it.lang) }
                .thenBy { seedBucket(it.seeders) }
                .thenBy { qualityRank(it.name, it.sizeBytes) }
                .thenByDescending { it.seeders },
        )
        runCatching {
            Log.i(
                "ArkivMirror",
                "finalizeMirror: post-filtro=${torrents.size} → dedup=${deduped.size} → idioma=${afterLang.size} → FINAL=${ranked.size}" +
                    (if (langs.isEmpty()) " (idiomas=todos)" else " (idiomas=${langs.joinToString(",") { it.label }})"),
            )
        }
        return ranked
    }

    /** Busca una película con TODOS los títulos dados (latino, castellano, original) + año. */
    suspend fun searchMovie(
        titles: List<String>,
        year: String,
        langs: Set<TorrentLang>,
        maxSizeBytes: Long = 0,
        tmdbId: Int? = null,
    ): List<TorrentResult> {
        val ts = titles.map { it.trim() }.filter { it.isNotBlank() }.distinct()
        val slug = mirror.resolveSlug(tmdbId, ContentType.MOVIE, ts.firstOrNull()) ?: return emptyList()
        val sel = MirrorFilter.select(mirror.titleTorrents(slug), ContentType.MOVIE, 0, emptySet(), maxSizeBytes)
        return finalizeMirror(sel, langs) { it.ordinal }
    }

    /**
     * Búsqueda por TEXTO LIBRE (caja de búsqueda), sin asumir tipo: resuelve el slug por `q=` sin kind
     * y trae TODAS las fuentes del título que matchee (peli/serie/anime). Evita el sesgo de forzar
     * kind=movie (que traía la peli equivocada al escribir el nombre de una serie/anime).
     */
    suspend fun searchByText(query: String, langs: Set<TorrentLang>, maxSizeBytes: Long = 0): List<TorrentResult> {
        val slug = mirror.resolveSlugByText(query) ?: return emptyList()
        val sel = MirrorFilter.selectAll(mirror.titleTorrents(slug), maxSizeBytes)
        return finalizeMirror(sel, langs) { it.ordinal }
    }

    /**
     * Busca un capítulo de serie con TODOS los títulos dados (latino, castellano y original —
     * a veces difieren y los releases usan cualquiera), en formato SxxEyy.
     */
    suspend fun searchEpisode(
        titles: List<String>,
        season: Int,
        episode: Int,
        langs: Set<TorrentLang>,
        maxSizeBytes: Long = 0,
        tmdbId: Int? = null,
    ): List<TorrentResult> {
        val ts = titles.map { it.trim() }.filter { it.isNotBlank() }.distinct()
        val slug = mirror.resolveSlug(tmdbId, ContentType.TV, ts.firstOrNull()) ?: return emptyList()
        val sel = MirrorFilter.select(mirror.titleTorrents(slug), ContentType.TV, season, setOf(episode), maxSizeBytes)
        return finalizeMirror(sel, langs) { it.ordinal }
    }

    /**
     * Igual que [searchEpisode] pero contra web_sources del mirror (backend ya crawleo el episodio
     * server-side — sin Cloudflare on-device). Vacio si el mirror no tiene nada para este episodio.
     *
     * Acotado con [withTimeoutOrNull]: son 2-4 llamadas de red SECUENCIALES (resolveSlug +
     * titleWebSources) delante del scraping en vivo; sin tope, un backend lento/caido retrasaria
     * decenas de segundos el fallback que antes arrancaba de inmediato.
     */
    suspend fun searchEpisodeWeb(
        titles: List<String>,
        season: Int,
        episode: Int,
        tmdbId: Int? = null,
    ): List<com.arkiv.player.data.catalog.web.WebResult> = withTimeoutOrNull(MIRROR_WEB_TIMEOUT_MS) {
        val ts = titles.map { it.trim() }.filter { it.isNotBlank() }.distinct()
        val slug = mirror.resolveSlug(tmdbId, ContentType.TV, ts.firstOrNull())
            ?: return@withTimeoutOrNull emptyList()
        val sel = MirrorWebFilter.select(mirror.titleWebSources(slug), ContentType.TV, season, setOf(episode))
        sel.map { com.arkiv.player.data.catalog.mirror.MirrorWebMapper.toWebResult(it) }
    } ?: emptyList()

    /**
     * La serie COMPLETA desde el mirror, agrupada por sitio, para ofrecerla como paquete cuando el
     * usuario busca la serie sin elegir capitulo. Vacio si el titulo aun no esta crawleado.
     */
    suspend fun seriesWebPacks(
        titles: List<String>,
        kind: ContentType,
        tmdbId: Int? = null,
        showTitle: String,
    ): List<com.arkiv.player.data.catalog.mirror.MirrorWebPack> = withTimeoutOrNull(MIRROR_WEB_TIMEOUT_MS) {
        val ts = titles.map { it.trim() }.filter { it.isNotBlank() }.distinct()
        val slug = mirror.resolveSlug(tmdbId, kind, ts.firstOrNull()) ?: return@withTimeoutOrNull emptyList()
        com.arkiv.player.data.catalog.mirror.MirrorWebPack.groupBySite(showTitle, mirror.titleWebSources(slug))
    } ?: emptyList()

    /**
     * Busca las fuentes de un episodio de anime usando la spec del resolver (queries multi-título
     * con numeración absoluta/relativa) y el ranking de idioma propio del anime.
     */
    suspend fun searchAnime(
        spec: SourceQuerySpec,
        langs: Set<TorrentLang>,
        maxSizeBytes: Long = 0,
        tmdbId: Int? = null,
    ): List<TorrentResult> {
        val slug = mirror.resolveSlug(tmdbId, ContentType.ANIME, spec.primaryTitle) ?: return emptyList()
        val sel = MirrorFilter.select(
            mirror.titleTorrents(slug), ContentType.ANIME, spec.seasonForMirror, spec.episodeNumbers, maxSizeBytes,
        )
        return finalizeMirror(sel, langs, ::animeLangPriority)
    }

    /**
     * Igual que [searchAnime] pero contra web_sources del mirror, con la misma spec (numeración
     * absoluta/relativa) que ya arma AnimeSourceProvider para torrents. Acotado con
     * [withTimeoutOrNull] por el mismo motivo que [searchEpisodeWeb].
     */
    suspend fun searchAnimeWeb(
        spec: SourceQuerySpec,
        tmdbId: Int? = null,
    ): List<com.arkiv.player.data.catalog.web.WebResult> = withTimeoutOrNull(MIRROR_WEB_TIMEOUT_MS) {
        val slug = mirror.resolveSlug(tmdbId, ContentType.ANIME, spec.primaryTitle)
            ?: return@withTimeoutOrNull emptyList()
        val sel = MirrorWebFilter.select(
            mirror.titleWebSources(slug), ContentType.ANIME, spec.seasonForMirror, spec.episodeNumbers,
        )
        sel.map { com.arkiv.player.data.catalog.mirror.MirrorWebMapper.toWebResult(it) }
    } ?: emptyList()

    /** Dispara el procesamiento manual de un titulo puntual en el mirror (botón "Procesar ahora"
     * de la UI). Passthrough directo a MirrorApiClient -- MirrorApiClient queda como detalle de
     * implementación detrás de esta clase, mismo criterio que el resto de los métodos acá. */
    suspend fun refreshTitle(tmdbId: Int, kind: ContentType, title: String, year: String) =
        mirror.refresh(tmdbId, kind, title, year)

    /**
     * Browse: TODAS las fuentes de un show (sin filtrar por episodio), para navegar shows en emisión
     * donde no se conoce el nº de episodios. Ranking de idioma anime.
     */
    suspend fun searchAnimeBrowse(
        titles: List<String>,
        langs: Set<TorrentLang>,
        maxSizeBytes: Long = 0,
        tmdbId: Int? = null,
    ): List<TorrentResult> {
        val ts = titles.map { it.trim() }.filter { it.isNotBlank() }.distinct()
        val slug = mirror.resolveSlug(tmdbId, ContentType.ANIME, ts.firstOrNull()) ?: return emptyList()
        val sel = MirrorFilter.selectAll(mirror.titleTorrents(slug), maxSizeBytes)
        return finalizeMirror(sel, langs, ::animeLangPriority)
    }

    /** Igual que [searchMovie] pero como Flow (emite una sola vez, si hay resultados). */
    fun searchMovieFlow(titles: List<String>, year: String, langs: Set<TorrentLang>, maxSizeBytes: Long = 0, tmdbId: Int? = null): Flow<List<TorrentResult>> =
        flow { searchMovie(titles, year, langs, maxSizeBytes, tmdbId).takeIf { it.isNotEmpty() }?.let { emit(it) } }

    /** Igual que [searchEpisode] pero como Flow (emite una sola vez, si hay resultados). */
    fun searchEpisodeFlow(titles: List<String>, season: Int, episode: Int, langs: Set<TorrentLang>, maxSizeBytes: Long = 0, tmdbId: Int? = null): Flow<List<TorrentResult>> =
        flow { searchEpisode(titles, season, episode, langs, maxSizeBytes, tmdbId).takeIf { it.isNotEmpty() }?.let { emit(it) } }

    /** Igual que [searchAnime] pero como Flow (emite una sola vez, si hay resultados). */
    fun searchAnimeFlow(spec: SourceQuerySpec, langs: Set<TorrentLang>, maxSizeBytes: Long = 0, tmdbId: Int? = null): Flow<List<TorrentResult>> =
        flow { searchAnime(spec, langs, maxSizeBytes, tmdbId).takeIf { it.isNotEmpty() }?.let { emit(it) } }

    /** Igual que [searchAnimeBrowse] pero como Flow (emite una sola vez, si hay resultados). */
    fun searchAnimeBrowseFlow(titles: List<String>, langs: Set<TorrentLang>, maxSizeBytes: Long = 0, tmdbId: Int? = null): Flow<List<TorrentResult>> =
        flow { searchAnimeBrowse(titles, langs, maxSizeBytes, tmdbId).takeIf { it.isNotEmpty() }?.let { emit(it) } }

    /**
     * Browse: TODAS las fuentes de una SERIE (sin filtrar por episodio), para navegar shows en
     * emisión donde no se conoce el nº de episodios. A diferencia de [searchAnimeBrowse], resuelve
     * con `kind=serie` (no `kind=anime`) y usa el ranking de idioma ordinal (igual que película/
     * capítulo), no el de anime.
     */
    suspend fun searchSeriesBrowse(
        titles: List<String>, langs: Set<TorrentLang>, maxSizeBytes: Long = 0, tmdbId: Int? = null,
    ): List<TorrentResult> {
        val ts = titles.map { it.trim() }.filter { it.isNotBlank() }.distinct()
        val slug = mirror.resolveSlug(tmdbId, ContentType.TV, ts.firstOrNull()) ?: return emptyList()
        val sel = MirrorFilter.selectAll(mirror.titleTorrents(slug), maxSizeBytes)
        return finalizeMirror(sel, langs) { it.ordinal }
    }

    /** Igual que [searchSeriesBrowse] pero como Flow (emite una sola vez, si hay resultados). */
    fun searchSeriesBrowseFlow(titles: List<String>, langs: Set<TorrentLang>, maxSizeBytes: Long = 0, tmdbId: Int? = null): Flow<List<TorrentResult>> =
        flow { searchSeriesBrowse(titles, langs, maxSizeBytes, tmdbId).takeIf { it.isNotEmpty() }?.let { emit(it) } }

    /** ¿El release es un pack (varios episodios)? Se exime del corte de tamaño porque de un pack
     *  solo se streamea un archivo. */
    private fun isPack(name: String): Boolean = PACK_MARKERS.containsMatchIn(name.lowercase())

    /** Puntaje de "reproducibilidad/streaming" (menor = mejor): prioriza 1080p liviano; penaliza
     *  4K/UHD/REMUX/HDR/Dolby Vision (pesados y/o difíciles de decodificar) y los muy grandes. */
    private fun qualityRank(name: String, sizeBytes: Long): Int {
        val n = name.lowercase()
        var r = 0
        if (HEAVY_MARKERS.containsMatchIn(n)) r += 100
        when {
            n.contains("1080p") -> r += 0
            n.contains("720p") -> r += 4
            LOWQ_MARKERS.containsMatchIn(n) -> r += 12
            else -> r += 2
        }
        // Preferir el punto dulce (~≤8 GB); penalizar suave lo más pesado.
        if (sizeBytes > 8L shl 30) r += 6
        if (sizeBytes > 15L shl 30) r += 20
        return r
    }

    /**
     * Resuelve un resultado a algo reproducible: magnet directo, o siguiendo el link /dl del
     * proveedor (con UA de navegador para pasar Cloudflare). Ese link puede: redirigir a un magnet,
     * redirigir a otra URL de descarga, o servir directamente el archivo .torrent (típico de
     * trackers españoles como Wolfmax/DivxTotal). Devuelve null si no se pudo resolver.
     */
    suspend fun resolveSource(result: TorrentResult): TorrentSource? = withContext(Dispatchers.IO) {
        android.util.Log.i("ArkivDl", "resolve magnet=${result.magnetUri?.take(24)} hash=${result.infoHash?.take(12)} dl=${result.downloadUrl?.take(30)}")
        // Solo un magnetUri VÁLIDO; si no, caemos al infohash o al /dl.
        result.magnetUri?.takeIf { it.startsWith("magnet:") }?.let { return@withContext TorrentSource.Magnet(it) }
        result.infoHash?.takeIf { it.length in 32..40 && it.all { c -> c.isLetterOrDigit() } }
            ?.let { return@withContext TorrentSource.Magnet(buildMagnet(it, result.name)) }
        val url = result.downloadUrl ?: return@withContext null
        resolveDownloadUrl(url, hops = 0)
    }

    private fun resolveDownloadUrl(url: String, hops: Int): TorrentSource? {
        if (hops > 4) return null
        if (url.startsWith("magnet:")) return TorrentSource.Magnet(url)
        if (!url.startsWith("http")) return null
        return runCatching {
            noRedirectClient.newCall(
                Request.Builder().url(url)
                    .header("User-Agent", BROWSER_UA)
                    .header("Accept", "application/x-bittorrent,*/*")
                    .build(),
            ).execute().use { resp ->
                val ct = resp.header("Content-Type").orEmpty()
                when {
                    resp.code in 300..399 -> {
                        val loc = resp.header("Location")
                        android.util.Log.i("ArkivDl", "redirect ${resp.code} -> ${loc?.take(40)}")
                        when {
                            loc == null -> null
                            loc.startsWith("magnet:") -> TorrentSource.Magnet(loc)
                            else -> resolveDownloadUrl(loc, hops + 1) // seguir la redirección
                        }
                    }
                    resp.isSuccessful -> {
                        val bytes = resp.body?.bytes()
                        val isTorrent = bytes != null && bytes.size > 50 &&
                            (ct.contains("bittorrent") || bytes[0] == 'd'.code.toByte())
                        android.util.Log.i("ArkivDl", "200 ct=$ct bytes=${bytes?.size} first=${bytes?.getOrNull(0)} torrent=$isTorrent")
                        if (isTorrent) TorrentSource.TorrentFile(bytes!!) else null
                    }
                    else -> { android.util.Log.w("ArkivDl", "code ${resp.code} ct=$ct"); null }
                }
            }
        }.onFailure { android.util.Log.w("ArkivDl", "fail: $it") }.getOrNull()
    }

    private val noRedirectClient: OkHttpClient by lazy {
        OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
            .connectTimeout(8, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build()
    }

    private fun buildMagnet(hash: String, name: String): String {
        val dn = java.net.URLEncoder.encode(name, "UTF-8")
        val tr = TRACKERS.joinToString("") { "&tr=" + java.net.URLEncoder.encode(it, "UTF-8") }
        return "magnet:?xt=urn:btih:$hash&dn=$dn$tr"
    }
}
