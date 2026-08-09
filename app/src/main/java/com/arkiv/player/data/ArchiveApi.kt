package com.arkiv.player.data

import com.arkiv.player.data.model.ArchiveItem
import com.arkiv.player.data.model.RawFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Un resultado de búsqueda en archive.org (ítem de video).
 *
 * [episodeCount] es el número de episodios/videos dentro del ítem. Es 0 hasta que se
 * enriquece (ver [ArchiveApi.search] con `enrichEpisodes = true`): sirve para distinguir
 * una serie completa (p. ej. 49 eps) de un fragmento de 1 capítulo con el mismo título.
 */
data class ArchiveSearchResult(
    val identifier: String,
    val title: String,
    val year: String,
    val episodeCount: Int = 0,
    /**
     * true si salió de NUESTRA biblioteca (los capítulos que subimos), no del buscador público.
     * No se puede deducir del [identifier]: el ítem se sube a archive.org con un identificador
     * hasheado, indistinguible de cualquier otro. Solo sirve para marcarlo en la UI — a partir
     * de acá se reproduce y se descarga igual que cualquier ítem público.
     */
    val fromLibrary: Boolean = false,
    /** Ref opaco del gateway, cuando el resultado vino de ahi. Se manda tal cual a `/v1/resolve`
     *  y la app nunca lo interpreta: asi una fuente puede cambiar por dentro sin obligar a un APK. */
    val gatewayRef: String? = null,
    /**
     * Serie de TMDB de la que salió este resultado, cuando se sabe. Se guarda junto al ítem para
     * poder pedirle a TMDB los títulos de los capítulos: ni archive.org ni el mirror guardan el
     * nombre del episodio, solo su número.
     */
    val tmdbId: Int? = null,
    /**
     * Sinopsis de la serie. Solo la traen nuestras subidas (la pone el mirror desde TMDB): en
     * archive.org la descripción de esos ítems es el hash con el que se subieron.
     */
    val overview: String? = null,
)

/** Acceso de red a la API pública de metadata de archive.org. */
class ArchiveApi(private val client: OkHttpClient = defaultClient()) {

    /**
     * Busca ítems de video en archive.org por título.
     *
     * Con [enrichEpisodes] = true, además cuenta los episodios de cada ítem (en paralelo) y
     * reordena poniendo primero los que tienen más episodios. Así una serie completa deja de
     * quedar enterrada debajo de fragmentos de 1 capítulo que se llaman igual. Cuesta una
     * consulta de metadata por resultado, así que solo se activa en la búsqueda interactiva.
     */
    suspend fun search(
        query: String,
        rows: Int = 20,
        enrichEpisodes: Boolean = false,
    ): List<ArchiveSearchResult> = withContext(Dispatchers.IO) {
        val q = query.trim()
        if (q.isBlank()) return@withContext emptyList()
        val lucene = "title:($q) AND mediatype:(movies)"
        val url = "https://archive.org/advancedsearch.php?q=" +
            java.net.URLEncoder.encode(lucene, "UTF-8").replace("+", "%20") +
            "&fl[]=identifier&fl[]=title&fl[]=year&rows=$rows&output=json"
        val body = runCatching {
            client.newCall(
                Request.Builder().url(url).header("User-Agent", "Arkiv/0.1 (personal)").build(),
            ).execute().use { if (it.isSuccessful) it.body?.string() else null }
        }.getOrNull() ?: return@withContext emptyList()
        val results = runCatching {
            val docs = JSONObject(body).optJSONObject("response")?.optJSONArray("docs")
                ?: return@runCatching emptyList()
            (0 until docs.length()).mapNotNull { i ->
                val d = docs.optJSONObject(i) ?: return@mapNotNull null
                val id = d.optString("identifier")
                if (id.isBlank()) return@mapNotNull null
                // "title" puede venir como string o array; tomamos el primero.
                val title = when (val t = d.opt("title")) {
                    is org.json.JSONArray -> t.optString(0)
                    else -> t?.toString().orEmpty()
                }.ifBlank { id }
                ArchiveSearchResult(identifier = id, title = title, year = d.optString("year"))
            }
        }.getOrDefault(emptyList())

        if (!enrichEpisodes) return@withContext results
        val enriched = coroutineScope {
            results.map { r -> async { r.copy(episodeCount = episodeCountOf(r.identifier)) } }.awaitAll()
        }
        rankByEpisodeCount(enriched)
    }

    /** Cuenta episodios de un ítem (best-effort). 0 si falla la red o no tiene videos. */
    private suspend fun episodeCountOf(identifier: String): Int =
        runCatching { fetchItem(identifier).episodes.size }.getOrDefault(0)


    /**
     * Descarga y parsea el metadata de un ítem. Lanza [IOException] si la red
     * falla o [ItemNotFoundException] si el ítem no existe o no tiene videos.
     */
    suspend fun fetchItem(identifier: String): ArchiveItem = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(ArchiveUrls.metadata(identifier))
            .header("User-Agent", "Arkiv/0.1 (personal)")
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("archive.org respondió ${response.code}")
            }
            val body = response.body?.string().orEmpty()
            val json = JSONObject(body)
            if (!json.has("metadata")) {
                throw ItemNotFoundException(identifier)
            }
            val meta = json.getJSONObject("metadata")
            val title = meta.optString("title", identifier)
            val description = meta.optString("description").ifBlank { null }
            val files = parseFiles(json)

            val item = MetadataParser.parse(
                identifier = identifier,
                title = title,
                description = description?.let { stripHtml(it) },
                thumbnailUrl = ArchiveUrls.thumbnail(identifier),
                files = files,
            )
            if (item.episodes.isEmpty()) {
                throw ItemNotFoundException(identifier, "El ítem no tiene videos reproducibles")
            }
            item
        }
    }

    private fun parseFiles(json: JSONObject): List<RawFile> {
        val array = json.optJSONArray("files") ?: return emptyList()
        val out = ArrayList<RawFile>(array.length())
        for (i in 0 until array.length()) {
            val f = array.optJSONObject(i) ?: continue
            out.add(
                RawFile(
                    name = f.optString("name"),
                    source = f.optString("source", "original"),
                    format = f.optString("format"),
                    original = f.optString("original").ifBlank { null },
                    sizeBytes = f.optString("size").toLongOrNull() ?: 0L,
                    lengthSeconds = f.optString("length").toDoubleOrNull() ?: 0.0,
                )
            )
        }
        return out
    }

    private fun stripHtml(s: String): String =
        s.replace(Regex("<[^>]*>"), "").replace("&nbsp;", " ").trim()

    companion object {
        /**
         * Reordena resultados poniendo primero los de más episodios (serie completa antes que
         * fragmentos). Orden estable: ante empate conserva el orden de relevancia de archive.org.
         */
        fun rankByEpisodeCount(results: List<ArchiveSearchResult>): List<ArchiveSearchResult> =
            results.sortedByDescending { it.episodeCount }

        private fun defaultClient() = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }
}

class ItemNotFoundException(
    val identifier: String,
    message: String = "No se encontró el ítem \"$identifier\"",
) : Exception(message)
