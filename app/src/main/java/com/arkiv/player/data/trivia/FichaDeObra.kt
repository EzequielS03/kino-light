package com.arkiv.player.data.trivia

import org.json.JSONArray
import org.json.JSONObject

/**
 * Los hechos de un capítulo puntual, cuando la obra es una serie y TMDB lo encontró.
 *
 * `temporada` y `episodio` salen del propio JSON del capítulo (`season_number`/`episode_number`),
 * no de fuera: así [FichaDeObra.renglones] queda armado solo con lo que TMDB confirmó.
 */
internal data class FichaDeCapitulo(
    val temporada: Int? = null,
    val episodio: Int? = null,
    val nombre: String? = null,
    /** `air_date`, tal como lo manda TMDB (`yyyy-MM-dd`). */
    val fecha: String? = null,
    val directores: List<String> = emptyList(),
    val guionistas: List<String> = emptyList(),
    val invitados: List<String> = emptyList(),
)

/**
 * Hechos verificados de TMDB para anclar el dato curioso a ESTA obra exacta: nunca la sinopsis ni
 * el `overview`, porque traen trama y el dato curioso no puede tener spoilers.
 *
 * Se arma con [fichaDePelicula] o [fichaDeSerie] (más [capituloDeFicha] si aplica) — nunca a mano
 * salvo la ficha mínima de respaldo sin `tmdbId` (ver `ArkivRepository.fichaDeObra`), que trae solo
 * [nombre] y nada más.
 */
internal data class FichaDeObra(
    /** "movie" o "tv", como en [ObraDeDatos.tipo]: decide el rótulo y qué hechos tienen sentido. */
    val tipo: String,
    /** Título de la película o nombre de la serie. Nunca vacío si esta ficha vino de TMDB. */
    val nombre: String,
    /** `release_date` (película) o `first_air_date` (serie), tal como los manda TMDB. */
    val fechaEstreno: String? = null,
    /** Solo en películas: `credits.crew` con `job == "Director"`. */
    val directores: List<String> = emptyList(),
    /** Solo en películas: `credits.crew` con `department == "Writing"`. */
    val guionistas: List<String> = emptyList(),
    /** Los primeros 5 del reparto principal, por `order` (`credits.cast` o `aggregate_credits.cast`). */
    val reparto: List<String> = emptyList(),
    /** Solo en películas: `production_companies[].name`. */
    val productoras: List<String> = emptyList(),
    /** Solo en películas: `runtime`, en minutos. */
    val duracionMinutos: Int? = null,
    /** Solo en series: `created_by[].name`. */
    val creadores: List<String> = emptyList(),
    /** Solo en series: `networks[].name`. */
    val cadenas: List<String> = emptyList(),
    /** Solo si la obra es un capítulo de serie y TMDB lo encontró. */
    val capitulo: FichaDeCapitulo? = null,
) {
    /**
     * El bloque de hechos que va en el prompt, para presentarlos como datos verificados de TMDB:
     * una línea para la película o la serie, y otra para el capítulo si lo hay. Vacío si la ficha
     * no trae ningún hecho más allá del nombre (la ficha mínima de respaldo).
     */
    fun renglones(): String {
        val lineas = mutableListOf<String>()
        val datosPrincipales = mutableListOf<String>()
        fechaEstreno?.let { datosPrincipales += if (tipo == "movie") "estrenada $it" else "primera emisión $it" }
        if (tipo == "movie") {
            if (directores.isNotEmpty()) datosPrincipales += "dirigida por ${directores.joinToString(", ")}"
            if (guionistas.isNotEmpty()) datosPrincipales += "escrita por ${guionistas.joinToString(", ")}"
        } else {
            if (creadores.isNotEmpty()) datosPrincipales += "creada por ${creadores.joinToString(", ")}"
            if (cadenas.isNotEmpty()) datosPrincipales += "canal ${cadenas.joinToString(", ")}"
        }
        if (reparto.isNotEmpty()) datosPrincipales += "reparto: ${reparto.joinToString(", ")}"
        if (tipo == "movie") {
            if (productoras.isNotEmpty()) datosPrincipales += "productoras: ${productoras.joinToString(", ")}"
            duracionMinutos?.let { datosPrincipales += "$it min" }
        }
        if (datosPrincipales.isNotEmpty()) {
            val etiqueta = if (tipo == "movie") "Película" else "Serie"
            lineas += "$etiqueta: $nombre (${datosPrincipales.joinToString("; ")})"
        }
        capitulo?.let { c ->
            val datosCapitulo = mutableListOf<String>()
            c.fecha?.let { datosCapitulo += "emitido $it" }
            if (c.directores.isNotEmpty()) datosCapitulo += "dirigido por ${c.directores.joinToString(", ")}"
            if (c.guionistas.isNotEmpty()) datosCapitulo += "escrito por ${c.guionistas.joinToString(", ")}"
            if (c.invitados.isNotEmpty()) datosCapitulo += "invitados: ${c.invitados.joinToString(", ")}"
            val numeros = when {
                c.temporada != null && c.episodio != null -> "temporada ${c.temporada}, episodio ${c.episodio}"
                c.episodio != null -> "episodio ${c.episodio}"
                c.temporada != null -> "temporada ${c.temporada}"
                else -> null
            }
            val encabezado = listOfNotNull(numeros, c.nombre?.let { "«$it»" }).joinToString(", ")
            if (encabezado.isNotBlank() || datosCapitulo.isNotEmpty()) {
                val sufijo = if (datosCapitulo.isNotEmpty()) " (${datosCapitulo.joinToString("; ")})" else ""
                lineas += "Capítulo: $encabezado$sufijo"
            }
        }
        return lineas.joinToString("\n")
    }
}

