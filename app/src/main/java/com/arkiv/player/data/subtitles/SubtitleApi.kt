package com.arkiv.player.data.subtitles

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/** Un subtítulo encontrado (antes de descargarlo). */
data class SubtitleTrack(
    val fileId: Long,
    val language: String,   // "es", "en", …
    val label: String,      // etiqueta legible para el selector
    val release: String,
    val hashMatch: Boolean = false, // true si OpenSubtitles lo emparejó por moviehash (release exacto)
)

/**
 * Subtítulos vía OpenSubtitles.com (API v1). Busca por imdb id (o título) + idioma y baja el .srt,
 * para verlo con ExoPlayer. Así una fuente en inglés se ve con subtítulos en español. Requiere una
 * La llave de OpenSubtitles vive en el gateway; acá solo viaja la credencial de Arkiv.
 * OpenSubtitles EXIGE User-Agent, así que se manda igual.
 */
class SubtitleApi(
    /** Base del gateway y credencial unica. La llave de OpenSubtitles vive en el servidor. */
    private val gatewayUrl: () -> String,
    private val arkivKey: () -> String,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build(),
) {
    // Passthrough del gateway: la llave de OpenSubtitles vive en el servidor.
    private val base: String get() = "${gatewayUrl()}/v1/catalog/opensubtitles"
    private val ua = "Arkiv v0.1"
    private val jsonType = "application/json".toMediaType()

    val configured: Boolean get() = arkivKey().isNotBlank()

    /**
     * Busca subtítulos. Pasá imdbId (ej "tt0816692") o query (título). Para series, season/episode.
     * languages: códigos separados por coma (ej "es" = español, "es,en").
     */
    suspend fun search(
        imdbId: String? = null,
        query: String? = null,
        season: Int? = null,
        episode: Int? = null,
        languages: String = "es",
        moviehash: String? = null,
    ): List<SubtitleTrack> = withContext(Dispatchers.IO) {
        if (!configured) return@withContext emptyList()
        val params = buildList {
            add("languages=$languages")
            // moviehash = match EXACTO del release por hash del archivo (OSDb). OpenSubtitles marca los
            // resultados con moviehash_match=true; se combinan con imdb/query para no quedarse sin nada.
            moviehash?.takeIf { it.isNotBlank() }?.let { add("moviehash=$it") }
            imdbId?.removePrefix("tt")?.toIntOrNull()?.let { add("imdb_id=$it") }
            if (imdbId.isNullOrBlank() && !query.isNullOrBlank()) add("query=${enc(query)}")
            season?.let { add("season_number=$it") }
            episode?.let { add("episode_number=$it") }
            add("order_by=download_count")
        }
        val url = "$base/subtitles?${params.joinToString("&")}"
        val body = get(url) ?: return@withContext emptyList()
        runCatching {
            val data = JSONObject(body).optJSONArray("data") ?: JSONArray()
            (0 until data.length()).mapNotNull { i ->
                val attr = data.optJSONObject(i)?.optJSONObject("attributes") ?: return@mapNotNull null
                val files = attr.optJSONArray("files") ?: return@mapNotNull null
                val fileId = files.optJSONObject(0)?.optLong("file_id", -1L) ?: -1L
                if (fileId <= 0) return@mapNotNull null
                val lang = attr.optString("language")
                val release = attr.optString("release").ifBlank { attr.optString("feature_details") }
                val hashMatch = attr.optBoolean("moviehash_match", false)
                SubtitleTrack(
                    fileId = fileId,
                    language = lang,
                    label = "${if (hashMatch) "✓ " else ""}${langLabel(lang)}${if (release.isNotBlank()) " · ${release.take(40)}" else ""}",
                    release = release,
                    hashMatch = hashMatch,
                )
            }
        }.getOrDefault(emptyList())
    }

    /**
     * Baja el .srt de un subtítulo y lo guarda localmente. Devuelve el archivo o null.
     * [lang] va en el NOMBRE del archivo (`sub-123.es.srt`) porque libVLC nombra las pistas externas
     * con su ruta: así el clasificador de idioma también reconoce las bajadas de OpenSubtitles, en
     * vez de que sean la única pista opaca del sistema.
     */
    suspend fun download(fileId: Long, dir: File, lang: String = ""): File? = withContext(Dispatchers.IO) {
        if (!configured) return@withContext null
        // 1) pedir el link de descarga.
        val reqBody = JSONObject().put("file_id", fileId).put("sub_format", "srt").toString()
        val dlResp = runCatching {
            client.newCall(
                Request.Builder().url("$base/download")
                    .header("X-Arkiv-Key", arkivKey()).header("User-Agent", ua)
                    .header("Accept", "application/json")
                    .post(reqBody.toRequestBody(jsonType))
                    .build(),
            ).execute().use { if (it.isSuccessful) it.body?.string() else null }
        }.getOrNull() ?: return@withContext null
        val link = runCatching { JSONObject(dlResp).optString("link") }.getOrNull()
            ?.takeIf { it.startsWith("http") } ?: return@withContext null
        // 2) bajar el .srt.
        val bytes = runCatching {
            client.newCall(Request.Builder().url(link).header("User-Agent", ua).build())
                .execute().use { if (it.isSuccessful) it.body?.bytes() else null }
        }.getOrNull() ?: return@withContext null
        runCatching {
            dir.mkdirs()
            val sufijo = lang.lowercase().takeIf { it.isNotBlank() }?.let { ".$it" }.orEmpty()
            File(dir, "sub-$fileId$sufijo.srt").apply { writeBytes(bytes) }
        }.getOrNull()
    }

    private fun get(url: String): String? = runCatching {
        client.newCall(
            Request.Builder().url(url)
                .header("X-Arkiv-Key", arkivKey()).header("User-Agent", ua)
                .header("Accept", "application/json").build(),
        ).execute().use { if (it.isSuccessful) it.body?.string() else null }
    }.getOrNull()

    private fun langLabel(code: String): String = when (code.lowercase()) {
        "es", "spa" -> "Español"
        "es-419", "es-mx" -> "Español latino"
        "en" -> "Inglés"
        "pt-br" -> "Portugués (BR)"
        else -> code
    }

    private fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20")
}
