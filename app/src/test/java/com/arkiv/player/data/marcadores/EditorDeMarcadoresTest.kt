package com.arkiv.player.data.marcadores

import com.arkiv.player.data.MarcadorDeCapitulo
import com.arkiv.player.data.db.SkipMarkerEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EditorDeMarcadoresTest {

    private val dao = FakeSkipMarkerDao()
    private val editor = EditorDeMarcadores(dao = dao, clock = { 555L })

    private fun fila(itemId: String, episodeId: String) =
        dao.filas[MarcadorDeCapitulo.idDe(itemId, episodeId)]

    @Test fun marcar_el_fin_del_opening_escribe_un_manual_de_ESE_capitulo() = runBlocking {
        // El caso que no podía producir nadie: los dos caminos manuales que había escribían
        // siempre con `episodeId = ""`, o sea un manual de la SERIE entera. La rama
        // `manualCapitulo` de `MarcadorDeCapitulo.elegir` estaba probada y era inalcanzable.
        editor.finDelOpening("daima", "daima::e3", 92_000)
        val guardado = fila("daima", "daima::e3")!!
        assertEquals("daima::e3", guardado.episodeId)
        assertEquals(MarcadorDeCapitulo.ORIGEN_MANUAL, guardado.origen)
        assertEquals(92_000L, guardado.openingEndMs)
        assertEquals(555L, guardado.updatedAt)
    }

    @Test fun corregir_un_capitulo_no_toca_a_los_demas() = runBlocking {
        // La razón de ser de todo esto: un manual de SERIE pisa el automático correcto de todos
        // los demás capítulos, por la precedencia de `elegir`.
        dao.upsert(auto("daima", "daima::e1", openingEnd = 90_000))
        dao.upsert(auto("daima", "", openingEnd = 88_000))
        editor.finDelOpening("daima", "daima::e3", 92_000)
        assertEquals(90_000L, fila("daima", "daima::e1")!!.openingEndMs)
        assertEquals(88_000L, fila("daima", "")!!.openingEndMs)
    }

    @Test fun corregir_el_opening_conserva_el_ending_que_ya_tenia_el_capitulo() = runBlocking {
        // La fuente automática se equivoca de a un campo: para un capítulo devolvió los créditos
        // etiquetados como opening. Corregir uno no puede borrar el otro.
        dao.upsert(auto("daima", "daima::e3", openingEnd = 30_000, endingStart = 1_300_000))
        editor.finDelOpening("daima", "daima::e3", 92_000)
        val guardado = fila("daima", "daima::e3")!!
        assertEquals(92_000L, guardado.openingEndMs)
        assertEquals(1_300_000L, guardado.endingStartMs)
        assertEquals("corregido a mano deja de ser automático", MarcadorDeCapitulo.ORIGEN_MANUAL, guardado.origen)
    }

    @Test fun marcar_el_inicio_del_ending_conserva_el_opening() = runBlocking {
        dao.upsert(auto("daima", "daima::e3", openingEnd = 90_000))
        editor.inicioDelEnding("daima", "daima::e3", 1_280_000)
        val guardado = fila("daima", "daima::e3")!!
        assertEquals(90_000L, guardado.openingEndMs)
        assertEquals(1_280_000L, guardado.endingStartMs)
    }

    @Test fun quitar_los_de_un_capitulo_deja_una_fila_manual_vacia() = runBlocking {
        // Borrar la fila a secas haría que el buscador se baje otra vez el automático equivocado
        // en la próxima reproducción. La fila vacía dice "este capítulo no tiene": no tiene
        // tiempos, así que `elegir` la ignora y no dibuja botones, pero existe.
        dao.upsert(auto("daima", "daima::e3", openingEnd = 30_000, endingStart = 1_300_000))
        editor.quitar("daima", "daima::e3")
        val guardado = fila("daima", "daima::e3")!!
        assertNull(guardado.openingEndMs)
        assertNull(guardado.endingStartMs)
        assertEquals(MarcadorDeCapitulo.ORIGEN_MANUAL, guardado.origen)
        assertNull(
            "sin tiempos no manda sobre nada",
            MarcadorDeCapitulo.elegir(delCapitulo = guardado, deLaSerie = null),
        )
    }

    @Test fun quitar_los_de_la_serie_borra_la_fila_como_siempre() = runBlocking {
        dao.upsert(auto("daima", "", openingEnd = 88_000))
        editor.quitar("daima", "")
        assertTrue(dao.filas.isEmpty())
    }

    @Test fun un_manual_de_capitulo_le_gana_al_manual_de_la_serie() = runBlocking {
        editor.finDelOpening("daima", "", 88_000)
        editor.finDelOpening("daima", "daima::e3", 92_000)
        val elegido = MarcadorDeCapitulo.elegir(
            delCapitulo = fila("daima", "daima::e3"),
            deLaSerie = fila("daima", ""),
        )
        assertEquals(92_000L, elegido!!.openingEndMs)
    }

    private fun auto(itemId: String, episodeId: String, openingEnd: Long? = null, endingStart: Long? = null) =
        SkipMarkerEntity(
            id = MarcadorDeCapitulo.idDe(itemId, episodeId),
            itemId = itemId,
            episodeId = episodeId,
            openingStartMs = 0,
            openingEndMs = openingEnd,
            endingStartMs = endingStart,
            updatedAt = 1,
            origen = MarcadorDeCapitulo.ORIGEN_AUTO,
        )
}
