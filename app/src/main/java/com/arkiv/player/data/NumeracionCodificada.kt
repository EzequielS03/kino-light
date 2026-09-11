package com.arkiv.player.data

/**
 * How to read the numbering that some sources used to pack INSIDE `orderIndex`.
 *
 * The torrent and web sources (removed in this branch's pruning, along with the functions that
 * used to save chapters this way) saved their chapters with `orderIndex = season*1000 + episode`,
 * while archive.org (also removed) used it as a plain 0..N-1 correlative. Both numbers share the
 * same column, and something has to tell them apart for the rows still saved from before that.
 *
 * **Why just looking at the number isn't enough.** The old rule was "if `orderIndex >= 1000` it's
 * encoded." It breaks on both sides:
 *  - A **season 0** (specials) gives `0*1000 + 3 = 3`, indistinguishable from an archive
 *    correlative: special 3 showed up as "E4".
 *  - An anime pack with **absolute** numbering (One Piece, chapter 1085) does go past 1000
 *    without being encoded, and was read as "S1 · E85".
 *
 * What actually tells them apart is **which source the row came from**, and that's two pieces of
 * data already saved on the row itself: the `itemId` prefix (only torrent and web ever encoded;
 * archive.org used its bare identifier) and the `section`, which those same sources wrote as
 * "Temporada N" exactly when they encoded. Asking for both is what rules out an archive.org
 * upload that saved its files in a folder called "Temporada 1", as well as the absolute-numbering
 * packs, which leave the section empty.
 *
 * The season comes from the section's text and not from `orderIndex / 1000` because it's the
 * direct value: for season 0 the two forms agree, but one doesn't depend on the arithmetic.
 *
 * This is a **reader for old data**. Sources save `season`/`episode` in their own column now, so
 * new rows never go through here; it still exists for whatever is already saved from before this
 * branch's torrent/web/archive.org pruning.
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
