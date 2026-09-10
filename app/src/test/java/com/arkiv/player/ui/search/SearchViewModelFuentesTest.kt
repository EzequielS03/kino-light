package com.arkiv.player.ui.search

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import com.arkiv.player.data.SearchHistoryRepo
import com.arkiv.player.data.SettingsStore
import com.arkiv.player.data.catalog.AniListApi
import com.arkiv.player.data.catalog.TmdbApi
import com.arkiv.player.data.db.RecentTitleDao
import com.arkiv.player.data.db.RecentTitleEntity
import com.arkiv.player.data.db.SearchHistoryDao
import com.arkiv.player.data.db.SearchHistoryEntity
import com.arkiv.player.data.gateway.FuenteDeContenido
import com.arkiv.player.data.gateway.GatewayEpisode
import com.arkiv.player.data.gateway.GatewayException
import com.arkiv.player.data.gateway.GatewayPlayable
import com.arkiv.player.data.gateway.GatewayResult
import com.arkiv.player.data.gateway.GatewaySearchQuery
import com.arkiv.player.data.gateway.GatewaySerie
import com.arkiv.player.data.gateway.SearchEvent
import com.arkiv.player.ui.catalog.PlaySource
import java.lang.reflect.Proxy
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Que el ViewModel de la búsqueda EXPONGA el error de cada fuente, además de pasar los resultados
 * de las que sí respondieron. Es lo que leen los resultados del celular y del TV.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SearchViewModelFuentesTest {

    @Before fun antes() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After fun despues() {
        Dispatchers.resetMain()
    }

    private fun vm(fuente: FuenteDeContenido) = SearchViewModel(
        tmdbApi = TmdbApi(apiKey = "x"),
        aniListApi = AniListApi(),
        settings = SettingsStore(ContextoDePrueba()),
        arkivApiClient = fuente,
        searchHistory = SearchHistoryRepo(HistorialVacio(), RecientesVacios()),
    )

    @Test fun `si Caracol se cae, Magis se ve y la caida de Caracol queda expuesta`() = runTest {
        val sinRed = java.net.UnknownHostException("sin red")
        val vm = vm(FuenteDePrueba {
            listOf(
                SearchEvent.SourceStart("magis"),
                SearchEvent.ResultEvent("magis", GatewayResult(source = "magis", title = "Rigo", ref = "m1")),
                SearchEvent.SourceDone("magis", 1, 5),
                SearchEvent.SourceStart("ditu"),
                SearchEvent.SourceError("ditu", "sin red", 5, 0, causa = sinRed),
                SearchEvent.Done(10),
            )
        })

        vm.buscarFuentesPorTexto("rigo")
        advanceUntilIdle()

        assertEquals(listOf("Rigo"), vm.sources.value.map { (it as PlaySource.Magis).result.title })
        assertEquals(mapOf("ditu" to "sin red"), vm.estadoDeFuentes.value.caidas)
        // La excepción llega a la pantalla: es con lo que `FalloDeCaracol` escribe la línea.
        assertEquals(mapOf<String, Throwable>("ditu" to sinRed), vm.estadoDeFuentes.value.causas)
        assertEquals(setOf("magis"), vm.estadoDeFuentes.value.respondieron)
        assertFalse(vm.fuentesBuscando.value.alguna)
    }

    /** "Buscando en Magis…" se apaga con el SourceDone de Magis, no con el Done de toda la búsqueda. */
    @Test fun `Magis deja de girar apenas responde, aunque Caracol siga buscando`() = runTest {
        val caracolContesta = CompletableDeferred<Unit>()
        val vm = vm(FuenteConFlujo {
            flow {
                emit(SearchEvent.SourceStart("magis"))
                emit(SearchEvent.ResultEvent("magis", GatewayResult(source = "magis", title = "Rigo", ref = "m1")))
                emit(SearchEvent.SourceDone("magis", 1, 5))
                emit(SearchEvent.SourceStart("ditu"))
                caracolContesta.await()
                emit(SearchEvent.SourceDone("ditu", 0, 5))
                emit(SearchEvent.Done(10))
            }
        })

        vm.buscarFuentesPorTexto("rigo")
        advanceUntilIdle()

        assertFalse(vm.fuentesBuscando.value.buscando(SourceTab.MAGIS))
        assertTrue(vm.fuentesBuscando.value.buscando(SourceTab.CARACOL))
        assertTrue(vm.fuentesBuscando.value.buscando(SourceTab.TODO))

        caracolContesta.complete(Unit)
        advanceUntilIdle()

        assertFalse(vm.fuentesBuscando.value.alguna)
    }

    @Test fun `una busqueda nueva arranca sin los errores de la anterior`() = runTest {
        var cae = true
        val vm = vm(FuenteDePrueba {
            if (cae) {
                listOf(SearchEvent.SourceStart("ditu"), SearchEvent.SourceError("ditu", "sin red", 0, 0), SearchEvent.Done(0))
            } else {
                listOf(SearchEvent.SourceStart("ditu"), SearchEvent.SourceDone("ditu", 0, 0), SearchEvent.Done(0))
            }
        })

        vm.buscarFuentesPorTexto("rigo")
        advanceUntilIdle()
        assertEquals(setOf("ditu"), vm.estadoDeFuentes.value.caidas.keys)

        cae = false
        vm.buscarFuentesPorTexto("rigo")
        advanceUntilIdle()
        assertTrue(vm.estadoDeFuentes.value.caidas.isEmpty())
        assertEquals(setOf("ditu"), vm.estadoDeFuentes.value.respondieron)
    }
}

