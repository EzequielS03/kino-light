package com.arkiv.player.data

/**
 * Cómo leer la numeración que algunas fuentes dejan METIDA dentro del `orderIndex`.
 *
 * Las fuentes de torrent y web guardan sus capítulos con `orderIndex = temporada*1000 + episodio`
 * (ver `ArkivRepository.addSeriesEpisode`, `addSeriesEpisodeMagnet`, `addWebSeriesEpisode` y
 * `PackRowBuilder`), mientras que archive.org lo usa como un correlativo 0..N-1. Los dos números
 * conviven en la misma columna y hay que saber cuál es cuál.
 *
 * **Por qué no alcanza con mirar el número.** La regla vieja era "si `orderIndex >= 1000` viene
 * codificado". Se cae por los dos lados:
 *  - Una **temporada 0** (los especiales) da `0*1000 + 3 = 3`, indistinguible de un correlativo de
 *    archive: el especial 3 se mostraba como "E4".
 *  - Un pack de anime con numeración **absoluta** (One Piece, capítulo 1085) sí pasa de 1000 sin
 *    estar codificado, y se leía como "T1 · E85".
 *
 * Lo que distingue de verdad es **de qué fuente viene la fila**, y eso son dos datos que ya están
 * guardados y que además el sync sí replica (a diferencia de `season`/`episode`, ver `SyncMappers`):
 * el prefijo del `itemId` (solo torrent y web codifican; archive.org usa su identificador pelado) y
 * la `section`, que esas mismas fuentes escriben como "Temporada N" exactamente cuando codifican.
 * Pedir las dos cosas es lo que deja afuera tanto a una subida de archive.org que guarde sus
 * archivos en una carpeta llamada "Temporada 1" como a los packs de numeración absoluta, que dejan
 * la sección vacía.
 *
 * La temporada se toma del texto de la sección y no de `orderIndex / 1000` porque es el dato
 * directo: para la temporada 0 las dos formas coinciden, pero una no depende de la aritmética.
 *
 * Esto es un **lector de datos viejos**. Las fuentes ya guardan `season`/`episode` en su propia
 * columna, así que las filas nuevas ni pasan por acá; sigue existiendo para lo que ya está en la
 * base y para lo que llegue por sync desde un dispositivo con una versión anterior.
 */
object NumeracionCodificada {

    private val SECCION_DE_TEMPORADA = Regex("""^Temporada (\d+)$""")

    /** Solo torrent y web codifican. archive.org (identificador pelado) y Magis, no. */
    private fun codifica(itemId: String) =
        itemId.startsWith("torrent:") || itemId.startsWith("web:")

    /**
     * (temporada, capítulo) si esta fila trae la numeración codificada en el [orderIndex], o null
     * si el [orderIndex] no significa eso y hay que tratarlo como lo que sea que sea para su fuente.
     */
    fun coordenadas(itemId: String, section: String, orderIndex: Int): Pair<Int, Int>? {
        if (!codifica(itemId)) return null
        val temporada = SECCION_DE_TEMPORADA.find(section)?.groupValues?.get(1)?.toIntOrNull()
            ?: return null
        return temporada to (orderIndex % 1000)
    }
}
