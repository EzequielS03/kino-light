package com.arkiv.player.data

import com.arkiv.player.data.db.ArtworkEntity
import com.arkiv.player.data.db.LibraryRow
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Agrupación de la biblioteca: la MISMA serie entrada por varias fuentes tiene que dar UNA tarjeta.
 * Los casos son los medidos en la DB real del Fire TV el 2026-08-10 (ver el plan).
 */
class LibraryGroupingTest {

    private fun row(
        id: String,
        title: String,
        eps: Int,
        source: String = "web",
        category: String? = "series",
        addedAt: Long = 0L,
    ) = LibraryRow(
        identifier = id,
        title = title,
        description = null,
        thumbnailUrl = "",
        episodeCount = eps,
        durationSeconds = 0.0,
        addedAt = addedAt,
        categoryOverride = category,
        source = source,
    )

    private fun art(id: String, tmdbId: Int?, type: String?) =
        ArtworkEntity(itemId = id, tmdbId = tmdbId, tmdbType = type, backdropsJson = "[]", fetchedAt = 0L)

    @Test
    fun `una serie con tmdbId de tv resuelto agrupa por ese id`() {
        val a = row("web:series:tt30217403", "DAN DA DAN", 24)
        val b = row("web:series:anilist171018", "DAN DA DAN", 1)
        val artwork = mapOf(
            a.identifier to art(a.identifier, 240411, "tv"),
            b.identifier to art(b.identifier, 240411, "tv"),
        )
        assertEquals("tv:240411", LibraryGrouping.groupKeyOf(a, artwork[a.identifier]))
        assertEquals("tv:240411", LibraryGrouping.groupKeyOf(b, artwork[b.identifier]))
    }

    /** El resolver de artwork confunde películas distintas (Lego Batman vs Batman 1966): nunca agrupar. */
    @Test
    fun `las peliculas nunca se agrupan aunque compartan tmdbId`() {
        val a = row("torrent:aaa", "Lego Batman: la película", 1, source = "torrent", category = null)
        val b = row("torrent:bbb", "Batman: La película (1966)", 1, source = "torrent", category = null)
        val artA = art(a.identifier, 324849, "movie")
        val artB = art(b.identifier, 324849, "movie")
        assertEquals("item:torrent:aaa", LibraryGrouping.groupKeyOf(a, artA))
        assertEquals("item:torrent:bbb", LibraryGrouping.groupKeyOf(b, artB))
    }

    /** Sin artwork resuelto se cae al seriesId del identifier, que es exacto. */
    @Test
    fun `sin tmdbId cae al seriesId del identifier`() {
        val r = row("web:series:tt0409591", "Naruto", 300)
        assertEquals("series:tt0409591", LibraryGrouping.groupKeyOf(r, art(r.identifier, null, null)))
        assertEquals("series:tt0409591", LibraryGrouping.groupKeyOf(r, null))
    }

    /** Sin nada de lo anterior, el ítem es su propio grupo (comportamiento de hoy). */
    @Test
    fun `sin tmdbId ni seriesId el item queda solo`() {
        val r = row("alfa:series:jkanime:3f7d7ee2", "Naruto", 1, source = "alfa")
        assertEquals("item:alfa:series:jkanime:3f7d7ee2", LibraryGrouping.groupKeyOf(r, null))
    }

    /** Series distintas con el mismo título no se fusionan: Ranma 1989 y el remake de 2024. */
    @Test
    fun `dos series homonimas con tmdbId distinto quedan separadas`() {
        val vieja = row("web:series:tt0096686", "Ranma ½", 161)
        val nueva = row("web:series:tt32766897", "Ranma1/2", 24)
        val grupos = LibraryGrouping.group(
            listOf(vieja, nueva),
            mapOf(
                vieja.identifier to art(vieja.identifier, 33840, "tv"),
                nueva.identifier to art(nueva.identifier, 240909, "tv"),
            ),
        )
        assertEquals(2, grupos.size)
    }

    @Test
    fun `el representante del grupo es el de mas capitulos`() {
        val pocos = row("web:series:anilist171018", "DAN DA DAN", 1, addedAt = 200)
        val muchos = row("web:series:tt30217403", "DAN DA DAN", 24, addedAt = 100)
        val grupos = LibraryGrouping.group(
            listOf(pocos, muchos),
            mapOf(
                pocos.identifier to art(pocos.identifier, 240411, "tv"),
                muchos.identifier to art(muchos.identifier, 240411, "tv"),
            ),
        )
        assertEquals(1, grupos.size)
        assertEquals("web:series:tt30217403", grupos[0].primary.identifier)
        assertEquals(2, grupos[0].sourceCount)
        assertEquals(25, grupos[0].episodeCount)
    }

    /** El orden del home es por lo más reciente del grupo, para que agrupar no reordene la fila. */
    @Test
    fun `los grupos salen ordenados por el miembro mas reciente`() {
        val viejo = row("web:series:tt1", "Vieja", 10, addedAt = 100)
        val nuevo = row("web:series:tt2", "Nueva", 10, addedAt = 300)
        val grupos = LibraryGrouping.group(listOf(viejo, nuevo), emptyMap())
        assertEquals(listOf("Nueva", "Vieja"), grupos.map { it.primary.title })
    }

    /**
     * El combine de los dos flows: si el arte llega DESPUÉS que la biblioteca (que es lo normal —
     * `ensureArtwork` sale a la red), el grupo tiene que recalcularse solo. Si no, el home se
     * queda con las tarjetas separadas hasta reabrir la app.
     */
    @Test
    fun `los grupos se recalculan cuando llega el artwork`() = kotlinx.coroutines.runBlocking {
        val a = row("web:series:tt30217403", "DAN DA DAN", 24)
        val b = row("web:series:anilist171018", "DAN DA DAN", 1)
        val artwork = kotlinx.coroutines.flow.MutableStateFlow<Map<String, ArtworkEntity>>(emptyMap())
        val flow = LibraryGrouping.groupsFlow(
            kotlinx.coroutines.flow.flowOf(listOf(a, b)),
            artwork,
        )
        val emissions = mutableListOf<List<LibraryGroup>>()
        val job = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined).launch {
            flow.collect { emissions += it }
        }
        assertEquals(2, emissions.last().size)
        artwork.value = mapOf(
            a.identifier to art(a.identifier, 240411, "tv"),
            b.identifier to art(b.identifier, 240411, "tv"),
        )
        assertEquals(1, emissions.last().size)
        job.cancel()
    }
}
