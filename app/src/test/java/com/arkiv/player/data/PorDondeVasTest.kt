package com.arkiv.player.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Por dónde vas en una serie: la regla que comparten "Continuar viendo" y el botón "Reproducir".
 *
 * El caso que la motivó (device, 2026-08-13): Dragon Ball visto hasta el e136 —todos terminados— y
 * un e104 abandonado al 38% de la mañana anterior. Las dos superficies ofrecían el e104, porque las
 * dos buscaban "el más reciente SIN TERMINAR" en vez de mirar primero qué fue lo último que se
 * reprodujo de verdad.
 */
class PorDondeVasTest {

    private fun p(
        episodeId: String,
        lastPlayedAt: Long,
        positionMs: Long = 0L,
        watched: Boolean = false,
    ) = ProgresoDeCapitulo(
        episodeId = episodeId,
        positionMs = positionMs,
        watched = watched,
        lastPlayedAt = lastPlayedAt,
    )

    /** Lista ordenada de capítulos: el siguiente de "e5" es "e6". */
    private fun enOrden(vararg ids: String): (String) -> String? = { id ->
        val i = ids.indexOf(id)
        if (i >= 0) ids.getOrNull(i + 1) else null
    }

    private val minuto = 60_000L

    @Test
    fun `si terminaste el ultimo capitulo ofrece el siguiente`() {
        val r = PorDondeVas.elegir(
            listOf(p("e136", lastPlayedAt = 900L, positionMs = 1_393_000L, watched = true)),
            enOrden("e136", "e137"),
        )
        assertEquals("e137", r?.episodeId)
        assertEquals(true, r?.esSiguiente)
    }

    @Test
    fun `el capitulo abandonado hace rato NO le gana a lo que terminaste despues`() {
        // El bug reportado, tal cual: e104 a medias por la mañana, e136 terminado por la noche.
        val r = PorDondeVas.elegir(
            listOf(
                p("e104", lastPlayedAt = 100L, positionMs = 567_000L),
                p("e136", lastPlayedAt = 900L, positionMs = 1_393_000L, watched = true),
            ),
            enOrden("e104", "e136", "e137"),
        )
        assertEquals("e137", r?.episodeId)
    }

    @Test
    fun `el orden lo manda la ultima reproduccion, no el capitulo ofrecido`() {
        // La fila del home se ordena por esto: si mandara el capítulo ofrecido (que no se reprodujo
        // nunca), una serie que terminaste anoche caería al fondo detrás de cualquier cosa vieja.
        val r = PorDondeVas.elegir(
            listOf(p("e136", lastPlayedAt = 900L, positionMs = 1_393_000L, watched = true)),
            enOrden("e136", "e137"),
        )
        assertEquals(900L, r?.lastPlayedAt)
    }

    @Test
    fun `un capitulo a medias reciente gana sobre el siguiente`() {
        val r = PorDondeVas.elegir(
            listOf(
                p("e2", lastPlayedAt = 100L, positionMs = 1_400_000L, watched = true),
                p("e3", lastPlayedAt = 900L, positionMs = 213_000L),
            ),
            enOrden("e2", "e3", "e4"),
        )
        assertEquals("e3", r?.episodeId)
        assertEquals(false, r?.esSiguiente)
    }

    @Test
    fun `si terminaste la serie entera no hay nada que continuar`() {
        val r = PorDondeVas.elegir(
            listOf(p("e153", lastPlayedAt = 900L, positionMs = 1_400_000L, watched = true)),
            enOrden("e152", "e153"),
        )
        assertNull(r)
    }

    @Test
    fun `el capitulo solo ABIERTO es por donde vas, sin piso de segundos`() {
        // Regresión que arregló inProgressEpisode y que el piso NO puede revivir: darle play al e5
        // y salir a los tres segundos (markInProgress deja la fila en 0) tiene que decir "vas en el
        // e5", no "vas en el e1". Por eso elegir() no conoce ningún piso.
        val r = PorDondeVas.elegir(
            listOf(p("e5", lastPlayedAt = 900L, positionMs = 0L)),
            enOrden("e1", "e2", "e3", "e4", "e5"),
        )
        assertEquals("e5", r?.episodeId)
        // NO es "el siguiente": es donde estás parado, aunque no tenga posición guardada. De esto
        // depende que el detalle lo siga marcando como el capítulo actual.
        assertEquals(false, r?.esSiguiente)
    }

