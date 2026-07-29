package com.arkiv.player.data.catalog.web

import com.arkiv.player.data.catalog.providers.ContentType
import com.arkiv.player.data.catalog.providers.SearchContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/**
 * Backend para canales con API JSON (modelado sobre el read_api de Alfa, p.ej. lamovie /wp-api/v1/...).
 * Descarga el JSON, navega la ruta al array de resultados y extrae cada item por rutas de campo.
 * poster/year suelen quedar vacíos y los rellena WebTmdbMatcher, igual que hace Alfa con TMDB.
 */
object WebJsonBackend {

    suspend fun browse(def: WebSourceDefinition, fetcher: PageFetcher, kind: String, page: Int): List<WebResult> {
        val api = def.api ?: return emptyList()
        val template = api.browse[kind] ?: return emptyList()
        val json = fetchJson(def, fetcher, template.replace("{page}", page.toString())) ?: return emptyList()
        return parse(def, api, json, kind)
    }

    suspend fun search(def: WebSourceDefinition, fetcher: PageFetcher, ctx: SearchContext): List<WebResult> {
        val api = def.api ?: return emptyList()
        val searchPath = api.search ?: return emptyList()
        val q = ctx.titles.firstOrNull { it.isNotBlank() }
        if (q == null) {
            runCatching { android.util.Log.i("ArkivWeb", "web=${def.id} (api): query NULO (títulos vacíos) titles=${ctx.titles}") }
            return emptyList()
        }
        val encoded = URLEncoder.encode(q, "UTF-8").replace("+", "%20")
        val kind = if (ctx.type == ContentType.MOVIE) "movie" else "tv"
        runCatching { android.util.Log.i("ArkivWeb", "web=${def.id} (api) search q=\"$q\" kind=$kind url=${def.baseUrl.trimEnd('/')}${searchPath.replace("{query}", encoded)}") }
        val json = fetchJson(def, fetcher, searchPath.replace("{query}", encoded)) ?: return emptyList()
        val out = parse(def, api, json, kind)
        runCatching { android.util.Log.i("ArkivWeb", "web=${def.id} (api) search q=\"$q\" → ${out.size} resultados") }
        return out
    }

    private suspend fun fetchJson(def: WebSourceDefinition, fetcher: PageFetcher, path: String): JSONObject? {
        for (base in listOf(def.baseUrl) + def.hostAlt) {
            val body = runCatching { fetcher.fetch(base.trimEnd('/') + path, def.charset) }.getOrNull()
            if (!body.isNullOrBlank()) return runCatching { JSONObject(body) }.getOrNull()
        }
        return null
    }

    /** Puro y testeable: convierte el JSON en WebResults según las reglas api de la definición. */
    fun parse(def: WebSourceDefinition, api: WebApiRules, root: JSONObject, kind: String, maxRows: Int = 60): List<WebResult> {
        val arr = jsonPath(root, api.listPath) as? JSONArray ?: return emptyList()
        val out = ArrayList<WebResult>()
        for (i in 0 until arr.length()) {
            if (out.size >= maxRows) break
            val item = arr.optJSONObject(i) ?: continue
            val title = (jsonPath(item, api.title)?.toString() ?: "").trim()
            if (title.isBlank()) continue
            val pageUrl = buildPageUrl(def, api.pageUrl, item)
            if (pageUrl.isBlank()) continue
            val poster = api.poster?.let { jsonPath(item, it)?.toString() }.orEmpty()
            val year = api.year?.let { jsonPath(item, it)?.toString() }
                ?.let { Regex("\\d{4}").find(it)?.value }.orEmpty()
            out.add(
                WebResult(
                    siteId = def.id, siteName = def.name, title = title, year = year,
                    pageUrl = pageUrl, posterUrl = poster,
                    language = normalizeLang(def.languageTokens.firstOrNull()), kind = kind,
                )
            )
        }
        return out
    }

    /** Sustituye {ruta} en la plantilla por el valor del item; si queda relativa, antepone baseUrl. */
    private fun buildPageUrl(def: WebSourceDefinition, template: String, item: JSONObject): String {
        var s = template
        for (m in Regex("\\{([^}]+)\\}").findAll(template)) {
            val v = jsonPath(item, m.groupValues[1])?.toString() ?: ""
            s = s.replace(m.value, v)
        }
        return when {
            s.isBlank() || s.startsWith("http") -> s
            else -> def.baseUrl.trimEnd('/') + "/" + s.trimStart('/')
        }
    }

    /** Navega una ruta con notación de puntos ("data.posts") sobre JSONObjects. */
    fun jsonPath(obj: Any?, path: String): Any? {
        var cur: Any? = obj
        for (seg in path.split(".")) {
            cur = (cur as? JSONObject)?.let { if (it.isNull(seg)) null else it.opt(seg) } ?: return null
        }
        return cur
    }

    private fun normalizeLang(token: String?): String = when {
        token == null -> ""
        token.contains("latino", true) || token == "lat" -> "LAT"
        token.contains("castellano", true) || token == "cast" -> "CAST"
        token.contains("spanish", true) -> "ES"
        else -> token.uppercase()
    }
}
