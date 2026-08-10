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

    // --- resolveMembers ------------------------------------------------------------------

    /** (i) Una llave de grupo viva devuelve sus miembros, del más completo al menos. */
    @Test
    fun `resolveMembers con una llave de grupo viva devuelve sus miembros, mas completo primero`() {
        val pocos = row("web:series:anilist171018", "DAN DA DAN", 1)
        val muchos = row("web:series:tt30217403", "DAN DA DAN", 24)
        val groups = LibraryGrouping.group(
            listOf(pocos, muchos),
            mapOf(
                pocos.identifier to art(pocos.identifier, 240411, "tv"),
                muchos.identifier to art(muchos.identifier, 240411, "tv"),
            ),
        )
        val result = LibraryGrouping.resolveMembers("tv:240411", groups, groups.flatMap { it.members })
        assertEquals(listOf(muchos.identifier, pocos.identifier), result.map { it.identifier })
    }

    /**
     * (ii) LA REGRESIÓN de Finding 2: una llave `item:<identifier>` que el detalle abrió cuando
     * el ítem todavía no tenía tmdbId. Si el arte resuelve DESPUÉS (mientras el detalle sigue
     * abierto), el ítem se muda a un grupo `tv:`, la llave `item:` deja de existir, y sin este
     * fallback `observeGroupMembers` devolvía vacío -> pantalla negra en el detalle.
     */
    @Test
    fun `resolveMembers con una llave item cuyo item se sumo a un grupo tv resuelve a ese grupo`() {
        val naruto = row("torrent:abc123", "Naruto — Pack", 220, source = "torrent")
        // Llave que tenía el ítem al momento de navegar: sin tmdbId todavía.
        val groupKeyDeLaRuta = LibraryGrouping.groupKeyOf(naruto, null)
        assertEquals("item:torrent:abc123", groupKeyDeLaRuta)

        // El arte resuelve DESPUÉS: ahora el ítem (y un hermano de otra fuente) viven en tv:46260.
        val otraFuente = row("web:series:tt0409591", "Naruto", 300)
        val groups = LibraryGrouping.group(
            listOf(naruto, otraFuente),
            mapOf(
                naruto.identifier to art(naruto.identifier, 46260, "tv"),
                otraFuente.identifier to art(otraFuente.identifier, 46260, "tv"),
            ),
        )
        assertEquals(emptyList<LibraryGroup>(), groups.filter { it.key == groupKeyDeLaRuta }) // la llave vieja ya no existe

        val result = LibraryGrouping.resolveMembers(groupKeyDeLaRuta, groups, groups.flatMap { it.members })
        assertEquals(setOf(naruto.identifier, otraFuente.identifier), result.map { it.identifier }.toSet())
    }

    /**
     * (iii) La MISMA regresión que (ii) pero para una llave `series:<seriesId>` (Finding del
     * closeout 2026-08-10): a diferencia de `item:<identifier>`, un `series:` nunca es igual a
     * ningún identifier (los identifiers van prefijados `web:series:`/`torrent:series:`), así que
     * el paso 3 de antes (`rows.filter { it.identifier == groupKey }`) jamás la encontraba. Sin
     * este fallback, TvHomeScreen navega con `series:tt...`, el arte resuelve mientras el detalle
     * sigue abierto, la llave `series:` deja de existir y el detalle queda en pantalla negra.
     */
    @Test
    fun `resolveMembers con una llave series cuyo item se sumo a un grupo tv resuelve a ese grupo`() {
        val pocos = row("web:series:tt30217403", "DAN DA DAN", 24)
        // Llave que tenía el ítem al momento de navegar: sin tmdbId todavía.
        val groupKeyDeLaRuta = LibraryGrouping.groupKeyOf(pocos, null)
        assertEquals("series:tt30217403", groupKeyDeLaRuta)

        // El arte resuelve DESPUÉS: ahora el ítem (y un hermano de otra fuente) viven en tv:240411.
        val muchos = row("torrent:series:tt30217403", "DAN DA DAN — Pack", 25, source = "torrent")
        val groups = LibraryGrouping.group(
            listOf(pocos, muchos),
            mapOf(
                pocos.identifier to art(pocos.identifier, 240411, "tv"),
                muchos.identifier to art(muchos.identifier, 240411, "tv"),
            ),
        )
        assertEquals(emptyList<LibraryGroup>(), groups.filter { it.key == groupKeyDeLaRuta }) // la llave vieja ya no existe

        val result = LibraryGrouping.resolveMembers(groupKeyDeLaRuta, groups, groups.flatMap { it.members })
        assertEquals(setOf(pocos.identifier, muchos.identifier), result.map { it.identifier }.toSet())
    }

    /** (iv) Un identifier crudo (Continuar viendo / menú de mantener presionado) resuelve a esa sola fila. */
    @Test
    fun `resolveMembers con un identifier crudo resuelve a esa sola fila`() {
        val suelto = row("torrent:xyz789", "Alguna película", 1, category = null)
        val groups = LibraryGrouping.group(listOf(suelto), emptyMap())
        val result = LibraryGrouping.resolveMembers("torrent:xyz789", groups, groups.flatMap { it.members })
        assertEquals(listOf("torrent:xyz789"), result.map { it.identifier })
    }

    /** (v) Una llave que no matchea nada (ni grupo ni fila) devuelve vacío. */
    @Test
    fun `resolveMembers con una llave desconocida devuelve vacio`() {
        val r = row("web:series:tt1", "Algo", 5)
        val groups = LibraryGrouping.group(listOf(r), emptyMap())
        val result = LibraryGrouping.resolveMembers("tv:99999999", groups, groups.flatMap { it.members })
        assertEquals(emptyList<LibraryRow>(), result)
    }

    // --- shouldRefetchArtwork --------------------------------------------------------------

    private val sevenDaysMs = 7 * 24 * 60 * 60 * 1000L

    /** Ya resuelto (tmdbId != null): nunca se reintenta, sin importar la antigüedad. */
    @Test
    fun `shouldRefetchArtwork con tmdbId resuelto es false aunque sea vieja`() {
        val existing = ArtworkEntity(itemId = "x", tmdbId = 240411, tmdbType = "tv", backdropsJson = "[]", fetchedAt = 0L)
        assertEquals(false, LibraryGrouping.shouldRefetchArtwork(existing, now = sevenDaysMs * 100))
    }

    /**
     * LA REGRESIÓN de Finding 1: sin tmdbId pero CON backdrops (el caso Magis, que guarda el
     * backdrop del portal con tmdbId=null). Antes del fix esto se reintentaba tras 7 días y
     * `ensureArtwork` pisaba el backdrop con un `"[]"` si TMDB no encontraba match.
     */
    @Test
    fun `shouldRefetchArtwork con backdrops pero sin tmdbId es false aunque sea vieja`() {
        val existing = ArtworkEntity(
            itemId = "magis:1", tmdbId = null, tmdbType = null,
            backdropsJson = """["https://portal/backdrop.jpg"]""", fetchedAt = 0L,
        )
        assertEquals(false, LibraryGrouping.shouldRefetchArtwork(existing, now = sevenDaysMs * 100))
    }

    /** Vacía (sin tmdbId ni backdrops) y ya pasó la ventana de 7 días: sí se reintenta. */
    @Test
    fun `shouldRefetchArtwork vacia y vieja es true`() {
        val existing = ArtworkEntity(itemId = "x", tmdbId = null, tmdbType = null, backdropsJson = "[]", fetchedAt = 0L)
        assertEquals(true, LibraryGrouping.shouldRefetchArtwork(existing, now = sevenDaysMs + 1))
    }

    /** Vacía pero reciente (dentro de la ventana): no se reintenta todavía. */
    @Test
    fun `shouldRefetchArtwork vacia y fresca es false`() {
        val existing = ArtworkEntity(itemId = "x", tmdbId = null, tmdbType = null, backdropsJson = "[]", fetchedAt = 1000L)
        assertEquals(false, LibraryGrouping.shouldRefetchArtwork(existing, now = 1000L + sevenDaysMs - 1))
    }

    /** Sin fila previa: se pide por primera vez. */
    @Test
    fun `shouldRefetchArtwork sin fila previa es true`() {
        assertEquals(true, LibraryGrouping.shouldRefetchArtwork(null, now = 0L))
    }
}
