package com.arkiv.player.data.magis

import org.json.JSONObject
import java.text.Normalizer

/**
 * Las decisiones de la búsqueda de Magis que no tocan la red: qué se le pide al portal, cómo se
 * ordena lo que devuelve y cómo se lee cada ítem. Son puras para poder probarlas sin portal — y
 * son justo las que, mal hechas, dejan el título correcto en el puesto 11.
 *
 * Puerto de los helpers de `adapters/magis/adapter.py` (`_consulta_portal`, `_ordenar_por_parecido`,
 * `_temporada`, `_ordenar_temporadas`, `_titulo`, `_anio`, `_imagenes`, `_items`).
 */

private val SEPARADORES = Regex("[:,–—|]")
private val PALABRA = Regex("[0-9a-z]+")
private val TEMPORADA = Regex(
    """(?:\bT\s?(\d{1,2})\b|\bTemp\.?\s?(\d{1,2})\b|\bTemporada\s?(\d{1,2})\b|\bS(\d{1,2})\b)""",
    RegexOption.IGNORE_CASE,
)

/**
 * Lo que se le pide al portal: la CABEZA del título, hasta el primer separador.
 *
 * El buscador del portal matchea por palabras sueltas, no por título. Medido el 2026-08-12: pedirle
 * "Avatar: Aang, El ultimo Maestro Aire" devuelve 20 títulos que comparten "ultimo" o "aire" —"El
 * ultimo refugio", "Venom: El ultimo baile"— con el correcto en el puesto 11. Pedirle "Avatar"
 * devuelve la familia entera bien rankeada, con el correcto segundo.
 *
 * Una cabeza de una o dos letras ("El", "A") no identifica nada: ahí vale más el título entero,
 * aunque el portal lo rankee peor.
 */
internal fun consultaDePortal(q: String): String {
    val cabeza = SEPARADORES.split(q, limit = 2).first().trim()
    return if (cabeza.length >= 3) cabeza else q.trim()
}

/**
 * Palabras de 3+ letras, sin tildes ni puntuación. Las de una o dos ("el", "de", "la") se descartan:
 * son justo las que hacen que "El ultimo refugio" parezca parecido a cualquier cosa.
 */
internal fun tokensDeTitulo(texto: String?): Set<String> {
    val plano = Normalizer.normalize(texto.orEmpty().lowercase(), Normalizer.Form.NFKD)
        .filter { Character.getType(it) != Character.NON_SPACING_MARK.toInt() }
    return PALABRA.findAll(plano).map { it.value }.filter { it.length > 2 }.toSet()
}

/**
 * Primero los que más palabras comparten con lo que se pidió.
 *
 * Hace falta porque el orden del portal para un título largo es ruido (ver [consultaDePortal]) y
 * porque se le pide una consulta MÁS CORTA que lo pedido: el pool viene bien pero mezcla toda la
 * familia, y quien pidió un título concreto tiene que verlo arriba.
 *
 * [titulos] son las formas conocidas de lo pedido — el título en español y, si TMDB lo sabe, el
 * ORIGINAL. Hacen falta las dos porque el portal guarda mucho contenido internacional solo con su
 * título en inglés. Se puntúa con la MEJOR de las formas, no con la suma: un ítem no es más
 * relevante por aparecer en dos idiomas.
 *
 * Estable a propósito: con el mismo puntaje manda el orden del portal.
 */
internal fun ordenarPorParecido(items: List<JSONObject>, titulos: List<String>): List<JSONObject> {
    val pedidos = titulos.map { tokensDeTitulo(it) }.filter { it.isNotEmpty() }
    if (pedidos.isEmpty()) return items
    return items.sortedByDescending { item ->
        val delItem = tokensDeTitulo(tituloDeItem(item))
        pedidos.maxOf { it.intersect(delItem).size }
    }
}

/** Número de temporada leído del nombre. 1 si no trae sufijo (serie de temporada única). */
internal fun temporadaDeNombre(nombre: String?): Int {
    val m = TEMPORADA.find(nombre.orEmpty()) ?: return 1
    return m.groupValues.drop(1).firstOrNull { it.isNotBlank() }?.toIntOrNull() ?: 1
}

