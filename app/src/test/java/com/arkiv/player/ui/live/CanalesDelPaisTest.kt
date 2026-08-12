package com.arkiv.player.ui.live

import com.arkiv.player.data.gateway.LiveChannel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CanalesDelPaisTest {

    private fun canal(code: String, nombre: String = code) =
        LiveChannel(code = code, nombre = nombre, numero = 0, logo = null)

    // --- detección de país ---

    @Test
    fun `la SIM manda sobre la zona horaria y el idioma`() {
        assertEquals("CO", paisDesdeSenales(sim = "co", regionZonaHoraria = "US", localeCountry = "ES"))
    }

    @Test
    fun `sin SIM manda la zona horaria, no el idioma`() {
        // El caso del Fire TV: idioma en inglés de fábrica, zona horaria puesta en Bogotá.
        assertEquals("CO", paisDesdeSenales(sim = null, regionZonaHoraria = "CO", localeCountry = "US"))
        assertEquals("CO", paisDesdeSenales(sim = "", regionZonaHoraria = "CO", localeCountry = "US"))
    }

    @Test
    fun `el idioma es el ultimo recurso`() {
        assertEquals("MX", paisDesdeSenales(sim = null, regionZonaHoraria = null, localeCountry = "mx"))
    }

    @Test
    fun `descarta regiones que no son un pais`() {
        // ICU devuelve "001" (mundo) o "419" (Latinoamérica) para zonas genéricas tipo Etc/UTC.
        assertEquals("ES", paisDesdeSenales(sim = null, regionZonaHoraria = "001", localeCountry = "ES"))
        assertEquals("ES", paisDesdeSenales(sim = null, regionZonaHoraria = "419", localeCountry = "ES"))
        assertNull(paisDesdeSenales(sim = null, regionZonaHoraria = "001", localeCountry = ""))
    }

    @Test
    fun `sin ninguna senal util no hay pais`() {
        assertNull(paisDesdeSenales(sim = null, regionZonaHoraria = null, localeCountry = null))
    }

    // --- mapeo país → categoría del portal ---

    @Test
    fun `los paises del portal mapean a su categoria con el nombre exacto`() {
        assertEquals("Colombia", CATEGORIAS_POR_PAIS["CO"])
        // Con tilde, tal cual lo devuelve /v1/live/categories: el cruce es por nombre.
        assertEquals("México", CATEGORIAS_POR_PAIS["MX"])
        assertEquals("Perú", CATEGORIAS_POR_PAIS["PE"])
    }

    @Test
    fun `un pais sin categoria propia no inventa una`() {
        // Brasil y Argentina no están entre las categorías del portal.
        assertNull(CATEGORIAS_POR_PAIS["BR"])
        assertNull(CATEGORIAS_POR_PAIS["AR"])
    }

    // --- orden y deduplicación de la fila ---

    @Test
    fun `los recientes van primero y en su orden`() {
        val fila = filaDeCanalesDelHome(
            recientes = listOf(canal("c"), canal("b"), canal("a")),
            delPais = listOf(canal("x"), canal("y")),
        )
        assertEquals(listOf("c", "b", "a", "x", "y"), fila.map { it.code })
    }

    @Test
    fun `un canal reciente no se repite entre los del pais`() {
        // El caso concreto: Caracol está visto Y está en la lista de Colombia.
        val fila = filaDeCanalesDelHome(
            recientes = listOf(canal("caracol", "Caracol"), canal("rcn", "RCN")),
            delPais = listOf(canal("citytv", "City TV"), canal("caracol", "Caracol HD"), canal("win", "Win")),
        )
        assertEquals(listOf("caracol", "rcn", "citytv", "win"), fila.map { it.code })
        assertEquals(1, fila.count { it.code == "caracol" })
        // Y gana la versión de los recientes, con el nombre con el que se vio.
        assertEquals("Caracol", fila.first { it.code == "caracol" }.nombre)
    }

    @Test
    fun `el limite corta el total de la fila, no cada parte`() {
        val fila = filaDeCanalesDelHome(
            recientes = (1..3).map { canal("r$it") },
            delPais = (1..50).map { canal("p$it") },
            limite = 10,
        )
        assertEquals(10, fila.size)
        assertEquals(listOf("r1", "r2", "r3", "p1"), fila.map { it.code }.take(4))
    }

    @Test
    fun `sin canales del pais la fila es solo los recientes`() {
        val fila = filaDeCanalesDelHome(listOf(canal("a")), emptyList())
        assertEquals(listOf("a"), fila.map { it.code })
    }

    @Test
    fun `sin recientes la fila es solo los del pais`() {
        val fila = filaDeCanalesDelHome(emptyList(), listOf(canal("x"), canal("y")))
        assertEquals(listOf("x", "y"), fila.map { it.code })
    }

    @Test
    fun `sin nada la fila queda vacia`() {
        assertTrue(filaDeCanalesDelHome(emptyList(), emptyList()).isEmpty())
    }

    @Test
    fun `un code repetido dentro de una misma lista tampoco se duplica`() {
        val fila = filaDeCanalesDelHome(
            recientes = listOf(canal("a"), canal("a")),
            delPais = listOf(canal("b"), canal("b")),
        )
        assertEquals(listOf("a", "b"), fila.map { it.code })
    }
}
