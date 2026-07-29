package com.arkiv.player.data.catalog.web

import android.util.Log
import com.arkiv.player.data.catalog.providers.ContentType
import com.arkiv.player.data.catalog.providers.SearchContext
import java.net.URLEncoder

/**
 * Filtra al tipo pedido. Conservador: solo descarta lo que el kindHint detectó AFIRMATIVAMENTE como
 * el otro tipo; lo que no tiene kindHint (o no matchea) hereda el kind del contexto y se conserva.
 */
private fun List<WebResult>.filterByKind(kind: String): List<WebResult> = filter { it.kind == kind }

/** Ejecuta UNA definición web: arma URL (browse o search) → fetch (con hostAlt) → parse → WebResult. */
class WebSourceBackend(
    private val def: WebSourceDefinition,
    private val fetcher: PageFetcher,
) {
    /** Lista el catálogo del sitio para el grid. kind: "movie" | "tv". */
    suspend fun browse(kind: String, page: Int): List<WebResult> {
        if (!def.enabled) return emptyList()
        if (def.api != null) return WebJsonBackend.browse(def, fetcher, kind, page)
        val template = def.browse[kind] ?: return emptyList()
        val path = template.replace("{page}", page.toString())
        val html = fetchWithFallback(path) ?: return emptyList()
        return WebHtmlParser.parse(def, html, kind).filterByKind(kind)
    }

    /** Busca por título para la hoja de fuentes. */
    suspend fun search(ctx: SearchContext): List<WebResult> {
        if (!def.enabled) return emptyList()
        if (def.api != null) return WebJsonBackend.search(def, fetcher, ctx)
        val searchPath = def.search
        if (searchPath == null) {
            runCatching { Log.i("ArkivWeb", "web=${def.id}: SIN plantilla search (no busca) titles=${ctx.titles}") }
            return emptyList()
        }
        val query = buildQuery(ctx)
        if (query == null) {
            runCatching { Log.i("ArkivWeb", "web=${def.id}: query NULO (sin keywords[${ctx.type}] o título vacío) titles=${ctx.titles}") }
            return emptyList()
        }
        val encoded = URLEncoder.encode(query, "UTF-8").replace("+", "%20")
        val path = searchPath.replace("{query}", encoded)
        val kind = if (ctx.type == ContentType.MOVIE) "movie" else "tv"
        runCatching { Log.i("ArkivWeb", "web=${def.id} search q=\"$query\" kind=$kind url=${def.baseUrl.trimEnd('/')}$path") }
        val html = fetchWithFallback(path) ?: return emptyList()
        val raw = WebHtmlParser.parse(def, html, kind)
        val filtered = raw.filterByKind(kind)
        runCatching { Log.i("ArkivWeb", "web=${def.id} search q=\"$query\" → raw=${raw.size} kind[$kind]=${filtered.size}${if (raw.size != filtered.size) " (descartados por kind: ${raw.map { it.kind }.groupingBy { it }.eachCount()})" else ""}") }
        return filtered
    }

    /** Arma el query desde la plantilla keywords del sitio. */
    private fun buildQuery(ctx: SearchContext): String? {
        val template = when (ctx.type) {
            ContentType.MOVIE -> def.keywords["movie"]
            ContentType.TV, ContentType.ANIME -> def.keywords["tv"] ?: def.keywords["movie"]
        } ?: return null
        val title = ctx.titles.firstOrNull { it.isNotBlank() } ?: return null
        return template.replace("{title}", title).replace("{year}", ctx.year.take(4))
            .replace(Regex("\\s+"), " ").trim().ifBlank { null }
    }

    /** Prueba baseUrl y, si falla, cada hostAlt en orden. Devuelve el primer HTML no nulo. */
    private suspend fun fetchWithFallback(path: String): String? {
        for (base in listOf(def.baseUrl) + def.hostAlt) {
            val url = base.trimEnd('/') + path
            val html = runCatching { fetcher.fetch(url, def.charset) }.getOrNull()
            if (!html.isNullOrBlank()) return html
        }
        runCatching { Log.w("ArkivWeb", "web=${def.id}: sin respuesta en baseUrl ni hostAlt para $path") }
        return null
    }
}