/** Como `optString`, pero sin el texto literal `"null"` que Android devuelve cuando el campo es un
 *  null de verdad (mismo patrón que `TmdbApi.texto`; TMDB manda nulls de verdad seguido). */
private fun JSONObject.texto(name: String): String = if (isNull(name)) "" else optString(name)

/** Si [nombre] tiene alguna letra latina. TMDB guarda el `name` de mucha gente japonesa (y de otras
 *  procedencias) en su alfabeto original: sin ninguna letra latina ese nombre sale ilegible para
 *  quien ve en Colombia, así que los parsers de personas lo descartan (nunca los títulos de la obra
 *  ni del capítulo, que se muestran tal cual). Un nombre mixto con al menos una letra latina se queda. */
private val LETRA_LATINA = Regex("\\p{IsLatin}")

private fun tieneLetrasLatinas(nombre: String): Boolean = LETRA_LATINA.containsMatchIn(nombre)

/** Los `name` no vacíos (ni el texto `"null"`) de un arreglo de objetos `{"name": ...}`. */
private fun JSONArray?.nombres(): List<String> =
    (0 until (this?.length() ?: 0)).mapNotNull { i -> this?.optJSONObject(i)?.texto("name")?.takeIf(String::isNotBlank) }

/** Los `name` de `crew` cuyo `job` es exactamente "Director". */
private fun JSONArray?.directoresDe(): List<String> =
    (0 until (this?.length() ?: 0)).mapNotNull { i -> this?.optJSONObject(i) }
        .filter { it.texto("job") == "Director" }
        .mapNotNull { it.texto("name").takeIf(String::isNotBlank) }
        .filter(::tieneLetrasLatinas)
        .distinct()

/** Los `name` de `crew` cuyo `department` es "Writing" (puede repetir persona con distintos `job`). */
private fun JSONArray?.guionistasDe(): List<String> =
    (0 until (this?.length() ?: 0)).mapNotNull { i -> this?.optJSONObject(i) }
        .filter { it.texto("department") == "Writing" }
        .mapNotNull { it.texto("name").takeIf(String::isNotBlank) }
        .filter(::tieneLetrasLatinas)
        .distinct()

