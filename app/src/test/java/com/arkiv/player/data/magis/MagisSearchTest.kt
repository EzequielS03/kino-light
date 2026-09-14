package com.arkiv.player.data.magis

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class MagisSearchTest {

    private fun item(name: String, type: String = "movie", extra: String = "") = JSONObject(
        """{"name":"$name","programType":"$type"${if (extra.isBlank()) "" else ",$extra"}}""",
    )

    @Test
    fun `the portal is asked for the title's head, not the whole title`() {
        assertEquals("Avatar", portalQuery("Avatar: Aang, El ultimo Maestro Aire"))
        assertEquals("Spider-Man", portalQuery("Spider-Man: Un nuevo dia"))
        assertEquals("Dune", portalQuery("Dune"))
        assertEquals("Bajo el mismo techo", portalQuery("  Bajo el mismo techo  "))
    }

    @Test
    fun `a one- or two-letter head identifies nothing, the whole title goes`() {
        assertEquals("El: algo mas", portalQuery("El: algo mas"))
        assertEquals("A, lo que sea", portalQuery("A, lo que sea"))
    }

    @Test
    fun `the tokens ignore accents, punctuation and two-letter words`() {
        assertEquals(setOf("ultimo", "maestro", "aire"), titleTokens("El último, Maestro Aire"))
        assertEquals(setOf("corazon"), titleTokens("¿Corazón?"))
        assertEquals(emptySet<String>(), titleTokens(null))
    }

    @Test
    fun `whoever shares the most words with what was asked wins`() {
        val items = listOf(
            item("El ultimo refugio"),
            item("Avatar: Aang, El ultimo Maestro Aire"),
            item("Amenaza en el aire"),
        )

        val sorted = sortBySimilarity(items, listOf("Avatar: Aang, El ultimo Maestro Aire"))

        assertEquals("Avatar: Aang, El ultimo Maestro Aire", itemTitle(sorted.first()))
    }

    @Test
    fun `it's scored with the BEST form of the title, not the sum`() {
        val items = listOf(
            item("Spider-Man: La serie animada"),
            item("Spider-Man: No Way Home"),
        )

        // The portal keeps the international one under its English title: without the original
        // form, both share only "spider"/"man" and the portal's order would decide.
        val sorted = sortBySimilarity(
            items,
            listOf("Spider-Man: Sin camino a casa", "Spider-Man: No Way Home"),
        )

        assertEquals("Spider-Man: No Way Home", itemTitle(sorted.first()))
    }

    @Test
    fun `with no titles to compare against, nothing gets reordered`() {
        val items = listOf(item("B"), item("A"))

        assertEquals(items, sortBySimilarity(items, listOf("", "de", "  ")))
    }

    @Test
    fun `the season is read from the name in all four formats`() {
        assertEquals(3, seasonFromName("Dragon Ball T3"))
        assertEquals(2, seasonFromName("Dragon Ball Temp.2"))
        assertEquals(4, seasonFromName("Dragon Ball Temporada 4"))
        assertEquals(5, seasonFromName("Dragon Ball S5"))
        // No suffix is a single season, not "unknown".
        assertEquals(1, seasonFromName("Dragon Ball"))
        assertEquals(1, seasonFromName(null))
    }

    @Test
    fun `a series' seasons end up in order and movies don't shift`() {
        val items = listOf(
            item("Pelicula A"),
            item("Dragon Ball T4", type = "teleplay"),
            item("Dragon Ball T2", type = "teleplay"),
            item("Pelicula B"),
            item("Dragon Ball T1", type = "teleplay"),
        )

        val sorted = sortSeasons(items).map { itemTitle(it) }

        assertEquals(
            listOf("Pelicula A", "Dragon Ball T1", "Dragon Ball T2", "Pelicula B", "Dragon Ball T4"),
            sorted,
        )
    }

    @Test
    fun `between different series, whichever appeared first wins`() {
        val items = listOf(
            item("Naruto T2", type = "teleplay"),
            item("Bleach T1", type = "teleplay"),
            item("Naruto T1", type = "teleplay"),
        )

        val sorted = sortSeasons(items).map { itemTitle(it) }

        assertEquals(listOf("Naruto T1", "Naruto T2", "Bleach T1"), sorted)
    }

    @Test
    fun `the title comes from name, viewPoint or alias, never from title`() {
        assertEquals("El nombre", itemTitle(JSONObject("""{"name":"El nombre","title":"otro"}""")))
        assertEquals("Por viewPoint", itemTitle(JSONObject("""{"viewPoint":"Por viewPoint"}""")))
        assertEquals("Por alias", itemTitle(JSONObject("""{"alias":"Por alias"}""")))
        assertEquals("", itemTitle(JSONObject("""{"title":"el portal no usa este campo"}""")))
    }

    @Test
    fun `the year comes from releaseTime and garbage doesn't get made up`() {
        assertEquals("2024", itemYear(JSONObject("""{"releaseTime":"2024-05-01 00:00:00"}""")))
        assertEquals("", itemYear(JSONObject("""{"releaseTime":"sin fecha"}""")))
        assertEquals("", itemYear(JSONObject("{}")))
    }

    @Test
    fun `images are chosen by fileType and a missing type isn't emitted`() {
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
            itemImages(item),
        )
        assertEquals(emptyMap<String, String>(), itemImages(JSONObject("{}")))
    }

    @Test
    fun `search flattens into the three shapes the portal uses`() {
        val byGroups = JSONObject(
            """{"searchItemList":[{"itemList":[{"name":"A"}]},{"itemList":[{"name":"B"}]}]}""",
        )
        assertEquals(listOf("A", "B"), searchItems(byGroups).map { it.optString("name") })

        val byAssetList = JSONObject("""{"assetList":[{"name":"C"}]}""")
        assertEquals(listOf("C"), searchItems(byAssetList).map { it.optString("name") })

        val byList = JSONObject("""{"list":[{"name":"D"}]}""")
        assertEquals(listOf("D"), searchItems(byList).map { it.optString("name") })

        assertEquals(emptyList<String>(), searchItems(JSONObject("""{"returnCode":"0"}""")).map { "" })
    }
}
