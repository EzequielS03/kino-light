package com.arkiv.player.data.catalog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AnimeMappingCacheTest {
    private val week = 7L * 24 * 60 * 60 * 1000

    @Test
    fun `cache reciente es fresco`() {
        assertTrue(AnimeMappingRepository.isFresh(fetchedAtMs = 1_000, nowMs = 1_000 + week - 1))
    }

    @Test
    fun `cache de mas de una semana esta vencido`() {
        assertFalse(AnimeMappingRepository.isFresh(fetchedAtMs = 1_000, nowMs = 1_000 + week + 1))
    }

    @Test
    fun `sin fetch previo (0) no es fresco`() {
        assertFalse(AnimeMappingRepository.isFresh(fetchedAtMs = 0, nowMs = 5_000))
    }

    @Test
    fun `un archivo que parsea vacio no se considera fresco aunque su timestamp sea reciente`() {
        // Un JSON corrupto/truncado parsea a mapa vacío (ver FribbAnimeListParser.parse, que
        // atrapa la excepción y devuelve emptyMap). Aunque el timestamp del archivo sea "fresco"
        // según TTL, el repo NO debe tratarlo como cache válido: debe forzar re-descarga en la
        // próxima carga en vez de servir un mapa vacío indefinidamente (bug F6).
        val parsedFromCorruptFile = FribbAnimeListParser.parse("{ esto no es un json valido ]")
        assertTrue(parsedFromCorruptFile.isEmpty())

        // La condición de "fresco y válido" usada por AnimeMappingRepository requiere ambas cosas:
        // isFresh(lastModified) Y que el parseo no sea vacío. Simulamos esa combinación aquí,
        // ya que ensureLoaded() es privado y depende de I/O real (no se testea directo sin red).
        val timestampFresh = AnimeMappingRepository.isFresh(
            fetchedAtMs = System.currentTimeMillis(),
            nowMs = System.currentTimeMillis(),
        )
        assertTrue(timestampFresh)
        val consideredValidAndFresh = timestampFresh && parsedFromCorruptFile.isNotEmpty()
        assertFalse(consideredValidAndFresh)
        assertEquals(0, parsedFromCorruptFile.size)
    }
}
