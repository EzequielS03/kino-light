package com.arkiv.player.torrent

/**
 * Filtra archivos "basura" de un torrent (samples, trailers, extras, featurettes…) al elegir el video —
 * robado de Elementum (`GetCandidateFiles`/`skipFileRegex`, torrent.go:1776). Sin esto, un torrent que
 * trae `Movie.mkv` + `Sample.mkv` podría reproducir el sample (30s) si el matching por nombre lo pesca,
 * y un pack con "extras/" mete featurettes en la lista.
 */
object SampleFilter {
    // Tokens de basura como PALABRA (con separadores . _ - espacio o borde), para no matar títulos que
    // casualmente contengan la subcadena (ej. "Trailer Park Boys" no debe caer por "trailer").
    // Los bordes incluyen separadores de ruta (/ \) además de . _ - espacio [ ( para pescar "Extras/…".
    private val JUNK = Regex(
        "(?i)(^|[\\s._\\-\\[(/\\\\])(sample|muestra|trailer|tr[aá]iler|teaser|extras?|featurette|" +
            "behind[\\s._\\-]?the[\\s._\\-]?scenes|bonus|deleted[\\s._\\-]?scenes?|proof|screens?|" +
            "rarbg\\.com|rarbg|etrg[\\s._\\-]sample)([\\s._\\-)\\]/\\\\]|$)",
    )

    /** True si el nombre del archivo parece un sample/extra y NO la película/episodio principal. */
    fun isJunk(name: String): Boolean = JUNK.containsMatchIn(name)

    /**
     * Quita los archivos basura de [files] (pares índice→nombre), pero NO deja la lista vacía: si TODOS
     * parecen basura (falso positivo), devuelve la lista original para no quedarse sin video que servir.
     */
    fun clean(files: List<Pair<Int, String>>): List<Pair<Int, String>> {
        val kept = files.filterNot { isJunk(it.second) }
        return kept.ifEmpty { files }
    }
}
