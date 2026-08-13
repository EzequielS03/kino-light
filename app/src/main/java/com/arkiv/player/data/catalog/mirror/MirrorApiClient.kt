package com.arkiv.player.data.catalog.mirror

import android.util.Log
import com.arkiv.player.data.ArchiveSearchResult
import com.arkiv.player.data.catalog.providers.ContentType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** Un torrent tal como lo devuelve el backend Mirror API (campos ya normalizados). */
data class MirrorTorrent(
    val magnet: String?,
    val infohash: String?,
    val season: Int?,
    val episode: Int?,
    val episodeEnd: Int?,
    val isPack: Boolean,
    val langNorm: String?,
    val langRaw: String?,
    val quality: String?,
    val seeders: Int?,
    val sizeBytes: Long,
    val sizeLabel: String?,
    val source: String?,
    val name: String?,
)

/** Resultado de POST /api/refresh (botón "Procesar ahora"). */
data class RefreshResult(
    val ok: Boolean,
    val created: Boolean,
    val webSourcesAdded: Int,
    val torrentsAdded: Int,
    val error: String?,
)

/**
 * Cliente del backend Arkiv Mirror API. Resuelve el `slug` de un título (por tmdb_id, con fallback
 * a texto) y trae sus torrents. Caché en memoria de torrents por slug (TTL configurable).
 */
