package com.arkiv.player.data.catalog.web

import org.jsoup.nodes.Element

/** Aplica [FieldRule]s a elementos HTML parseados (jsoup). Usado por [WebHtmlParser]. */
object HtmlParser {

    /** Aplica una [FieldRule] a un elemento: selecciona, saca texto/atributo, aplica regex y resolve. */
    fun applyRule(row: Element, rule: FieldRule, baseUrl: String): String? = runCatching {
        val el = if (rule.selector.isBlank()) row else row.selectFirst(rule.selector) ?: return null
        var value = when (rule.attr) {
            "", "text" -> el.text()
            else -> if (rule.resolve == "absolute" && (rule.attr == "href" || rule.attr == "src"))
                el.absUrl(rule.attr).ifBlank { el.attr(rule.attr) } else el.attr(rule.attr)
        }
        rule.regex?.let { rx -> value = Regex(rx).find(value)?.groupValues?.let { it.getOrNull(1) ?: it[0] } ?: "" }
        if (rule.resolve == "absolute" && value.isNotBlank() && !value.startsWith("http") && !value.startsWith("magnet:")) {
            value = baseUrl.trimEnd('/') + "/" + value.trimStart('/')
        }
        value.trim()
    }.getOrNull()
}