    @Test
    fun `el capitulo solo abierto no le gana al que tiene reproduccion real`() {
        // Regresión de la regla vieja (ver el doc de inProgressEpisode): markInProgress escribe una
        // fila en posición 0 al ABRIR un capítulo. Esa fila no puede desplazar a la que sí sonó.
        val r = PorDondeVas.elegir(
            listOf(
                p("e126", lastPlayedAt = 100L, positionMs = 210_000L),
                p("e127", lastPlayedAt = 800L, positionMs = 0L),
                p("e128", lastPlayedAt = 900L, positionMs = 0L),
            ),
            enOrden("e126", "e127", "e128"),
        )
        assertEquals("e126", r?.episodeId)
    }

    @Test
    fun `sin ninguna reproduccion no hay nada que continuar`() {
        assertNull(PorDondeVas.elegir(emptyList(), enOrden("e1")))
    }

    @Test
    fun `si el ultimo terminado es el unico y no tiene siguiente no inventa nada`() {
        val r = PorDondeVas.elegir(
            listOf(p("suelto", lastPlayedAt = 900L, positionMs = 1_400_000L, watched = true)),
            { null },
        )
        assertNull(r)
    }

    // --- La fila completa del home (una tarjeta por ítem, ordenada) ------------------------------

    private fun enItem(
        itemId: String,
        progreso: ProgresoDeCapitulo,
        siguienteEpisodeId: String? = null,
    ) = ProgresoEnItem(itemId, progreso, siguienteEpisodeId)

    @Test
    fun `una sola tarjeta por serie, aunque tenga diez capitulos con progreso`() {
        val filas = (1..10).map { n ->
            enItem("db", p("e$n", lastPlayedAt = n * 100L, positionMs = 1_400_000L, watched = true), "e${n + 1}")
        }
        val r = PorDondeVas.porItem(filas, minPositionMs = 0L)
        assertEquals(1, r.size)
        assertEquals("e11", r.first().episodeId)
    }

    @Test
    fun `la serie que viste mas recien va primero`() {
        // El caso reportado: Dragon Ball terminado anoche tiene que ir ANTES que el Evangelion
        // que quedó a medias más temprano.
        val filas = listOf(
            enItem("eva", p("eva-e3", lastPlayedAt = 500L, positionMs = 213_000L)),
            enItem("db", p("db-e136", lastPlayedAt = 900L, positionMs = 1_393_000L, watched = true), "db-e137"),
        )
        val r = PorDondeVas.porItem(filas, minPositionMs = 2 * minuto)
        assertEquals(listOf("db-e137", "eva-e3"), r.map { it.episodeId })
    }

    @Test
    fun `los items sin nada que continuar no ocupan lugar en la fila`() {
        val filas = listOf(
            enItem("terminada", p("fin", lastPlayedAt = 900L, positionMs = 1_400_000L, watched = true), null),
            enItem("viva", p("e1", lastPlayedAt = 100L, positionMs = 300_000L)),
        )
        val r = PorDondeVas.porItem(filas, minPositionMs = 2 * minuto)
        assertEquals(listOf("e1"), r.map { it.episodeId })
    }

    @Test
    fun `la fila se corta en el limite`() {
        val filas = (1..30).map { n ->
            enItem("item$n", p("e$n", lastPlayedAt = n * 100L, positionMs = 300_000L))
        }
        assertEquals(20, PorDondeVas.porItem(filas, minPositionMs = 0L).size)
    }

    @Test
    fun `una pelicula que abriste unos segundos no ocupa la fila`() {
        val filas = listOf(enItem("peli", p("peli", lastPlayedAt = 900L, positionMs = 3_000L)))
        assertEquals(emptyList<String>(), PorDondeVas.porItem(filas, minPositionMs = 2 * minuto).map { it.episodeId })
    }

    @Test
    fun `una serie que venis viendo se queda aunque el capitulo actual lleve 30 segundos`() {
        // Terminaste el e136 y le diste play 30 s al e137: seguís en el e137. El piso está para las
        // cosas que tocaste y abandonaste, no para echar de la fila la serie que venís viendo.
        val filas = listOf(
            enItem("db", p("e136", lastPlayedAt = 100L, positionMs = 1_393_000L, watched = true), "e137"),
            enItem("db", p("e137", lastPlayedAt = 900L, positionMs = 30_000L), "e138"),
        )
        val r = PorDondeVas.porItem(filas, minPositionMs = 2 * minuto)
        assertEquals(listOf("e137"), r.map { it.episodeId })
    }

    @Test
    fun `el siguiente de un item no se cruza con el de otro`() {
        val filas = listOf(
            enItem("a", p("a1", lastPlayedAt = 900L, positionMs = 1_400_000L, watched = true), "a2"),
            enItem("b", p("b1", lastPlayedAt = 800L, positionMs = 1_400_000L, watched = true), "b2"),
        )
        val r = PorDondeVas.porItem(filas, minPositionMs = 0L)
        assertEquals(listOf("a2", "b2"), r.map { it.episodeId })
    }
}
