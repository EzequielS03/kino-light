package com.arkiv.player.data

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Búsqueda "Get Backers": el ítem completo (49 eps) salía enterrado en #5, debajo de un
 * fragmento de 1 episodio ("Capítulo # 01"). El ranking por # de episodios lo sube al tope.
 */
class ArchiveSearchRankTest {

    private fun r(id: String, eps: Int) =
        ArchiveSearchResult(identifier = id, title = id, year = "", episodeCount = eps)

    @Test
    fun `ordena por numero de episodios descendente para que la serie completa gane`() {
        // Orden tal como lo devuelve archive.org por relevancia (el fragmento arriba del completo).
        val input = listOf(
            r("brazil", 49),
            r("capitulo-01", 1),        // fragmento que salía por encima
            r("get-backers-05", 49),    // el completo que el usuario tuvo que agregar a mano
            r("vol-1", 12),
        )
        val ranked = ArchiveApi.rankByEpisodeCount(input)
        assertEquals(
            listOf("brazil", "get-backers-05", "vol-1", "capitulo-01"),
            ranked.map { it.identifier },
        )
    }

    @Test
    fun `los empates conservan el orden original de relevancia`() {
        val input = listOf(r("a", 49), r("b", 49), r("c", 1))
        assertEquals(listOf("a", "b", "c"), ArchiveApi.rankByEpisodeCount(input).map { it.identifier })
    }
}
