package com.arkiv.player.data.recomendaciones

import com.arkiv.player.data.catalog.TmdbItem
import com.arkiv.player.data.gateway.GatewayResult
import com.arkiv.player.data.ia.RespuestaDeIa
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VerificacionParaTiTest {

    private fun tmdb(id: Int, tipo: String, titulo: String, anio: String = "2017") =
        TmdbItem(id = id, type = tipo, title = titulo, originalTitle = titulo, posterUrl = "p$id", year = anio)

    private fun resultado(titulo: String, ref: String = "magis1:movie:0:$titulo") =
        GatewayResult(source = "magis", title = titulo, ref = ref)

    private val coco = Candidato("Coco", "2017", "movie", "porque viste Encanto")

    private fun verificacion(
        enTmdb: (String, String) -> TmdbItem? = { t, titulo -> tmdb(1, t, titulo) },
        enFuentes: (String) -> List<GatewayResult> = { listOf(resultado(it)) },
        arbitro: Arbitro = Arbitro { _, _, _, _ -> listOf(0) },
    ) = VerificacionParaTi(
        tmdb = BuscadorEnTmdb { tipo, titulo -> enTmdb(tipo, titulo) },
        fuentes = BuscadorEnFuentes { titulo, _, _, _ -> enFuentes(titulo) },
        arbitro = arbitro,
    )

    @Test fun `un candidato que pasa todo queda con su ref y los datos de TMDB`() = runTest {
        val v = verificacion().verificar(listOf(coco), emptySet()).single()
        assertEquals(1, v.tmdbId)
        assertEquals("magis1:movie:0:Coco", v.ref)
        assertEquals("p1", v.posterUrl)
        assertEquals("porque viste Encanto", v.candidato.porque)
    }

    @Test fun `lo que TMDB no conoce era una alucinacion`() = runTest {
        assertTrue(verificacion(enTmdb = { _, _ -> null }).verificar(listOf(coco), emptySet()).isEmpty())
    }

    /** El tipo que vale de ahí en adelante es el que confirmó TMDB, no el que propuso el modelo. */
    @Test fun `si no aparece con su tipo se busca con el otro`() = runTest {
        val v = verificacion(enTmdb = { t, titulo -> if (t == "tv") tmdb(9, "tv", titulo) else null })
            .verificar(listOf(coco), emptySet()).single()
        assertEquals("tv", v.tipo)
    }

    /** El homónimo del tipo equivocado es como una película terminó abriendo la pantalla de temporada. */
    @Test fun `un calce exacto del otro tipo gana sobre un homonimo`() = runTest {
        val v = verificacion(enTmdb = { t, _ ->
            if (t == "movie") tmdb(1, "movie", "Coco Chanel") else tmdb(2, "tv", "Coco")
        }).verificar(listOf(coco), emptySet()).single()
        assertEquals(2, v.tmdbId)
        assertEquals("tv", v.tipo)
    }

    @Test fun `sin calce exacto gana el primero que aparecio`() = runTest {
        val v = verificacion(enTmdb = { t, _ ->
            if (t == "movie") tmdb(1, "movie", "Coco Chanel") else tmdb(2, "tv", "Coco y sus amigos")
        }).verificar(listOf(coco), emptySet()).single()
        assertEquals(1, v.tmdbId)
    }

    @Test fun `lo ya visto por id se descarta`() = runTest {
        assertTrue(verificacion().verificar(listOf(coco), setOf("tmdb:1")).isEmpty())
    }

    @Test fun `lo ya visto por titulo se descarta`() = runTest {
        assertTrue(verificacion().verificar(listOf(coco), setOf(NormalizarTitulo.de("COCO!"))).isEmpty())
    }

    @Test fun `un titulo vacio en ya vistos no descarta nada`() = runTest {
        assertEquals(1, verificacion().verificar(listOf(coco), setOf("")).size)
    }

    @Test fun `sin fuente que lo tenga se descarta`() = runTest {
        assertTrue(verificacion(enFuentes = { emptyList() }).verificar(listOf(coco), emptySet()).isEmpty())
    }

    @Test fun `el arbitro elige cual resultado es la obra`() = runTest {
        val v = verificacion(
            enFuentes = { listOf(resultado("Coco podcast", "ref-podcast"), resultado("Coco", "ref-bueno")) },
            arbitro = Arbitro { _, _, _, _ -> listOf(1) },
        ).verificar(listOf(coco), emptySet()).single()
        assertEquals("ref-bueno", v.ref)
    }

    /** El gateway (`router/search.py`) gana el aprobado que va PRIMERO en `resultados`, no el
     *  primer índice que el modelo haya escrito (el JSON no obliga orden ascendente). */
    @Test fun `con varios aprobados gana el primero de la lista de resultados`() = runTest {
        val v = verificacion(
            enFuentes = { listOf(resultado("A", "ref-a"), resultado("B", "ref-b"), resultado("C", "ref-c")) },
            arbitro = Arbitro { _, _, _, _ -> listOf(2, 0) },
        ).verificar(listOf(coco), emptySet()).single()
        assertEquals("ref-a", v.ref)
    }

    @Test fun `un rechazo total del arbitro descarta al candidato`() = runTest {
        assertTrue(verificacion(arbitro = Arbitro { _, _, _, _ -> emptyList() }).verificar(listOf(coco), emptySet()).isEmpty())
    }

    /** Árbitro caído = primer resultado, como hacía el gateway. */
    @Test fun `si el arbitro no contesta se toma el primero`() = runTest {
        val v = verificacion(
            enFuentes = { listOf(resultado("A", "ref-a"), resultado("B", "ref-b")) },
            arbitro = Arbitro { _, _, _, _ -> null },
        ).verificar(listOf(coco), emptySet()).single()
        assertEquals("ref-a", v.ref)
    }

    @Test fun `se para en el tope`() = runTest {
        val muchos = (1..15).map { Candidato("Peli $it", "2020", "movie", "x") }
        assertEquals(10, verificacion().verificar(muchos, emptySet(), tope = 10).size)
    }

    @Test fun `normalizar quita tildes mayusculas y signos`() {
        assertEquals("el nino y la garza", NormalizarTitulo.de("¡El  Niño y la Garza!"))
    }

    @Test fun `normalizar no borra otros alfabetos`() {
        assertEquals("千と千尋の神隠し", NormalizarTitulo.de("千と千尋の神隠し"))
    }

    @Test fun `el arbitro lee los numeros y descarta los que no existen`() = runTest {
        val a = ArbitroDeIa { RespuestaDeIa.Texto("[0, 5, true, 1]", "m") }
        assertEquals(listOf(0, 1), a.cuales("Coco", "2017", "movie", listOf(resultado("x"), resultado("y"))))
    }

    @Test fun `el arbitro que no contesta es null`() = runTest {
        assertNull(ArbitroDeIa { RespuestaDeIa.NoPude }.cuales("Coco", "", "movie", listOf(resultado("x"))))
    }

    @Test fun `el arbitro ilegible es null`() = runTest {
        assertNull(ArbitroDeIa { RespuestaDeIa.Texto("no sé", "m") }.cuales("Coco", "", "movie", listOf(resultado("x"))))
    }

    @Test fun `el arbitro manda el prompt del gateway con la lista numerada`() = runTest {
        var instruccion = ""
        ArbitroDeIa { instruccion = it; RespuestaDeIa.Texto("[]", "m") }
            .cuales("Coco", "2017", "movie", listOf(GatewayResult(source = "magis", title = "Coco.2017.1080p", ref = "r", year = "2017")))
        assertTrue(instruccion.startsWith("Busco: Coco (2017) (película)."))
        assertTrue(instruccion.endsWith("\n\n0. [magis] Coco.2017.1080p (2017, movie)"))
    }
}
