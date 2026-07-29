package com.arkiv.player.data.catalog.web

import org.json.JSONObject

/** Regla de extracción de un campo desde un elemento HTML (selector CSS + atributo + regex + resolve). */
data class FieldRule(
    val selector: String,
    val attr: String,
    val regex: String? = null,
    val resolve: String? = null, // "absolute" completa URLs relativas con baseUrl
) {
    companion object {
        fun fromJson(o: JSONObject?): FieldRule? {
            if (o == null) return null
            val sel = o.optString("selector")
            // selector vacío = el propio elemento fila (soportado por HtmlParser.applyRule); se permite
            // siempre que haya un attr explícito (ej. la fila ES el <a>: {selector:"", attr:"href"}).
            // Solo se rechaza la regla totalmente vacía (sin selector ni attr).
            if (sel.isBlank() && o.optString("attr").isBlank()) return null
            return FieldRule(
                selector = sel,
                attr = o.optString("attr", "text"),
                regex = o.optString("regex").ifBlank { null },
                resolve = o.optString("resolve").ifBlank { null },
            )
        }
    }
}
