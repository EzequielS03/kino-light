package com.arkiv.player.data.gateway

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FuenteCompuestaTest {

    private class FuenteDeMentira(
        val nombre: String,
        val prefijo: String,
        val resultados: List<String> = emptyList(),
        val error: String? = null,
    ) : FuenteDeContenido {
        var resolvio: String? = null

        override fun reconoce(ref: String) = ref.startsWith(prefijo)

        override fun search(ctx: GatewaySearchQuery): Flow<SearchEvent> = flow {
            emit(SearchEvent.SourceStart(nombre))
            if (error != null) {
                emit(SearchEvent.SourceError(nombre, error, 1, 0))
            } else {
                resultados.forEach {
                    emit(SearchEvent.ResultEvent(nombre, GatewayResult(nombre, it, "$prefijo$it")))
                }
                emit(SearchEvent.SourceDone(nombre, resultados.size, 1))
            }
            emit(SearchEvent.Done(1))
        }

        override suspend fun resolve(ref: String): GatewayPlayable {
            resolvio = ref
            return GatewayPlayable(kind = nombre, url = "http://$nombre")
        }

        override suspend fun episodesConSerie(ref: String): Pair<List<GatewayEpisode>, GatewaySerie?> {
            resolvio = ref
            return listOf(GatewayEpisode(1, "Cap", ref)) to null
        }
    }

    @Test fun `los resultados de las dos fuentes llegan`() = runTest {
        val a = FuenteDeMentira("a", "a:", listOf("uno", "dos"))
        val b = FuenteDeMentira("b", "b:", listOf("tres"))

        val eventos = FuenteCompuesta(listOf(a, b)).search(GatewaySearchQuery(q = "x")).toList()

        val titulos = eventos.filterIsInstance<SearchEvent.ResultEvent>().map { it.item.title }
        assertEquals(setOf("uno", "dos", "tres"), titulos.toSet())
    }

    /** UN solo Done, y al final: si cada fuente emitiera el suyo, la pantalla creería que terminó
     *  la búsqueda cuando apenas terminó la primera. */
    @Test fun `hay un unico Done y es el ultimo evento`() = runTest {
        val a = FuenteDeMentira("a", "a:", listOf("uno"))
        val b = FuenteDeMentira("b", "b:", listOf("dos"))

        val eventos = FuenteCompuesta(listOf(a, b)).search(GatewaySearchQuery(q = "x")).toList()

        assertEquals(1, eventos.count { it is SearchEvent.Done })
        assertTrue(eventos.last() is SearchEvent.Done)
    }

    /** LA REGLA QUE IMPORTA: una fuente caída no puede vaciar la búsqueda de la otra. */
    @Test fun `si una fuente falla la otra igual entrega`() = runTest {
        val rota = FuenteDeMentira("rota", "r:", error = "se cayó")
        val sana = FuenteDeMentira("sana", "s:", listOf("uno", "dos"))

        val eventos = FuenteCompuesta(listOf(rota, sana)).search(GatewaySearchQuery(q = "x")).toList()

        assertEquals(2, eventos.filterIsInstance<SearchEvent.ResultEvent>().size)
        val err = eventos.filterIsInstance<SearchEvent.SourceError>().single()
        assertEquals("rota", err.source)
        assertTrue(eventos.last() is SearchEvent.Done)
    }

    @Test fun `resolve va a la fuente que reconoce el ref`() = runTest {
        val a = FuenteDeMentira("a", "a:")
        val b = FuenteDeMentira("b", "b:")

        val play = FuenteCompuesta(listOf(a, b)).resolve("b:42")

        assertEquals("b", play.kind)
        assertEquals("b:42", b.resolvio)
        assertEquals(null, a.resolvio)
    }

    @Test fun `episodes va a la fuente que reconoce el ref`() = runTest {
        val a = FuenteDeMentira("a", "a:")
        val b = FuenteDeMentira("b", "b:")

        FuenteCompuesta(listOf(a, b)).episodesConSerie("a:9")

        assertEquals("a:9", a.resolvio)
        assertEquals(null, b.resolvio)
    }

    @Test fun `un ref que nadie reconoce es un GatewayException`() = runTest {
        val compuesta = FuenteCompuesta(listOf(FuenteDeMentira("a", "a:")))

        val e = runCatching { compuesta.resolve("z:1") }.exceptionOrNull()
        assertTrue(e is GatewayException)
    }

    @Test fun `reconoce si cualquiera de sus fuentes reconoce`() {
        val compuesta = FuenteCompuesta(listOf(FuenteDeMentira("a", "a:"), FuenteDeMentira("b", "b:")))

        assertTrue(compuesta.reconoce("b:1"))
        assertTrue(!compuesta.reconoce("z:1"))
    }

    private class FuenteQueLanza(
        val nombre: String,
        val prefijo: String,
    ) : FuenteDeContenido {
        override fun reconoce(ref: String) = ref.startsWith(prefijo)

        override fun search(ctx: GatewaySearchQuery): Flow<SearchEvent> = flow {
            emit(SearchEvent.SourceStart(nombre))
            throw IllegalStateException("Explosión deliberada")
        }

        override suspend fun resolve(ref: String): GatewayPlayable {
            return GatewayPlayable(kind = nombre, url = "http://$nombre")
        }

        override suspend fun episodesConSerie(ref: String): Pair<List<GatewayEpisode>, GatewaySerie?> {
            return listOf(GatewayEpisode(1, "Cap", ref)) to null
        }
    }

    /** LA REGLA QUE MÁS IMPORTA: una fuente que lanza NO puede vaciar la búsqueda de las otras. */
    @Test fun `si una fuente lanza la otra igual entrega`() = runTest {
        val lanzadora = FuenteQueLanza("lanzadora", "l:")
        val sana = FuenteDeMentira("sana", "s:", listOf("uno", "dos"))

        val eventos = FuenteCompuesta(listOf(lanzadora, sana)).search(GatewaySearchQuery(q = "x")).toList()

        assertEquals(2, eventos.filterIsInstance<SearchEvent.ResultEvent>().size)
        val err = eventos.filterIsInstance<SearchEvent.SourceError>().single()
        assertEquals("lanzadora", err.source)
        assertEquals(0, err.count)
        assertTrue(eventos.last() is SearchEvent.Done)
    }
}
