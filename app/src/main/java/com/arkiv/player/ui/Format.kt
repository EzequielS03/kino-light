package com.arkiv.player.ui

/** Duración legible a partir de segundos: "1 h 26 min", "45 min", "0 min". */
fun formatRuntime(seconds: Double): String {
    val total = seconds.toInt().coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    return when {
        h > 0 && m > 0 -> "$h h $m min"
        h > 0 -> "$h h"
        else -> "$m min"
    }
}

/** Etiqueta secundaria de una card de biblioteca según su tipo. */
fun libraryMeta(isMovie: Boolean, durationSeconds: Double, episodeCount: Int): String = when {
    !isMovie -> "$episodeCount episodios"
    else -> formatRuntime(durationSeconds)
}

private val HtmlTag = Regex("<[^>]*>")
private val Whitespace = Regex("\\s+")

/**
 * Sinopsis lista para pintar. AniList devuelve HTML (`<br>`, `<i>`) y las descripciones de
 * archive.org llegan con markup y saltos crudos. Devuelve "" si no queda texto, para que el
 * call site caiga a su respaldo con `ifBlank`.
 */
fun plainSynopsis(raw: String?): String {
    if (raw.isNullOrBlank()) return ""
    // Los tags se quitan ANTES de decodificar entidades: al revés, un "&lt;b&gt;" literal se
    // volvería "<b>" y el strip se comería texto que el autor escribió a propósito.
    // Por espacio y no por "": un "<br>" entre palabras tiene que dejar separador.
    return raw.replace(HtmlTag, " ")
        .replace("&nbsp;", " ")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&apos;", "'")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        // "&amp;" va al final: si fuera primero, "&amp;lt;" se decodificaría dos veces y
        // terminaría en "<" en vez del "&lt;" literal que el autor escribió.
        .replace("&amp;", "&")
        .replace(Whitespace, " ")
        .trim()
}

/**
 * Subtítulo del hero: la sinopsis del título, o [fallback] cuando no sirve.
 *
 * "No sirve" incluye el caso que parece raro y no lo es: que la descripción **sea** el título.
 * En archive.org quien sube el archivo suele repetir el nombre en la descripción
 * ("Night Of The Living Dead 1990" → "Night of the living dead 1990"), y pintarla debajo del
 * título daría exactamente la duplicación que este subtítulo existe para evitar.
 *
 * Solo se descarta la igualdad, no el prefijo: una sinopsis que arranca con el nombre de la obra
 * ("Avatar Aang, el último Maestro Aire del mundo, se entera de…") es legítima y se conserva.
 */
fun heroSubtitle(title: String, description: String?, fallback: String): String {
    val synopsis = plainSynopsis(description)
    return if (synopsis.equals(title.trim(), ignoreCase = true)) fallback else synopsis.ifBlank { fallback }
}

/**
 * Respaldo del subtítulo del hero en "Continuar viendo" cuando el ítem no tiene sinopsis: la
 * etiqueta del episodio SIN el título de la serie, que ya está arriba en el hero.
 *
 * Devuelve "" cuando lo que quedaría es el título repetido — el caso película, donde
 * `displayName` es directamente el título limpio del archivo.
 */
fun heroFallback(itemTitle: String, displayName: String): String {
    val title = itemTitle.trim()
    val prefix = "$title · "
    // Si la etiqueta se formateó con otro showTitle (el título se editó después), no hay prefijo
    // que quitar y se deja tal cual: mejor de más que comerse texto por una coincidencia parcial.
    val rest = displayName.let {
        if (it.startsWith(prefix, ignoreCase = true)) it.substring(prefix.length) else it
    }.trim()
    // Insensible a mayúsculas: los títulos guardados y los cleanName() de archivos difieren en
    // capitalización seguido, y una duplicación que se escapa por una mayúscula es el bug de acá.
    return if (rest.equals(title, ignoreCase = true)) "" else rest
}