class MirrorApiClient(
    private val baseUrl: () -> String,
    /**
     * A dónde va `refresh`: al GATEWAY, no al mirror. Es la única llamada de esta clase que exigía
     * una credencial propia (`X-Api-Key` del mirror) y por eso esa llave viajaba dentro del APK.
     * Ahora la pone el gateway y la app solo usa la suya. Ver [refresh].
     */
    private val gatewayUrl: () -> String = { "" },
    private val arkivApiKey: () -> String = { "" },
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build(),
    private val ttlMs: Long = 30 * 60 * 1000L,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
    /**
     * Task 8 (Paso 2): token de sesión de la PERSONA, misma fuente que ya usa `CuentaApi` para
     * `Authorization` (`SesionDePersona.token()`). Solo lo usa [refresh] -- es la única llamada de
     * esta clase que habla con el GATEWAY (ver su KDoc); `resolveSlug`/`titleTorrents`/etc. le
     * hablan al MIRROR, otro host, donde esta cabecera no significa nada.
     */
    private val personToken: () -> String? = { null },
    /** Token del APARATO que llama, misma fuente que ya usa `CuentaApi` para `X-Arkiv-Device`
     *  (`DeviceAuthManager.session.value?.token`). Igual que [personToken], solo aplica a [refresh]. */
    private val deviceToken: () -> String? = { null },
) {
    private data class Cached(val torrents: List<MirrorTorrent>, val atMs: Long)
    private val torrentCache = ConcurrentHashMap<String, Cached>()

    private fun kindParam(kind: ContentType): String = when (kind) {
        ContentType.MOVIE -> "movie"
        ContentType.TV -> "serie"
        ContentType.ANIME -> "anime"
    }

    private fun getJson(url: String): JSONObject? = runCatching {
        client.newCall(Request.Builder().url(url).build()).execute().use { resp ->
            if (!resp.isSuccessful) {
                Log.w(TAG, "GET $url → HTTP ${resp.code}")
                return null
            }
            resp.body?.string()?.let { JSONObject(it) }
        }
    }.onFailure { Log.w(TAG, "GET $url falló: $it") }.getOrNull()

    private fun firstSlug(url: String): String? {
        val arr = getJson(url)?.optJSONArray("results") ?: return null
        if (arr.length() == 0) return null
        return arr.optJSONObject(0)?.optString("slug")?.takeIf { it.isNotBlank() }
    }

    suspend fun resolveSlug(tmdbId: Int?, kind: ContentType, titleFallback: String?): String? =
        withContext(Dispatchers.IO) {
            val base = baseUrl().trimEnd('/')
            val k = kindParam(kind)
            Log.i(TAG, "resolveSlug tmdbId=$tmdbId kind=$k title='${titleFallback ?: ""}' base=$base")
            if (tmdbId != null) {
                // 1) tmdb_id + kind (preciso).
                firstSlug("$base/api/search?tmdb_id=$tmdbId&kind=$k")?.let {
                    Log.i(TAG, "  → slug='$it' (tmdb_id=$tmdbId kind=$k)")
                    return@withContext it
                }
                // 2) tmdb_id SIN kind. El backend filtra `kind` de forma estricta y su clasificación
                //    (serie vs anime) no siempre coincide con la de la app (p. ej. Naruto es anime en el
                //    backend, pero puede elegirse como card de serie TMDB). El tmdb_id ya identifica el
                //    título, así que sin kind lo recupera igual.
                firstSlug("$base/api/search?tmdb_id=$tmdbId")?.let {
                    Log.i(TAG, "  → slug='$it' (tmdb_id=$tmdbId SIN kind — clasificación distinta al pedido '$k')")
                    return@withContext it
                }
            }
            val q = titleFallback?.trim()?.takeIf { it.isNotBlank() }
            if (q == null) {
                Log.w(TAG, "  → sin slug (tmdb_id=$tmdbId sin match y sin título fallback)")
                return@withContext null
            }
            val enc = URLEncoder.encode(q, "UTF-8").replace("+", "%20")
            // texto: SIEMPRE acotado por kind. Sin kind, un q ambiguo (p. ej. "Naruto") matchearía la
            // primera peli/otro tipo y colaría contenido equivocado. Preferimos "sin resultados" honesto.
            val slug = firstSlug("$base/api/search?q=$enc&kind=$k")
            Log.i(TAG, "  → slug=${slug?.let { "'$it'" } ?: "null"} (por q='$q' kind=$k)")
            slug
        }

    /**
     * Resuelve un slug por texto libre SIN acotar por kind (para la caja de búsqueda, donde no se
     * conoce el tipo). Devuelve el primer match del backend, sea peli/serie/anime.
     */
    suspend fun resolveSlugByText(query: String): String? = withContext(Dispatchers.IO) {
        val q = query.trim().takeIf { it.isNotBlank() } ?: return@withContext null
        val base = baseUrl().trimEnd('/')
        val enc = URLEncoder.encode(q, "UTF-8").replace("+", "%20")
        val slug = firstSlug("$base/api/search?q=$enc")
        Log.i(TAG, "resolveSlugByText q='$q' → slug=${slug?.let { "'$it'" } ?: "null"}")
        slug
    }

    suspend fun titleTorrents(slug: String): List<MirrorTorrent> = withContext(Dispatchers.IO) {
        torrentCache[slug]?.takeIf { nowMs() - it.atMs < ttlMs }?.let {
            Log.i(TAG, "titleTorrents slug='$slug' → ${it.torrents.size} (caché)")
            return@withContext it.torrents
        }
        val base = baseUrl().trimEnd('/')
        val root = getJson("$base/api/title/$slug")
        if (root == null) {
            Log.w(TAG, "titleTorrents slug='$slug' → respuesta vacía/errónea")
            return@withContext emptyList()
        }
        val out = ArrayList<MirrorTorrent>()
        // Película/serie: array plano "torrents". Anime: el detalle NO trae "torrents"; los reparte en
        // "packs" (packs de temporada/serie) + "seasons[].episodes" (cada episodio es un torrent, misma
        // forma de item). Juntamos de las tres fuentes para cubrir ambas estructuras.
        collectTorrents(root.optJSONArray("torrents"), out)
        collectTorrents(root.optJSONArray("packs"), out)
        root.optJSONArray("seasons")?.let { seasons ->
            for (i in 0 until seasons.length()) {
                collectTorrents(seasons.optJSONObject(i)?.optJSONArray("episodes"), out)
            }
        }
        Log.i(TAG, "titleTorrents slug='$slug' → ${out.size} torrents")
        torrentCache[slug] = Cached(out, nowMs())
        out
    }

    /** Parsea un array de items-torrent (misma forma en torrents/packs/episodes) y los agrega a [out]. */
    private fun collectTorrents(arr: JSONArray?, out: MutableList<MirrorTorrent>) {
        if (arr == null) return
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            // OJO: en Android, org.json.optString() devuelve el string "null" cuando el valor JSON es
            // null (en la JVM devuelve ""), así que hay que guardar con isNull() ANTES de leer el string.
            fun str(key: String): String? = if (o.isNull(key)) null else o.optString(key).takeIf { it.isNotBlank() }
            out += MirrorTorrent(
                magnet = str("magnet"),
                infohash = str("infohash"),
                season = if (o.isNull("season")) null else o.optInt("season"),
                episode = if (o.isNull("episode")) null else o.optInt("episode"),
                episodeEnd = if (o.isNull("episode_end")) null else o.optInt("episode_end"),
                isPack = o.optBoolean("is_pack", false),
                langNorm = str("lang_norm"),
                langRaw = str("lang_raw"),
                quality = str("quality"),
                seeders = if (o.isNull("seeders")) null else o.optInt("seeders"),
                sizeBytes = o.optLong("size_bytes", 0L),
                sizeLabel = str("size_label"),
                source = str("source"),
                name = str("name"),
            )
        }
    }

    /** GET `/api/title/<slug>` y devuelve sus `web_sources[]` (fuentes web, no-torrent). */
    suspend fun titleWebSources(slug: String): List<MirrorWebSource> = withContext(Dispatchers.IO) {
        val base = baseUrl().trimEnd('/')
        val root = getJson("$base/api/title/$slug")
        if (root == null) {
            Log.w(TAG, "titleWebSources slug='$slug' → respuesta vacía/errónea")
            return@withContext emptyList()
        }
        val out = ArrayList<MirrorWebSource>()
        collectWebSources(root.optJSONArray("web_sources"), out)
        Log.i(TAG, "titleWebSources slug='$slug' → ${out.size} web_sources")
        out
    }

    /**
     * Nuestra biblioteca para un `tmdb_id`: los capítulos que subimos nosotros a archive.org.
     *
     * Hace falta un endpoint aparte porque esos ítems se suben con identificador y título
     * hasheados: `title:(<serie>)` en el buscador de archive.org no los encuentra JAMÁS, por más
     * que estén públicos. El mirror los indexa por tmdb_id y devuelve el identificador y los
     * nombres REALES de archive.org — o sea que a partir de acá se reproducen y descargan por el
     * mismo camino que cualquier ítem público, sin que el mirror sirva un solo byte.
     *
     * Devuelve null si no hay nada subido (404) o si falla la red: es una fuente más y no debe
     * tumbar el resto de la búsqueda.
     */
    suspend fun libraryItem(tmdbId: Int): ArchiveSearchResult? = withContext(Dispatchers.IO) {
        val base = baseUrl().trimEnd('/')
        val root = getJson("$base/library/metadata/tmdb-$tmdbId") ?: return@withContext null
        val meta = root.optJSONObject("metadata") ?: return@withContext null
        val identifier = meta.optString("identifier").takeIf { it.isNotBlank() }
            ?: return@withContext null
        val files = root.optJSONArray("files")?.length() ?: 0
        if (files == 0) return@withContext null
        Log.i(TAG, "libraryItem tmdbId=$tmdbId → '$identifier' (${files} archivos)")
        ArchiveSearchResult(
            identifier = identifier,
            // El título viene del mirror: el del ítem en archive.org es el hash.
            title = meta.optString("title").takeIf { it.isNotBlank() } ?: identifier,
            year = "",
            episodeCount = files,
            fromLibrary = true,
            tmdbId = tmdbId,
            overview = meta.optString("description").takeIf { it.isNotBlank() },
        )
    }

    /** Cliente de larga duración SOLO para /api/refresh: el servidor tarda ~60-90s (web+torrents
     * en paralelo), muy por encima del readTimeout de 15s que usa el resto de esta clase para
     * lecturas rápidas -- no se toca el timeout compartido para no enmascarar fallas reales ahí. */
    private val refreshClient by lazy { client.newBuilder().readTimeout(100, TimeUnit.SECONDS).build() }

    /**
     * "Procesar ahora": le pide que busque fuentes web y torrents de un título.
     *
     * Va por el GATEWAY (`/v1/catalog/refresh`) y no directo al mirror. El contrato con el mirror no
     * cambió —el mismo cuerpo, la misma respuesta—, lo que cambió es quién pone su credencial: antes
     * la app, con una `X-Api-Key` que por eso tenía que viajar compilada dentro del APK. Ahora esa
     * llave vive solo en el servidor, igual que ya pasaba con TMDB y OpenSubtitles.
     */
    suspend fun refresh(tmdbId: Int, kind: ContentType, title: String, year: String): RefreshResult =
        withContext(Dispatchers.IO) {
            val base = gatewayUrl().trimEnd('/')
            if (base.isBlank()) return@withContext RefreshResult(false, false, 0, 0, "gateway sin configurar")
            val body = JSONObject().apply {
                put("tmdb_id", tmdbId)
                put("kind", kindParam(kind))
                put("title", title)
                put("year", year.take(4).toIntOrNull())
            }.toString()
            val reqBuilder = Request.Builder()
                .url("$base/v1/catalog/refresh")
                .addHeader("X-Arkiv-Key", arkivApiKey())
                .post(body.toRequestBody("application/json".toMediaType()))
            // Sin sesión/aparato todavía (null o vacío) se omiten las cabeceras -- mandarlas
            // vacías sería peor que no mandarlas (ver ArkivApiClient.pedido).
            personToken()?.takeIf { it.isNotBlank() }?.let { reqBuilder.addHeader("Authorization", it) }
            deviceToken()?.takeIf { it.isNotBlank() }?.let { reqBuilder.addHeader("X-Arkiv-Device", it) }
            val req = reqBuilder.build()
            runCatching {
                refreshClient.newCall(req).execute().use { resp ->
                    val json = resp.body?.string()?.let { runCatching { JSONObject(it) }.getOrNull() }
                    if (json == null) {
                        RefreshResult(false, false, 0, 0, "HTTP ${resp.code}")
                    } else {
                        RefreshResult(
                            ok = json.optBoolean("ok", false),
                            created = json.optBoolean("created", false),
                            webSourcesAdded = json.optInt("web_sources_added", 0),
                            torrentsAdded = json.optInt("torrents_added", 0),
                            error = json.optString("error").takeIf { it.isNotBlank() },
                        )
                    }
                }
            }.getOrElse { RefreshResult(false, false, 0, 0, it.message ?: "error de red") }
        }

    companion object {
        private const val TAG = "ArkivMirror"

        /** Parsea el array top-level `web_sources` de un JSON de detalle (`/api/title/<slug>`). */
        fun parseWebSources(json: String): List<MirrorWebSource> {
            val root = runCatching { JSONObject(json) }.getOrNull() ?: return emptyList()
            val out = ArrayList<MirrorWebSource>()
            collectWebSources(root.optJSONArray("web_sources"), out)
            return out
        }

        private fun collectWebSources(arr: JSONArray?, out: MutableList<MirrorWebSource>) {
            if (arr == null) return
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                // OJO: en Android optString() devuelve el string "null" para JSON null (ver
                // collectTorrents arriba); hay que guardar con isNull() antes de leer, si no cada
                // fila renderiza el literal "null" como título/calidad/idioma. Los campos de
                // MirrorWebSource NO son nullables → caemos a "" / 0.
                fun str(key: String): String = if (o.isNull(key)) "" else o.optString(key)
                out += MirrorWebSource(
                    siteId = str("site_id"),
                    pageUrl = str("page_url"),
                    season = if (o.isNull("season")) 0 else o.optInt("season"),
                    episode = if (o.isNull("episode")) 0 else o.optInt("episode"),
                    name = str("name"),
                    quality = str("quality"),
                    langNorm = str("lang_norm"),
                )
            }
        }
    }
}
