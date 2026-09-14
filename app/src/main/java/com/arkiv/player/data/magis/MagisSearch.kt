package com.arkiv.player.data.magis

import org.json.JSONObject
import java.text.Normalizer

/**
 * Magis search decisions that don't touch the network: what gets asked of the portal, how what it
 * returns gets sorted, and how each item gets read. They're pure so they can be tested with no
 * portal — and they're exactly the ones that, done wrong, leave the correct title at position 11.
 *
 * Port of `adapters/magis/adapter.py`'s helpers (`_consulta_portal`, `_ordenar_por_parecido`,
 * `_temporada`, `_ordenar_temporadas`, `_titulo`, `_anio`, `_imagenes`, `_items`).
 */

private val SEPARATORS = Regex("[:,–—|]")
private val WORD = Regex("[0-9a-z]+")
private val SEASON = Regex(
    """(?:\bT\s?(\d{1,2})\b|\bTemp\.?\s?(\d{1,2})\b|\bTemporada\s?(\d{1,2})\b|\bS(\d{1,2})\b)""",
    RegexOption.IGNORE_CASE,
)

/**
 * What gets asked of the portal: the title's HEAD, up to the first separator.
 *
 * The portal's search matches by loose words, not by title. Measured on 2026-08-12: asking for
 * "Avatar: Aang, El ultimo Maestro Aire" returns 20 titles that share "ultimo" or "aire" —"El
 * ultimo refugio", "Venom: El ultimo baile"— with the correct one at position 11. Asking for
 * "Avatar" returns the whole family well ranked, with the correct one second.
 *
 * A one- or two-letter head ("El", "A") doesn't identify anything: there the whole title is worth
 * more, even if the portal ranks it worse.
 */
internal fun portalQuery(q: String): String {
    val head = SEPARATORS.split(q, limit = 2).first().trim()
    return if (head.length >= 3) head else q.trim()
}

/**
 * Words of 3+ letters, with no accents or punctuation. One- or two-letter ones ("el", "de", "la")
 * are discarded: they're exactly what makes "El ultimo refugio" look similar to anything.
 */
internal fun titleTokens(texto: String?): Set<String> {
    val plain = Normalizer.normalize(texto.orEmpty().lowercase(), Normalizer.Form.NFKD)
        .filter { Character.getType(it) != Character.NON_SPACING_MARK.toInt() }
    return WORD.findAll(plain).map { it.value }.filter { it.length > 2 }.toSet()
}

/**
 * The ones that share the most words with what was asked go first.
 *
 * Needed because the portal's order for a long title is noise (see [portalQuery]) and because a
 * SHORTER query than what was asked is requested from it: the pool comes in fine but mixes the
 * whole family, and whoever asked for a specific title has to see it at the top.
 *
 * [titulos] are the known forms of what was asked — the title in Spanish and, if TMDB knows it,
 * the ORIGINAL. Both are needed because the portal keeps a lot of international content only under
 * its English title. Scored with the BEST of the forms, not the sum: an item isn't more relevant
 * for showing up in two languages.
 *
 * Stable on purpose: with the same score, the portal's order wins.
 */
internal fun sortBySimilarity(items: List<JSONObject>, titulos: List<String>): List<JSONObject> {
    val requested = titulos.map { titleTokens(it) }.filter { it.isNotEmpty() }
    if (requested.isEmpty()) return items
    return items.sortedByDescending { item ->
        val ofItem = titleTokens(itemTitle(item))
        requested.maxOf { it.intersect(ofItem).size }
    }
}

/** Season number read from the name. 1 if it carries no suffix (single-season series). */
internal fun seasonFromName(nombre: String?): Int {
    val m = SEASON.find(nombre.orEmpty()) ?: return 1
    return m.groupValues.drop(1).firstOrNull { it.isNotBlank() }?.toIntOrNull() ?: 1
}

/** The title with its season suffix stripped, to group T1..T5 under the same series. */
internal fun withoutSeason(nombre: String?): String =
    SEASON.replace(nombre.orEmpty(), "").trim().lowercase()

/**
 * Leaves the same series' seasons in 1, 2, 3 order. The portal returns them mixed (seen on
 * device: T4, T2, T3, T5, T1) and in a poster grid that shows a lot more than in a text list.
 *
 * Only series-type items get touched, and only among themselves: each season goes back to a
 * position ALREADY occupied by a season, so movies don't shift and the relevance ranking is kept.
 * Between different series, whichever appeared first wins.
 */
internal fun sortSeasons(items: List<JSONObject>): List<JSONObject> {
    val indices = items.indices.filter { items[it].optString("programType") in MagisRef.SERIES }
    if (indices.size < 2) return items
    val titleOrder = mutableMapOf<String, Int>()
    indices.forEach { i ->
        titleOrder.getOrPut(withoutSeason(itemTitle(items[i]))) { titleOrder.size }
    }
    val series = indices.map { items[it] }.sortedWith(
        compareBy(
            { titleOrder.getValue(withoutSeason(itemTitle(it))) },
            { seasonFromName(itemTitle(it)) },
        ),
    )
    val output = items.toMutableList()
    indices.forEachIndexed { pos, i -> output[i] = series[pos] }
    return output
}

/**
 * The item's readable name. The portal uses `name` (and `viewPoint`/`alias` as a fallback), NOT
 * `title`: without this the result shows up with the contentId, which is a 32-char hash.
 */
internal fun itemTitle(item: JSONObject): String {
    for (key in listOf("name", "viewPoint", "alias")) {
        item.optString(key).takeIf { it.isNotBlank() }?.let { return it }
    }
    return ""
}

/** The year comes from `releaseTime` (ISO); the portal doesn't expose a `year` field. */
internal fun itemYear(item: JSONObject): String {
    val release = item.optString("releaseTime")
    val year = release.take(4)
    return if (year.length == 4 && year.all(Char::isDigit)) year else ""
}

/**
 * The item's image URLs, by type. `fileType` is the only reliable criterion: `size` comes in two
 * different formats within the same response ("100*100" and "262x370"). If a type is missing, its
 * key is NOT emitted — an empty string would force distinguishing "there's none" from "there is
 * one and it's empty".
 */
internal fun itemImages(item: JSONObject): Map<String, String> {
    val output = mutableMapOf<String, String>()
    item.optJSONArray("posterList")?.forEachObject { p ->
        val key = IMAGE_TYPES[p.optString("fileType")] ?: return@forEachObject
        val url = p.optString("fileUrl")
        if (url.isNotBlank() && key !in output) output[key] = url
    }
    return output
}

/** Portal `fileType` → the key the app sees. `stage` (100x100) is discarded: it's no use for
 *  anything the app shows. */
private val IMAGE_TYPES = mapOf("icon" to "poster", "poster" to "backdrop")

/** Flattens the search: the portal answers in three shapes depending on the endpoint. */
internal fun searchItems(respuesta: JSONObject): List<JSONObject> {
    val output = mutableListOf<JSONObject>()
    respuesta.optJSONArray("searchItemList")?.forEachObject { grupo ->
        grupo.optJSONArray("itemList")?.forEachObject { output.add(it) }
    }
    if (output.isNotEmpty()) return output
    (respuesta.optJSONArray("assetList") ?: respuesta.optJSONArray("list"))
        ?.forEachObject { output.add(it) }
    return output
}