/** Los primeros [n] `name` de un arreglo de reparto, ordenados por `order` (los sin `order` al final). */
private fun JSONArray?.repartoDe(n: Int): List<String> =
    (0 until (this?.length() ?: 0)).mapNotNull { i -> this?.optJSONObject(i) }
        .sortedBy { it.optInt("order", Int.MAX_VALUE) }
        .mapNotNull { it.texto("name").takeIf(String::isNotBlank) }
        .filter(::tieneLetrasLatinas)
        .take(n)

private const val REPARTO_PRINCIPAL = 5
private const val INVITADOS_PRINCIPALES = 5

/** Parsea `movie/{id}?append_to_response=credits`. Puro/testeable (sin red). Null si el JSON no se
 *  puede leer o no trae ni siquiera un título. */
internal fun fichaDePelicula(json: String): FichaDeObra? = runCatching {
    val o = JSONObject(json)
    val nombre = o.texto("title").takeIf { it.isNotBlank() } ?: return@runCatching null
    val credits = o.optJSONObject("credits")
    FichaDeObra(
        tipo = "movie",
        nombre = nombre,
        fechaEstreno = o.texto("release_date").takeIf { it.isNotBlank() },
        directores = credits?.optJSONArray("crew").directoresDe(),
        guionistas = credits?.optJSONArray("crew").guionistasDe(),
        reparto = credits?.optJSONArray("cast").repartoDe(REPARTO_PRINCIPAL),
        productoras = o.optJSONArray("production_companies").nombres(),
        duracionMinutos = o.optInt("runtime", 0).takeIf { it > 0 },
    )
}.getOrNull()

/** Parsea `tv/{id}?append_to_response=aggregate_credits`. Puro/testeable (sin red). Null si el JSON
 *  no se puede leer o no trae ni siquiera un nombre. */
internal fun fichaDeSerie(json: String): FichaDeObra? = runCatching {
    val o = JSONObject(json)
    val nombre = o.texto("name").takeIf { it.isNotBlank() } ?: return@runCatching null
    val cast = o.optJSONObject("aggregate_credits")?.optJSONArray("cast")
    FichaDeObra(
        tipo = "tv",
        nombre = nombre,
        fechaEstreno = o.texto("first_air_date").takeIf { it.isNotBlank() },
        reparto = cast.repartoDe(REPARTO_PRINCIPAL),
        creadores = o.optJSONArray("created_by").nombres().filter(::tieneLetrasLatinas),
        cadenas = o.optJSONArray("networks").nombres(),
    )
}.getOrNull()

/** Parsea `tv/{id}/season/{s}/episode/{e}` (crew y guest_stars vienen en la raíz, sin envoltorio de
 *  `append_to_response`). Puro/testeable (sin red). Null si el JSON no se puede leer o no trae ni
 *  siquiera un número de temporada o de episodio. */
internal fun capituloDeFicha(json: String): FichaDeCapitulo? = runCatching {
    val o = JSONObject(json)
    val temporada = o.optInt("season_number", 0).takeIf { it > 0 }
    val episodio = o.optInt("episode_number", 0).takeIf { it > 0 }
    if (temporada == null && episodio == null) return@runCatching null
    val invitados = (0 until (o.optJSONArray("guest_stars")?.length() ?: 0))
        .mapNotNull { i -> o.optJSONArray("guest_stars")?.optJSONObject(i) }
        .mapNotNull { it.texto("name").takeIf(String::isNotBlank) }
        .filter(::tieneLetrasLatinas)
        .take(INVITADOS_PRINCIPALES)
    FichaDeCapitulo(
        temporada = temporada,
        episodio = episodio,
        nombre = o.texto("name").takeIf { it.isNotBlank() },
        fecha = o.texto("air_date").takeIf { it.isNotBlank() },
        directores = o.optJSONArray("crew").directoresDe(),
        guionistas = o.optJSONArray("crew").guionistasDe(),
        invitados = invitados,
    )
}.getOrNull()