/** Una fuente cuyo flujo arma el test, para poder dejar una fuente a mitad de camino. */
private class FuenteConFlujo(private val flujo: () -> Flow<SearchEvent>) : FuenteDeContenido {
    override fun reconoce(ref: String) = false
    override fun search(ctx: GatewaySearchQuery): Flow<SearchEvent> = flujo()
    override suspend fun resolve(ref: String): GatewayPlayable = throw GatewayException("sin uso en el test")
    override suspend fun episodesConSerie(ref: String): Pair<List<GatewayEpisode>, GatewaySerie?> =
        emptyList<GatewayEpisode>() to null
}

/** Una fuente que devuelve, en cada búsqueda, los eventos que diga el test. */
private class FuenteDePrueba(private val eventos: () -> List<SearchEvent>) : FuenteDeContenido {
    override fun reconoce(ref: String) = false
    override fun search(ctx: GatewaySearchQuery): Flow<SearchEvent> = eventos().asFlow()
    override suspend fun resolve(ref: String): GatewayPlayable = throw GatewayException("sin uso en el test")
    override suspend fun episodesConSerie(ref: String): Pair<List<GatewayEpisode>, GatewaySerie?> =
        emptyList<GatewayEpisode>() to null
}

/**
 * `SettingsStore` lee `SharedPreferences` al construirse, y `SearchViewModel` no la usa para nada
 * (no tiene ningún `settings.`): alcanza con unas preferencias que devuelvan el valor por defecto.
 */
private class ContextoDePrueba : ContextWrapper(null) {
    override fun getApplicationContext(): Context = this

    override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences =
        Proxy.newProxyInstance(
            SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java),
        ) { _, metodo, args ->
            when (metodo.name) {
                "contains" -> false
                // getInt/getBoolean/getString(clave, porDefecto): el segundo argumento.
                else -> args?.getOrNull(1)
            }
        } as SharedPreferences
}

private class HistorialVacio : SearchHistoryDao {
    override suspend fun upsert(entry: SearchHistoryEntity) = Unit
    override suspend fun recent(kind: String, limit: Int): List<SearchHistoryEntity> = emptyList()
    override fun observeRecent(kind: String, limit: Int): Flow<List<SearchHistoryEntity>> = flowOf(emptyList())
    override suspend fun deleteOne(kind: String, query: String) = Unit
    override suspend fun clearKind(kind: String) = Unit
    override suspend fun clear() = Unit
}

private class RecientesVacios : RecentTitleDao {
    override suspend fun upsert(entry: RecentTitleEntity) = Unit
    override fun observeRecent(limit: Int): Flow<List<RecentTitleEntity>> = flowOf(emptyList())
    override suspend fun deleteOne(id: String) = Unit
    override suspend fun clear() = Unit
    override suspend fun trim(keep: Int) = Unit
}
