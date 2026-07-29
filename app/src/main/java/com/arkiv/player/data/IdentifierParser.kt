package com.arkiv.player.data

/** Extrae el identifier de archive.org desde una URL pegada o texto suelto. */
object IdentifierParser {

    private val MARKERS = listOf("/details/", "/download/", "/metadata/", "/embed/")

    /** Devuelve el identifier, o null si la entrada no parece válida. */
    fun extract(input: String): String? {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return null

        for (marker in MARKERS) {
            val idx = trimmed.indexOf(marker)
            if (idx >= 0) {
                val rest = trimmed.substring(idx + marker.length)
                return firstSegment(rest)
            }
        }

        // Sin marcador: si no tiene esquema ni espacios, se toma como identifier suelto.
        if (!trimmed.contains("://") && !trimmed.contains(' ')) {
            return firstSegment(trimmed).ifEmpty { null }
        }
        return null
    }

    private fun firstSegment(s: String): String {
        val cut = s.indexOfFirst { it == '/' || it == '?' || it == '#' }
        val seg = if (cut >= 0) s.substring(0, cut) else s
        return seg.trim()
    }
}
