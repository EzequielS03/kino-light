package com.arkiv.player.torrent

/**
 * Elige, entre los nombres de archivo de un torrent, el índice del que corresponde al episodio
 * pedido. Devuelve null si no hay match confiable (para caer al "más grande"). Reglas: SxxEyy,
 * SxEy, NxNN (1x05), capítulo español "Cap NEE" (1er dígito=temporada, 2 finales=episodio), y
 * número absoluto/relativo suelto (anime). Ignora números de resolución/codec/año (1080p, x264,
 * h265, 2019).
 */
object EpisodeFilePicker {
    /**
     * @param names nombres de archivo (en el MISMO orden que la lista de video del caller)
     * @param season temporada pedida (0 válido para especiales)
     * @param episode episodio relativo pedido
     * @param absoluteEpisode nº absoluto (anime), o null
     * @return índice dentro de [names] del archivo elegido, o null si no hay match único/confiable
     */
    fun pick(names: List<String>, season: Int, episode: Int, absoluteEpisode: Int? = null): Int? {
        if (names.size <= 1) return names.indices.firstOrNull()
        val cleaned = names.map { it.lowercase().replace('.', ' ').replace('_', ' ') }

        // 1) SxxEyy / SxEy exactos.
        val seRx = Regex("""s0*$season\s*e0*$episode(?!\d)""")
        matchUnique(cleaned) { seRx.containsMatchIn(it) }?.let { return it }

        // 2) NxNN (1x05) exacto.
        val xRx = Regex("""\b${season}x0*$episode(?!\d)""")
        matchUnique(cleaned) { xRx.containsMatchIn(it) }?.let { return it }

        // 3) Capítulo español "Cap NEE": primer dígito = temporada, 2 finales = episodio.
        val capRx = Regex("""cap\w*\s*0*(\d)(\d{2})(?!\d)""")
        matchUnique(cleaned) { name ->
            capRx.findAll(name).any { m -> m.groupValues[1].toInt() == season && m.groupValues[2].toInt() == episode }
        }?.let { return it }

        // 4) Número absoluto o relativo suelto (anime / "Show 1085"): evita resolución/codec/año.
        val targets = listOfNotNull(absoluteEpisode, episode).toSet()
        matchUnique(cleaned) { name -> looseEpisodeNumbers(name).any { it in targets } }?.let { return it }

        return null
    }

    private inline fun matchUnique(names: List<String>, pred: (String) -> Boolean): Int? {
        val hits = names.indices.filter { pred(names[it]) }
        return if (hits.size == 1) hits[0] else null
    }

    // Números "de episodio" plausibles: descarta los pegados a resolución/codec/año (1080p, x264,
    // 10bit, h264/h265, años tipo 19xx/20xx).
    private fun looseEpisodeNumbers(name: String): List<Int> {
        val bad = Regex("""\d+\s*(p|bit)\b|x\s*2\s*6[45]|h\s*26[45]|\b(19|20)\d{2}\b""")
        val masked = bad.replace(name, " ")
        return Regex("""\b(\d{1,4})\b""").findAll(masked).map { it.groupValues[1].toInt() }.filter { it in 1..3000 }.toList()
    }
}
