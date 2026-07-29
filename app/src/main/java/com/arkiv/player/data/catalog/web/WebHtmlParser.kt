package com.arkiv.player.data.catalog.web

import android.util.Log
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

/** Parsea el HTML de un listado de una web a [WebResult]. Soporta rowSelector (CSS) y rowRegex. */
object WebHtmlParser {

    fun parse(def: WebSourceDefinition, html: String, kind: String, maxRows: Int = 60): List<WebResult> {
        val p = def.parser
        return when {
            p.rowSelector != null -> parseCss(def, html, kind, maxRows)
            p.rowRegex != null -> parseRegex(def, html, kind, maxRows)
            else -> emptyList()
        }
    }

    private fun parseCss(def: WebSourceDefinition, html: String, kind: String, maxRows: Int): List<WebResult> {
        val doc = runCatching { Jsoup.parse(html, def.baseUrl) }.getOrNull() ?: return emptyList()
        val rows = doc.select(def.parser.rowSelector!!)
        if (rows.isEmpty()) {
            runCatching { Log.w("ArkivWeb", "web=${def.id} rowSelector matcheó 0 filas (¿cambió el sitio?)") }
            return emptyList()
        }
        val p = def.parser
        return rows.take(maxRows).mapNotNull { row ->
            val title = field(row, p.title, def.baseUrl)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val pageUrl = field(row, p.pageUrl, def.baseUrl)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            toResult(def, kind, title, pageUrl,
                poster = field(row, p.poster, def.baseUrl).orEmpty(),
                year = field(row, p.year, def.baseUrl).orEmpty(),
                quality = field(row, p.quality, def.baseUrl).orEmpty(),
                kindHint = field(row, p.kindHint, def.baseUrl))
        }
    }

    private fun parseRegex(def: WebSourceDefinition, html: String, kind: String, maxRows: Int): List<WebResult> {
        val fields = def.parser.rowRegexFields
        val rx = runCatching { Regex(def.parser.rowRegex!!, RegexOption.DOT_MATCHES_ALL) }.getOrNull() ?: return emptyList()
        val out = ArrayList<WebResult>()
        for (m in rx.findAll(html)) {
            if (out.size >= maxRows) break
            val g = m.groupValues // g[0] = match completo; g[1..] = grupos
            fun byName(name: String): String {
                val idx = fields.indexOf(name)
                return if (idx >= 0 && idx + 1 < g.size) g[idx + 1].trim() else ""
            }
            val title = byName("title")
            if (title.isBlank()) continue
            val pageUrl = resolveAbs(byName("pageUrl"), def.baseUrl)
            if (pageUrl.isBlank()) continue
            out.add(toResult(def, kind, title, pageUrl,
                poster = resolveAbs(byName("poster"), def.baseUrl),
                year = byName("year"),
                quality = byName("quality"),
                kindHint = byName("kindHint").ifBlank { null }))
        }
        return out
    }

    private fun toResult(
        def: WebSourceDefinition, kind: String, title: String, pageUrl: String,
        poster: String, year: String, quality: String, kindHint: String?,
    ): WebResult {
        // kindHint (regex /(pelicula|serie)/) refina el kind; si no, usa el kind del contexto.
        val resolvedKind = when {
            kindHint == null -> kind
            kindHint.contains("serie", true) || kindHint.contains("tv", true) -> "tv"
            kindHint.contains("pelicula", true) || kindHint.contains("movie", true) -> "movie"
            else -> kind
        }
        val lang = def.languageTokens.firstOrNull()?.let { normalizeLang(it) } ?: ""
        return WebResult(
            siteId = def.id, siteName = def.name, title = title, year = year,
            pageUrl = pageUrl, posterUrl = poster, language = lang, quality = quality, kind = resolvedKind,
        )
    }

    private fun normalizeLang(token: String): String = when {
        token.contains("latino", true) -> "LAT"
        token.contains("castellano", true) -> "CAST"
        token.contains("spanish", true) -> "ES"
        else -> token.uppercase()
    }

    private fun field(row: Element, rule: FieldRule?, baseUrl: String): String? =
        rule?.let { HtmlParser.applyRule(row, it, baseUrl) }

    private fun resolveAbs(value: String, baseUrl: String): String = when {
        value.isBlank() || value.startsWith("http") -> value
        else -> baseUrl.trimEnd('/') + "/" + value.trimStart('/')
    }
}
