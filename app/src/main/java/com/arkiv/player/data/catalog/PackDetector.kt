package com.arkiv.player.data.catalog

/**
 * Detecta si un release contiene VARIOS episodios (pack de temporada, serie completa, rango de
 * capítulos) en vez de uno suelto. Sirve para avisar en la lista de fuentes: sin esto, al buscar un
 * capítulo puedes elegir sin saberlo un pack de 40 GB del que solo vas a ver un episodio.
 *
 * Balandro y Alfa marcan esto siempre (Balandro lo modela como `contentType='season'`; Alfa etiqueta
 * la entrada y detecta rangos tipo `Cap.301_310`).
 *
 * Es MÁS ESTRICTO que el `isPack` interno del buscador (que solo sirve para eximir del corte de
 * tamaño y acepta cualquier "Season 3"): aquí un release con un episodio CONCRETO nunca es pack,
 * porque marcar "PACK" sobre un episodio suelto sería engañoso.
 */
object PackDetector {

    /**
     * Un episodio concreto (SxxEyy, NxNN, "Cap.305", "Season 3 Episode 4") que NO es el inicio de un
     * rango. El `(?!\d)` es imprescindible: sin él, en "S01E01-E10" el motor retrocede y casa "e0"
     * para esquivar el lookahead del rango, marcando un pack como episodio suelto.
     */
    private val SINGLE_EPISODE = listOf(
        Regex("""s\d{1,2}\s*e\d{1,3}(?!\d)(?!\s*[-–]\s*e?\d)"""),
        Regex("""\b\d{1,2}x\d{1,3}(?!\d)(?!\s*[-–]\s*\d)"""),
        // "Cap.305" español, sin rango detrás.
        Regex("""\bcap\w*\s*\d{3,4}(?!\d)(?!\s*[-–_]\s*\d)"""),
        // Forma en palabras: "Season 3 Episode 4" / "Temporada 3 Capitulo 4".
        Regex("""(?:season|temporada)s?\s*\d{1,2}\s*(?:episode|episodio|capitulo|cap)\w*\s*\d{1,3}"""),
    )

    /** Marcadores inequívocos de multi-episodio. */
    private val PACK = listOf(
        // OJO: "complete" a secas NO basta. En la scene, "Deadpool.2016.COMPLETE.BluRay.REMUX" es una
        // PELÍCULA ("complete" = disco BluRay entero), no varios episodios. Solo cuenta con contexto de
        // serie/colección. (Falso positivo detectado probando en device.)
        Regex("""complete series|serie completa|complete collection|colecci[oó]n completa|""" +
            """todas las temporadas|all seasons|temporada completa"""),
        Regex("""\bcollection\b|\bcolecci[oó]n\b|\bsaga\b|\bintegrale?\b"""),
        // Rangos: S01-S08 / E01-E10 / Cap.301_310 / 1x01-1x10
        Regex("""s\d{1,2}\s*[-–]\s*s\d{1,2}"""),
        Regex("""e\d{1,3}\s*[-–]\s*e?\d{1,3}"""),
        Regex("""\bcap\w*\s*\d{3,4}\s*[-–_]\s*\d{3,4}"""),
        Regex("""\b\d{1,2}x\d{1,3}\s*[-–]\s*\d"""),
        // "Temporada 3" / "Season 3" SIN episodio concreto (lo filtra SINGLE_EPISODE antes).
        Regex("""\b(?:season|temporada)s?\s*\d{1,2}\b"""),
        Regex("""\bs\d{2}\b(?!\s*e\d)"""),
    )

    fun isPack(name: String): Boolean {
        // El '_' se CONSERVA a propósito: es el separador de rango en "Cap.301_310".
        val n = name.lowercase().replace('.', ' ')
        if (SINGLE_EPISODE.any { it.containsMatchIn(n) }) return false
        return PACK.any { it.containsMatchIn(n) }
    }
}