/** El título sin su sufijo de temporada, para agrupar T1..T5 bajo la misma serie. */
internal fun sinTemporada(nombre: String?): String =
    TEMPORADA.replace(nombre.orEmpty(), "").trim().lowercase()

/**
 * Deja las temporadas de una misma serie en orden 1, 2, 3. El portal las devuelve mezcladas (visto
 * en device: T4, T2, T3, T5, T1) y en una grilla de carátulas eso se nota mucho más que en una
 * lista de texto.
 *
 * Solo se tocan los ítems de tipo serie y solo entre ellos: cada temporada vuelve a una posición que
 * YA ocupaba una temporada, así que las películas no se corren y el ranking de relevancia se
 * conserva. Entre series distintas manda cuál apareció primero.
 */
internal fun ordenarTemporadas(items: List<JSONObject>): List<JSONObject> {
    val indices = items.indices.filter { items[it].optString("programType") in MagisRef.SERIES }
    if (indices.size < 2) return items
    val ordenDeTitulos = mutableMapOf<String, Int>()
    indices.forEach { i ->
        ordenDeTitulos.getOrPut(sinTemporada(tituloDeItem(items[i]))) { ordenDeTitulos.size }
    }
    val series = indices.map { items[it] }.sortedWith(
        compareBy(
            { ordenDeTitulos.getValue(sinTemporada(tituloDeItem(it))) },
            { temporadaDeNombre(tituloDeItem(it)) },
        ),
    )
    val salida = items.toMutableList()
    indices.forEachIndexed { pos, i -> salida[i] = series[pos] }
    return salida
}

/**
 * Nombre legible del ítem. El portal usa `name` (y `viewPoint`/`alias` como respaldo), NO `title`:
 * sin esto el resultado se muestra con el contentId, que es un hash de 32 chars.
 */
internal fun tituloDeItem(item: JSONObject): String {
    for (clave in listOf("name", "viewPoint", "alias")) {
        item.optString(clave).takeIf { it.isNotBlank() }?.let { return it }
    }
    return ""
}

/** El año sale de `releaseTime` (ISO); el portal no expone un campo `year`. */
internal fun anioDeItem(item: JSONObject): String {
    val lanzamiento = item.optString("releaseTime")
    val anio = lanzamiento.take(4)
    return if (anio.length == 4 && anio.all(Char::isDigit)) anio else ""
}

/**
 * URLs de las imágenes del ítem, por tipo. El `fileType` es el único criterio confiable: los `size`
 * vienen con dos formatos distintos en la misma respuesta ("100*100" y "262x370"). Si un tipo falta,
 * su clave NO se emite — una cadena vacía obligaría a distinguir "no hay" de "hay y está vacía".
 */
internal fun imagenesDeItem(item: JSONObject): Map<String, String> {
    val salida = mutableMapOf<String, String>()
    item.optJSONArray("posterList")?.forEachObjeto { p ->
        val clave = TIPOS_DE_IMAGEN[p.optString("fileType")] ?: return@forEachObjeto
        val url = p.optString("fileUrl")
        if (url.isNotBlank() && clave !in salida) salida[clave] = url
    }
    return salida
}

/** `fileType` del portal → clave que ve la app. `stage` (100x100) se descarta: no sirve para nada
 *  de lo que la app muestra. */
private val TIPOS_DE_IMAGEN = mapOf("icon" to "poster", "poster" to "backdrop")

/** Aplana la búsqueda: el portal responde en tres formas según el endpoint. */
internal fun itemsDeBusqueda(respuesta: JSONObject): List<JSONObject> {
    val salida = mutableListOf<JSONObject>()
    respuesta.optJSONArray("searchItemList")?.forEachObjeto { grupo ->
        grupo.optJSONArray("itemList")?.forEachObjeto { salida.add(it) }
    }
    if (salida.isNotEmpty()) return salida
    (respuesta.optJSONArray("assetList") ?: respuesta.optJSONArray("list"))
        ?.forEachObjeto { salida.add(it) }
    return salida
}
