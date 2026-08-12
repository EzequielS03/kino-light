package com.arkiv.player.ui.live

import com.arkiv.player.data.db.LiveChannelCacheDao
import com.arkiv.player.data.db.LiveChannelCacheEntity
import com.arkiv.player.data.db.LiveFavoriteDao
import com.arkiv.player.data.db.LiveFavoriteEntity
import com.arkiv.player.data.gateway.LiveCatalogGateway
import com.arkiv.player.data.gateway.LiveCategory
import com.arkiv.player.data.gateway.LiveChannel
import com.arkiv.player.data.gateway.LiveProgram
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class LiveViewModelTest {
    private val canales = listOf(
        LiveChannel("c1", "ESPN", 501, null),
        LiveChannel("c2", "TNT Sports", 502, null),
        LiveChannel("c3", "Caracol", 101, null),
    )

    @Test
    fun `busca por nombre sin importar mayusculas ni tildes`() {
        assertEquals(listOf("c3"), filtrar(canales, "caracol").map { it.code })
    }

    @Test
    fun `busca por numero de canal`() {
        assertEquals(listOf("c2"), filtrar(canales, "502").map { it.code })
    }

    @Test
    fun `sin texto devuelve todo en el orden que vino`() {
        assertEquals(canales, filtrar(canales, "  "))
    }

    // --- progresoDePrograma: barra fina de avance del programa en curso (Step 5 del brief) ---

    @Test
    fun `progreso es cero justo al arrancar el programa`() {
        val p = LiveProgram("Noticias", inicio = 1000L, fin = 2000L, sinopsis = "")
        assertEquals(0f, progresoDePrograma(p, ahoraSegundos = 1000L))
    }

    @Test
    fun `progreso es la mitad a mitad de camino`() {
        val p = LiveProgram("Noticias", inicio = 1000L, fin = 2000L, sinopsis = "")
        assertEquals(0.5f, progresoDePrograma(p, ahoraSegundos = 1500L))
    }

    @Test
    fun `progreso no pasa de uno aunque el programa ya haya terminado`() {
        val p = LiveProgram("Noticias", inicio = 1000L, fin = 2000L, sinopsis = "")
        assertEquals(1f, progresoDePrograma(p, ahoraSegundos = 5000L))
    }

    @Test
    fun `progreso no baja de cero con datos inconsistentes de duracion`() {
        // fin <= inicio no debería pasar en la práctica, pero si el portal manda algo raro,
        // la barra no debe romperse ni mostrar un número negativo o NaN.
        val p = LiveProgram("Raro", inicio = 2000L, fin = 2000L, sinopsis = "")
        assertEquals(0f, progresoDePrograma(p, ahoraSegundos = 2000L))
    }
}

// --- Dobles de prueba -------------------------------------------------------------------------
//
// El proyecto no tiene Mockito ni mockk (ver app/build.gradle.kts). LiveViewModel depende de
// LiveCatalogGateway -la interfaz angosta definida junto a LiveApi en data/gateway/LiveApi.kt,
// con solo las tres operaciones que este ViewModel consume- y no de LiveApi directo, así que este
// doble la implementa derecho, sin heredar de ninguna clase de producción ni tocar red. (Una
// versión anterior abría LiveApi con `open class`/`open fun` para poder heredarla en el test; se
// descartó en review por ser la única clase abierta de todo el módulo sin motivo arquitectónico
// -ver el KDoc de LiveCatalogGateway para el razonamiento completo.) LiveFavoriteDao/
// LiveChannelCacheDao ya eran interfaces y se implementan igual, con almacenamiento en memoria.
private class FakeLiveApi : LiveCatalogGateway {
    var categoriasResult: List<LiveCategory> = emptyList()
    val canalesPorCategoria = mutableMapOf<Int, List<LiveChannel>>()

    /** Categoría -> gate que retiene canales(categoria) hasta que el test lo complete a mano. */
    val gates = mutableMapOf<Int, CompletableDeferred<Unit>>()
    val canalesCalls = mutableListOf<Int>()
    val epgCalls = mutableListOf<List<String>>()

    /**
     * Por default no devuelve nada y no le falta nada -el comportamiento que ya usaban los tests
     * existentes-. Los tests del reintento acotado de EPG (hallazgo F3) lo reemplazan para simular
     * que el gateway todavía no tiene la programación de algunos códigos (`missing`).
     */
    var epgResponder: (List<String>) -> Pair<Map<String, List<LiveProgram>>, List<String>> =
        { emptyMap<String, List<LiveProgram>>() to emptyList() }

    override suspend fun categorias(): List<LiveCategory> = categoriasResult

    override suspend fun canales(categoria: Int): List<LiveChannel> {
        canalesCalls.add(categoria)
        gates[categoria]?.await()
        return canalesPorCategoria[categoria].orEmpty()
    }

    override suspend fun epg(codes: List<String>): Pair<Map<String, List<LiveProgram>>, List<String>> {
        epgCalls.add(codes)
        return epgResponder(codes)
    }
}

