package com.arkiv.player.data.catalog

/**
 * Extrae una etiqueta de calidad legible del nombre de un release de torrent ("1080p BluRay", "4K HDR",
 * "720p WEB", "CAM"…) para mostrarla como badge en la lista de fuentes. Tokens robados de los canales de
 * Balandro/Alfa (mejortorrents/grantorrent/torrentdivx puntuar_calidad + autoplay.py equivalencias).
 */
object QualityLabel {
    fun extract(name: String): String {
        val n = name.lowercase()
        // Sin \b en 4k/uhd: los releases hispanos pegan los tokens ("4kuhdremux", "4khdr") y \b fallaría.
        val res = when {
            Regex("2160p|uhd|\\b4k").containsMatchIn(n) -> "4K"
            Regex("1080p").containsMatchIn(n) -> "1080p"
            Regex("720p").containsMatchIn(n) -> "720p"
            Regex("480p").containsMatchIn(n) -> "480p"
            else -> ""
        }
        // Fuente (una sola, la más específica) — orden de mayor a menor fidelidad. Incluye tokens de la
        // escena hispana (MicroHD, BD/UHD-Remux) robados de Balandro (puntuar_calidad de cada canal).
        val source = when {
            Regex("remux").containsMatchIn(n) -> "REMUX"          // bd(remux)/uhd(remux) caen aquí
            Regex("micro-?hd").containsMatchIn(n) -> "MicroHD"
            Regex("blu-?ray|\\bbdrip\\b|bd-?rip|fullbluray").containsMatchIn(n) -> "BluRay"
            Regex("web-?dl|web-?rip|\\bwebr?\\b").containsMatchIn(n) -> "WEB"
            Regex("\\bhdrip\\b|hd-?rip").containsMatchIn(n) -> "HDRip"
            Regex("\\bhdtv\\b").containsMatchIn(n) -> "HDTV"
            Regex("dvd-?rip|dvdscr").containsMatchIn(n) -> "DVDRip"
            Regex("telesync|telecine|hdcam|\\bcam\\b|screener|\\bscr\\b").containsMatchIn(n) -> "CAM"
            else -> ""
        }
        val hdr = when {
            Regex("dolby.?vision|\\bdovi\\b").containsMatchIn(n) -> "DV"
            Regex("\\bhdr10?\\b|\\bhdr\\b").containsMatchIn(n) -> "HDR"
            else -> ""
        }
        return listOf(res, source, hdr).filter { it.isNotBlank() }.joinToString(" ")
    }
}
