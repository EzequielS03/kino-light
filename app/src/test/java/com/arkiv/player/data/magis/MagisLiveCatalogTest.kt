package com.arkiv.player.data.magis

import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MagisLiveCatalogTest {

    private fun liveCatalog(
        fake: FakePortalClient,
        nowMs: () -> Long = { 0L },
    ): MagisLiveCatalog {
        val session = testSession(fake)
        return MagisLiveCatalog(MagisCatalog(fake, session), fake, session, nowMs)
    }

    private fun portalCategories() = MagisResult.Ok(
        JSONObject(
            """{"recommendList":[
                {"columnId":76182,"name":"ChannelList"},
                {"columnId":76183,"name":"Deportes"},
                {"columnId":76184,"name":"18+"},
                {"name":"sin columnId"}
            ]}""",
        ),
    )

    private fun portalChannels(vararg codes: String) = MagisResult.Ok(
        JSONObject(
            """{"channelList":[${codes.joinToString(",") { c ->
                """{"channelCode":"$c","name":"Canal $c","channelNumber":"7",
                    "posterList":[{"fileType":"poster","fileUrl":"https://p/$c.jpg"},
                                  {"fileType":"icon","fileUrl":"https://i/$c.png"}]}"""
            }}]}""",
        ),
    )

    @Test
    fun `ChannelList shows as Todos and 18+ doesn't come out unrequested`() = runTest {
        val fake = FakePortalClient()
        fake.queueResponse("getNextColumns", portalCategories())

        val cats = liveCatalog(fake).categories()

        assertEquals(listOf("Todos", "Deportes"), cats.map { it.name })
        assertEquals(listOf(76182, 76183), cats.map { it.id })
    }

    @Test
    fun `with includeAdults, the adult one comes out too`() = runTest {
        val fake = FakePortalClient()
        fake.queueResponse("getNextColumns", portalCategories())

        val cats = liveCatalog(fake).categories(includeAdults = true)

        assertEquals(listOf("Todos", "Deportes", "18+"), cats.map { it.name })
    }

    @Test
    fun `categories are requested with pageSize 200, not the default`() = runTest {
        val fake = FakePortalClient()
        fake.queueResponse("getNextColumns", portalCategories())

        liveCatalog(fake).categories()

        val (_, bean) = fake.calls.first { it.first == "getNextColumns" }
        assertEquals("masnew_live", bean["columnCode"])
        assertEquals(200, bean["pageSize"])
    }

    @Test
    fun `the logo comes from posterList's fileType icon entry, not the list's first one`() = runTest {
        val fake = FakePortalClient()
        fake.queueResponse("getNextColumns", portalCategories())
        fake.queueResponse("v6/getLiveData", portalChannels("A"))

        val channels = liveCatalog(fake).channels(76183)

        assertEquals("https://i/A.png", channels.single().logo)
        assertEquals("Canal A", channels.single().name)
        assertEquals(7, channels.single().number)
    }

    @Test
    fun `with no icon in posterList it falls back to the loose posterUrl`() = runTest {
        val fake = FakePortalClient()
        fake.queueResponse("getNextColumns", portalCategories())
        fake.queueResponse(
            "v6/getLiveData",
            MagisResult.Ok(
                JSONObject(
                    """{"channelList":[
                        {"channelCode":"B","name":"B","posterUrl":"https://suelta/b.png",
                         "posterList":[{"fileType":"poster","fileUrl":"https://p/b.jpg"}]},
                        {"channelCode":"C","name":"C"}
                    ]}""",
                ),
            ),
        )

        val channels = liveCatalog(fake).channels(76183)

        assertEquals("https://suelta/b.png", channels[0].logo)
        assertNull(channels[1].logo)
    }

    @Test
    fun `an adult category's channels end up marked one by one`() = runTest {
        val fake = FakePortalClient()
        fake.queueResponse("getNextColumns", portalCategories())
        fake.queueResponse("v6/getLiveData", portalChannels("X"))

        val channels = liveCatalog(fake).channels(76184)

        assertTrue(channels.single().adult)
    }

    @Test
    fun `a normal category doesn't mark its channels`() = runTest {
        val fake = FakePortalClient()
        fake.queueResponse("getNextColumns", portalCategories())
        fake.queueResponse("v6/getLiveData", portalChannels("X"))

        assertTrue(!liveCatalog(fake).channels(76183).single().adult)
    }

    @Test
    fun `pages until the portal returns an incomplete page`() = runTest {
        val fake = FakePortalClient()
        fake.queueResponse("getNextColumns", portalCategories())
        fake.queueResponse("v6/getLiveData", portalChannels(*(1..500).map { "p1-$it" }.toTypedArray()))
        fake.queueResponse("v6/getLiveData", portalChannels(*(1..40).map { "p2-$it" }.toTypedArray()))

        val channels = liveCatalog(fake).channels(76183)

        assertEquals(540, channels.size)
        assertEquals(2, fake.timesCalled("v6/getLiveData"))
        assertEquals(listOf(1, 2), fake.calls.filter { it.first == "v6/getLiveData" }.map { it.second["pageNum"] })
    }

    @Test
    fun `if the portal ignored pageNum the catalog wouldn't get duplicated`() = runTest {
        val fake = FakePortalClient()
        fake.queueResponse("getNextColumns", portalCategories())
        val page = portalChannels(*(1..500).map { "rep-$it" }.toTypedArray())
        fake.queueResponse("v6/getLiveData", page)
        fake.queueResponse("v6/getLiveData", page)

        val channels = liveCatalog(fake).channels(76183)

        assertEquals(500, channels.size)
        assertEquals(2, fake.timesCalled("v6/getLiveData"))
    }

    @Test
    fun `the catalog gets cached and doesn't go back to the portal until it expires`() = runTest {
        val fake = FakePortalClient()
        fake.queueResponse("getNextColumns", portalCategories())
        fake.queueResponse("v6/getLiveData", portalChannels("A"))
        var now = 0L
        val catalog = liveCatalog(fake) { now }

        catalog.channels(76183)
        catalog.channels(76183)
        catalog.categories()

        assertEquals(1, fake.timesCalled("v6/getLiveData"))
        assertEquals(1, fake.timesCalled("getNextColumns"))

        // Past 6h it asks again.
        now = 7 * 60 * 60 * 1000L
        fake.queueResponse("getNextColumns", portalCategories())
        fake.queueResponse("v6/getLiveData", portalChannels("A"))
        catalog.channels(76183)

        assertEquals(2, fake.timesCalled("v6/getLiveData"))
    }

    @Test
    fun `a portal error doesn't get cached as an empty catalog`() = runTest {
        val fake = FakePortalClient()
        // Just one: a RedError doesn't trigger a retry (the portal said nothing, it's down).
        fake.queueResponse("getNextColumns", MagisResult.RedError(java.io.IOException("sin red")))
        val catalog = liveCatalog(fake)

        assertTrue(catalog.categories().isEmpty())
        fake.queueResponse("getNextColumns", portalCategories())

        assertEquals(listOf("Todos", "Deportes"), catalog.categories().map { it.name })
    }

    // --- catalog tree (sections with their first items) --------------------------------

    private fun portalTree() = MagisResult.Ok(
        JSONObject(
            """{"recommendList":[
                {"columnId":91,"name":"Estrenos","assetList":[
                    {"contentId":"P1","name":"Una pelicula","programType":"movie","duration":"5400",
                     "posterList":[{"fileType":"icon","fileUrl":"https://i/p1.jpg"}]},
                    {"contentId":"S1","name":"Una serie","programType":"teleplay"},
                    {"name":"sin contentId"}
                ]},
                {"columnId":92,"name":"Recomendadas","assetList":[]},
                {"columnId":93,"assetList":[]}
            ]}""",
        ),
    )

    @Test
    fun `the tree carries the sections with their items and each one's ref`() = runTest {
        val fake = FakePortalClient()
        fake.queueResponse("getNextColumns", portalTree())

        val sections = liveCatalog(fake).tree("peliculas")

        // The unnamed section is discarded: an empty header can't be drawn.
        assertEquals(listOf("Estrenos", "Recomendadas"), sections.map { it.name })
        assertEquals(listOf(91, 92), sections.map { it.id })
        val items = sections.first().items
        assertEquals(listOf("Una pelicula", "Una serie"), items.map { it.title })
        assertEquals("https://i/p1.jpg", items[0].poster)
        assertEquals(5400, items[0].durationS)
        assertEquals(MagisRef("P1", "movie", 0), MagisRef.decode(items[0].ref))
        assertTrue(items[0].playable)
        // The type lets it branch without opening the ref: a series asks for its chapters first.
        assertTrue(items[1].isSeries)
        assertEquals(MagisRef("S1", "teleplay", 0), MagisRef.decode(items[1].ref))
    }

    @Test
    fun `each root has its own code and the obvious ones aren't used`() = runTest {
        val fake = FakePortalClient()
        fake.defaultResponse = portalTree()
        val catalog = liveCatalog(fake)

        listOf("peliculas" to "masnew_movies", "series" to "masnew_series",
               "infantil" to "masnew_kids", "anime" to "masnew_anime").forEach { (root, code) ->
            catalog.tree(root)
            assertEquals(code, fake.calls.last { it.first == "getNextColumns" }.second["columnCode"])
        }
    }

    @Test
    fun `a root that doesn't exist isn't requested from the portal`() = runTest {
        val fake = FakePortalClient()

        val e = runCatching { liveCatalog(fake).tree("lo-que-sea") }.exceptionOrNull()

        assertTrue("esperaba un error de argumento y fue $e", e is IllegalArgumentException)
        assertTrue(fake.calls.isEmpty())
    }

    @Test
    fun `the adult section has to be requested explicitly`() = runTest {
        val fake = FakePortalClient()
        fake.defaultResponse = portalTree()
        val catalog = liveCatalog(fake)

        val e = runCatching { catalog.tree("adultos") }.exceptionOrNull()
        assertTrue("esperaba que se niegue y fue $e", e is IllegalArgumentException)
        assertTrue(fake.calls.isEmpty())

        val sections = catalog.tree("adultos", includeAdults = true)
        assertTrue("los items tienen que quedar marcados", sections.first().items.all { it.adult })
        assertTrue(sections.all { it.adult })
    }

    @Test
    fun `the tree gets cached per root`() = runTest {
        val fake = FakePortalClient()
        fake.defaultResponse = portalTree()
        val catalog = liveCatalog(fake)

        catalog.tree("peliculas")
        catalog.tree("peliculas")
        catalog.tree("series")

        assertEquals(2, fake.timesCalled("getNextColumns"))
    }

    @Test
    fun `the portal has no EPG and it says so, without making up schedules`() = runTest {
        val fake = FakePortalClient()

        val (guide, missing) = liveCatalog(fake).epg(listOf("c1", "c2"))

        assertTrue(guide.isEmpty())
        assertEquals(listOf("c1", "c2"), missing)
        assertTrue(fake.calls.isEmpty())
    }
}