private class FakeFavoriteDao : LiveFavoriteDao {
    private val flow = MutableStateFlow<List<LiveFavoriteEntity>>(emptyList())
    override fun flowTodos(): Flow<List<LiveFavoriteEntity>> = flow
    override suspend fun guardar(f: LiveFavoriteEntity) {
        flow.value = flow.value.filterNot { it.code == f.code } + f
    }
    override suspend fun borrar(code: String) {
        flow.value = flow.value.filterNot { it.code == code }
    }
    override suspend fun esFavorito(code: String): Boolean = flow.value.any { it.code == code }
    override suspend fun getAll(): List<LiveFavoriteEntity> = flow.value
}

private class FakeCacheDao : LiveChannelCacheDao {
    private val store = mutableMapOf<Int, List<LiveChannelCacheEntity>>()

    /** Setup directo del test, sin pasar por guardar()/reemplazar(). */
    fun prellenar(categoria: Int, filas: List<LiveChannelCacheEntity>) {
        store[categoria] = filas
    }

    override suspend fun deCategoria(categoria: Int): List<LiveChannelCacheEntity> = store[categoria].orEmpty()
    // No lo ejercita ningún test de este archivo (son todos sobre elegirCategoria/deCategoria);
    // implementación mínima para satisfacer la interfaz.
    override suspend fun deCodigos(codes: List<String>): List<LiveChannelCacheEntity> =
        store.values.flatten().filter { it.code in codes }
    override suspend fun limpiar(categoria: Int) { store.remove(categoria) }
    override suspend fun guardar(filas: List<LiveChannelCacheEntity>) {
        filas.groupBy { it.categoria }.forEach { (cat, rows) -> store[cat] = rows }
    }
    // reemplazar() usa el body por default de la interfaz (limpiar + guardar), no hace falta acá.
}

