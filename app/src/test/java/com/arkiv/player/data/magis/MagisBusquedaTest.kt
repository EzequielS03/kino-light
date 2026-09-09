package com.arkiv.player.data.magis

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class MagisBusquedaTest {

    private fun item(nombre: String, tipo: String = "movie", extra: String = "") = JSONObject(
        """{"name":"$nombre","programType":"$tipo"${if (extra.isBlank()) "" else ",$extra"}}""",
    )

    @Test
    fun `al portal se le pide la cabeza del titulo, no el titulo entero`() {
        assertEquals("Avatar", consultaDePortal("Avatar: Aang, El ultimo Maestro Aire"))
        assertEquals("Spider-Man", consultaDePortal("Spider-Man: Un nuevo dia"))
        assertEquals("Dune", consultaDePortal("Dune"))
        assertEquals("Bajo el mismo techo", consultaDePortal("  Bajo el mismo techo  "))
    }

    @Test
    fun `una cabeza de una o dos letras no identifica nada, va el titulo entero`() {
        assertEquals("El: algo mas", consultaDePortal("El: algo mas"))
        assertEquals("A, lo que sea", consultaDePortal("A, lo que sea"))
    }

    @Test
    fun `los tokens ignoran tildes, puntuacion y palabras de dos letras`() {
        assertEquals(setOf("ultimo", "maestro", "aire"), tokensDeTitulo("El último, Maestro Aire"))
        assertEquals(setOf("corazon"), tokensDeTitulo("¿Corazón?"))
        assertEquals(emptySet<String>(), tokensDeTitulo(null))
    }

    @Test
    fun `gana el que mas palabras comparte con lo pedido`() {
        val items = listOf(
            item("El ultimo refugio"),
            item("Avatar: Aang, El ultimo Maestro Aire"),
            item("Amenaza en el aire"),
        )

        val ordenados = ordenarPorParecido(items, listOf("Avatar: Aang, El ultimo Maestro Aire"))

        assertEquals("Avatar: Aang, El ultimo Maestro Aire", tituloDeItem(ordenados.first()))
    }

    @Test
    fun `se puntua con la MEJOR forma del titulo, no con la suma`() {
        val items = listOf(
            item("Spider-Man: La serie animada"),
            item("Spider-Man: No Way Home"),
        )

        // El portal guarda el internacional con su titulo en ingles: sin la forma original, ambos
        // comparten solo "spider"/"man" y el orden del portal decidiria.
        val ordenados = ordenarPorParecido(
            items,
            listOf("Spider-Man: Sin camino a casa", "Spider-Man: No Way Home"),
        )

        assertEquals("Spider-Man: No Way Home", tituloDeItem(ordenados.first()))
    }

    @Test
    fun `sin titulos con los que comparar no se reordena nada`() {
        val items = listOf(item("B"), item("A"))

        assertEquals(items, ordenarPorParecido(items, listOf("", "de", "  ")))
    }

    @Test
    fun `la temporada se lee del nombre en los cuatro formatos`() {
        assertEquals(3, temporadaDeNombre("Dragon Ball T3"))
        assertEquals(2, temporadaDeNombre("Dragon Ball Temp.2"))
        assertEquals(4, temporadaDeNombre("Dragon Ball Temporada 4"))
        assertEquals(5, temporadaDeNombre("Dragon Ball S5"))
        // Sin sufijo es temporada unica, no "no se sabe".
        assertEquals(1, temporadaDeNombre("Dragon Ball"))
        assertEquals(1, temporadaDeNombre(null))
    }

    @Test
    fun `las temporadas de una serie quedan en orden y las peliculas no se corren`() {
        val items = listOf(
            item("Pelicula A"),
            item("Dragon Ball T4", tipo = "teleplay"),
            item("Dragon Ball T2", tipo = "teleplay"),
            item("Pelicula B"),
            item("Dragon Ball T1", tipo = "teleplay"),
        )

        val ordenados = ordenarTemporadas(items).map { tituloDeItem(it) }

        assertEquals(
            listOf("Pelicula A", "Dragon Ball T1", "Dragon Ball T2", "Pelicula B", "Dragon Ball T4"),
            ordenados,
        )
    }

    @Test
    fun `entre series distintas manda cual aparecio primero`() {
        val items = listOf(
            item("Naruto T2", tipo = "teleplay"),
            item("Bleach T1", tipo = "teleplay"),
            item("Naruto T1", tipo = "teleplay"),
        )

        val ordenados = ordenarTemporadas(items).map { tituloDeItem(it) }

        assertEquals(listOf("Naruto T1", "Naruto T2", "Bleach T1"), ordenados)
    }

    @Test
    fun `el titulo sale de name, viewPoint o alias, nunca de title`() {
        assertEquals("El nombre", tituloDeItem(JSONObject("""{"name":"El nombre","title":"otro"}""")))
        assertEquals("Por viewPoint", tituloDeItem(JSONObject("""{"viewPoint":"Por viewPoint"}""")))
        assertEquals("Por alias", tituloDeItem(JSONObject("""{"alias":"Por alias"}""")))
        assertEquals("", tituloDeItem(JSONObject("""{"title":"el portal no usa este campo"}""")))
    }

    @Test
    fun `el anio sale del releaseTime y la basura no se inventa`() {
        assertEquals("2024", anioDeItem(JSONObject("""{"releaseTime":"2024-05-01 00:00:00"}""")))
        assertEquals("", anioDeItem(JSONObject("""{"releaseTime":"sin fecha"}""")))
        assertEquals("", anioDeItem(JSONObject("{}")))
    }

    @Test
    fun `las imagenes se eligen por fileType y el tipo que falta no se emite`() {
        val item = JSONObject(
            """{"posterList":[
                {"fileType":"stage","fileUrl":"https://x/stage.jpg"},
                {"fileType":"icon","fileUrl":"https://x/icon.jpg"},
                {"fileType":"poster","fileUrl":"https://x/poster.jpg"},
                {"fileType":"icon","fileUrl":"https://x/icon2.jpg"}
            ]}""",
        )

        assertEquals(
            mapOf("poster" to "https://x/icon.jpg", "backdrop" to "https://x/poster.jpg"),
            imagenesDeItem(item),
        )
        assertEquals(emptyMap<String, String>(), imagenesDeItem(JSONObject("{}")))
    }

    @Test
    fun `la busqueda se aplana en las tres formas que usa el portal`() {
        val porGrupos = JSONObject(
            """{"searchItemList":[{"itemList":[{"name":"A"}]},{"itemList":[{"name":"B"}]}]}""",
        )
        assertEquals(listOf("A", "B"), itemsDeBusqueda(porGrupos).map { it.optString("name") })

        val porAssetList = JSONObject("""{"assetList":[{"name":"C"}]}""")
        assertEquals(listOf("C"), itemsDeBusqueda(porAssetList).map { it.optString("name") })

        val porList = JSONObject("""{"list":[{"name":"D"}]}""")
        assertEquals(listOf("D"), itemsDeBusqueda(porList).map { it.optString("name") })

        assertEquals(emptyList<String>(), itemsDeBusqueda(JSONObject("""{"returnCode":"0"}""")).map { "" })
    }
}
