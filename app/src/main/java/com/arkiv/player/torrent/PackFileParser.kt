package com.arkiv.player.torrent

/** Parseo de un nombre de archivo a (temporada, episodio, absoluto). Espejo del reconocimiento de
 *  [EpisodeFilePicker], con grupos de captura, para listar los capítulos de un pack. */
data class PackFileInfo(val season: Int?, val episode: Int?, val absolute: Int?)

object PackFileParser {
    private val SE = Regex("""s(\d{1,2})\s*e(\d{1,3})(?!\d)""")
    private val NX = Regex("""\b(\d{1,2})x(\d{1,3})(?!\d)""")
    private val CAP = Regex("""cap\w*\s*0*(\d)(\d{2})(?!\d)""")
    // Enmascara resolución/codec/año antes de buscar el número absoluto (anime).
    private val BAD = Regex("""\d+\s*(p|bit)\b|x\s*2\s*6[45]|h\s*26[45]|\b(19|20)\d{2}\b""")

    fun parse(name: String): PackFileInfo {
        val n = name.lowercase().replace('.', ' ').replace('_', ' ')
        SE.find(n)?.let { return PackFileInfo(it.groupValues[1].toInt(), it.groupValues[2].toInt(), null) }
        NX.find(n)?.let { return PackFileInfo(it.groupValues[1].toInt(), it.groupValues[2].toInt(), null) }
        CAP.find(n)?.let { return PackFileInfo(it.groupValues[1].toInt(), it.groupValues[2].toInt(), null) }
        val abs = Regex("""\b(\d{1,4})\b""").findAll(BAD.replace(n, " "))
            .map { it.groupValues[1].toInt() }.firstOrNull { it in 1..3000 }
        return PackFileInfo(null, null, abs)
    }
}