// --- Comportamiento dinámico del ViewModel ----------------------------------------------------
//
// Los tests de arriba solo cubrían funciones puras (filtrar/progresoDePrograma); estos ejercitan
// LiveViewModel de verdad, con corrutinas controladas a mano (StandardTestDispatcher) para poder
// forzar el orden de llegada de las respuestas. `viewModelScope` usa Dispatchers.Main.immediate,
// que no existe en un test JVM puro sin este dispatcher de prueba -- de ahí kotlinx-coroutines-test
// como nueva dependencia testImplementation (ver el comentario en build.gradle.kts).
@OptIn(ExperimentalCoroutinesApi::class)
class LiveViewModelAsyncTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() { Dispatchers.setMain(dispatcher) }

    @After
    fun tearDown() { Dispatchers.resetMain() }

    @Test
    fun `una respuesta vieja no pisa la categoria activa mas nueva`() = runTest(dispatcher) {
        val api = FakeLiveApi()
        api.canalesPorCategoria[1] = listOf(LiveChannel("a1", "Canal A", 1, null))
        api.canalesPorCategoria[2] = listOf(LiveChannel("b1", "Canal B", 2, null))
        // La categoría 1 (A) queda esperando este gate DENTRO de canales(1) -- simula que el
        // gateway tarda en responderle a la primera categoría que el usuario tocó.
        val gateA = CompletableDeferred<Unit>()
        api.gates[1] = gateA

        val vm = LiveViewModel(api, FakeFavoriteDao(), FakeCacheDao())
        advanceUntilIdle() // termina la carga inicial (CATEGORIA_TODOS) del init, sin relación con esto

        vm.elegirCategoria(1) // A: se queda colgada en el gate
        advanceUntilIdle()
        vm.elegirCategoria(2) // B: se pide DESPUÉS, sin gate -> resuelve enseguida y gana la pantalla
        advanceUntilIdle()

        assertEquals(2, vm.estado.value.categoriaActiva)
        assertEquals(listOf("b1"), vm.estado.value.canales.map { it.code })

        gateA.complete(Unit) // A llega TARDE, después que el usuario ya está mirando B
        advanceUntilIdle()

        // La respuesta vieja de A no debe pisar lo que el usuario tiene en pantalla (B): ni el
        // chip activo ni los canales listados. Antes del fix esto fallaba con
        // categoriaActiva=2 pero canales=["a1"] -- el bug exacto que describió la review.
        assertEquals(2, vm.estado.value.categoriaActiva)
        assertEquals(listOf("b1"), vm.estado.value.canales.map { it.code })
    }

    @Test
    fun `no duplica el pedido de EPG entre la cache pintada y la respuesta fresca`() = runTest(dispatcher) {
        val api = FakeLiveApi()
        val categoria = 5
        api.canalesPorCategoria[categoria] = listOf(LiveChannel("c1", "Canal 1", 1, null))
        val cacheDao = FakeCacheDao().apply {
            // "c1" ya está en caché Y en la respuesta fresca -- exactamente el caso que duplicaba
            // el pedido de EPG antes del fix (mismo canal, dos pasos de carga distintos).
            prellenar(categoria, listOf(LiveChannelCacheEntity("c1", categoria, "Canal 1", 1, null, 0L)))
        }

        val vm = LiveViewModel(api, FakeFavoriteDao(), cacheDao)
        advanceUntilIdle() // init: CATEGORIA_TODOS, sin caché ni canales -> no pide EPG, no contamina el conteo

        vm.elegirCategoria(categoria)
        advanceUntilIdle()

        assertEquals(1, api.epgCalls.size)
        assertEquals(listOf("c1"), api.epgCalls.single())
    }

    // --- Hallazgo F3 de la revisión final: sin barrido de fondo en el servidor, la PRIMERA
    // consulta de EPG de cualquier canal casi siempre vuelve con ese canal en `missing` -el
    // worker del gateway recién llena su caché a 1,5s por canal-. Sin reintento, la guía se
    // quedaba en "Cargando programación…" hasta que el usuario sacara la fila de pantalla y la
    // volviera a meter. ---

    @Test
    fun `la EPG que vino en missing se reintenta una vez a los 10s`() = runTest(dispatcher) {
        val api = FakeLiveApi()
        val categoria = 7
        api.canalesPorCategoria[categoria] = listOf(LiveChannel("c1", "Canal 1", 1, null))
        var llamada = 0
        api.epgResponder = { codes ->
            llamada++
            if (llamada == 1) emptyMap<String, List<LiveProgram>>() to codes
            else mapOf(codes.first() to listOf(LiveProgram("Partido", 0, 10, ""))) to emptyList()
        }

        val vm = LiveViewModel(api, FakeFavoriteDao(), FakeCacheDao())
        advanceUntilIdle()
        vm.elegirCategoria(categoria)
        // runCurrent(), no advanceUntilIdle(): éste último NO se detiene en el delay(10s) del
        // reintento -avanza el reloj virtual hasta agotar TODO lo agendado, incluidas las
        // corrutinas dormidas-, así que ya habría disparado el reintento antes de este chequeo.
        runCurrent()

        // Primera vuelta: el gateway todavía no la tiene. Sin el reintento, esto se queda así.
        assertEquals(1, api.epgCalls.size)
        assertTrue(vm.estado.value.programacion["c1"].isNullOrEmpty())

        advanceTimeBy(10_000)
        runCurrent()

        assertEquals("el reintento acotado a los ~10s", 2, api.epgCalls.size)
        assertEquals(listOf("Partido"), vm.estado.value.programacion["c1"]?.map { it.titulo })
    }

    @Test
    fun `si el reintento tambien viene faltante no se encadena un tercer pedido`() = runTest(dispatcher) {
        val api = FakeLiveApi()
        val categoria = 8
        api.canalesPorCategoria[categoria] = listOf(LiveChannel("c1", "Canal 1", 1, null))
        api.epgResponder = { codes -> emptyMap<String, List<LiveProgram>>() to codes }  // nunca la tiene

        val vm = LiveViewModel(api, FakeFavoriteDao(), FakeCacheDao())
        advanceUntilIdle()
        vm.elegirCategoria(categoria)
        runCurrent()
        assertEquals(1, api.epgCalls.size)

        advanceTimeBy(10_000)
        runCurrent()
        assertEquals("el UNICO reintento acotado", 2, api.epgCalls.size)

        advanceTimeBy(60_000)
        advanceUntilIdle()  // ya no queda ningun delay agendado (reintentar=false): drenar entero es seguro
        assertEquals(
            "no debe encadenar un tercer pedido si el gateway nunca la tiene -bucle infinito",
            2,
            api.epgCalls.size,
        )
    }

    @Test
    fun `el reintento no pelea con la proteccion de pedidos duplicados`() = runTest(dispatcher) {
        val api = FakeLiveApi()
        val categoria = 9
        api.canalesPorCategoria[categoria] = listOf(LiveChannel("c1", "Canal 1", 1, null))
        api.epgResponder = { codes -> emptyMap<String, List<LiveProgram>>() to codes }

        val vm = LiveViewModel(api, FakeFavoriteDao(), FakeCacheDao())
        advanceUntilIdle()
        vm.elegirCategoria(categoria)
        runCurrent()
        assertEquals(1, api.epgCalls.size)

        // Antes de que el reintento programado dispare, otro pedido (p.ej. el usuario sacó la
        // fila de pantalla y la volvió a meter) YA pide "c1" de nuevo -y esta vez el gateway sí
        // la tiene-.
        api.epgResponder = { codes -> mapOf("c1" to listOf(LiveProgram("Ya llego", 0, 10, ""))) to emptyList() }
        vm.pedirEpgDe(listOf("c1"))
        runCurrent()
        assertEquals(2, api.epgCalls.size)
        assertEquals(listOf("Ya llego"), vm.estado.value.programacion["c1"]?.map { it.titulo })

        // El reintento programado por la carga original dispara igual, pero como "c1" ya está en
        // `programacion`, pedirEpgDe() lo descarta solo -sin pelear con epgEnVuelo ni pedir de
        // nuevo algo que ya llegó por otro camino-.
        advanceTimeBy(10_000)
        advanceUntilIdle()
        assertEquals("no debe volver a pedir lo que ya llego por otro camino", 2, api.epgCalls.size)
    }
}
