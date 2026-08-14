package com.arkiv.player.ui.live

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.arkiv.player.data.db.LiveChannelCacheDao
import com.arkiv.player.data.db.LiveChannelCacheEntity
import com.arkiv.player.data.db.LiveFavoriteDao
import com.arkiv.player.data.db.LiveFavoriteEntity
import com.arkiv.player.data.gateway.LiveCatalogGateway
import com.arkiv.player.data.gateway.LiveCategory
import com.arkiv.player.data.gateway.LiveChannel
import com.arkiv.player.data.gateway.LiveProgram
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.Normalizer

/** Cuánto se espera antes de reintentar la EPG que el gateway devolvió en `missing`. Ver [LiveViewModel.pedirEpgDe]. */
private const val REINTENTO_EPG_MS = 10_000L

/** Id de categoría que el portal usa para "todos los canales" (no es una convención nuestra). */
const val CATEGORIA_TODOS = 76182

/** Categoría sintética (no existe en el portal): filtra por [LiveFavoriteDao] en vez del gateway. */
const val CATEGORIA_FAVORITOS = -1

private fun String.plano(): String =
    Normalizer.normalize(this, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "").lowercase()

/** Por nombre (sin tildes ni mayúsculas) o por número exacto de canal. */
fun filtrar(canales: List<LiveChannel>, texto: String): List<LiveChannel> {
    val q = texto.trim()
    if (q.isEmpty()) return canales
    val plano = q.plano()
    return canales.filter { it.nombre.plano().contains(plano) || it.numero.toString() == q }
}

/**
 * Fracción [0,1] de avance del programa en curso, para la barra fina de "Ahora en pantalla" de
 * la grilla. Blindada contra datos de EPG inconsistentes (fin <= inicio): sin esto una barra con
 * denominador 0 o negativo pinta un ancho negativo/NaN en vez de simplemente no avanzar.
 */
fun progresoDePrograma(p: LiveProgram, ahoraSegundos: Long = System.currentTimeMillis() / 1000): Float {
    val total = (p.fin - p.inicio).toFloat()
    if (total <= 0f) return 0f
    return ((ahoraSegundos - p.inicio).toFloat() / total).coerceIn(0f, 1f)
}

data class LiveUiState(
    val categorias: List<LiveCategory> = emptyList(),
    val categoriaActiva: Int = CATEGORIA_TODOS,
    val canales: List<LiveChannel> = emptyList(),
    val favoritos: Set<String> = emptySet(),
    /** El programa en curso por canal, para la grilla. */
    val ahora: Map<String, LiveProgram?> = emptyMap(),
    /** El día completo por canal, para la guía (Tarea 12). */
    val programacion: Map<String, List<LiveProgram>> = emptyMap(),
    val busqueda: String = "",
    val cargando: Boolean = false,
    val error: String? = null,
) {
    val visibles: List<LiveChannel> get() = filtrar(canales, busqueda)
}

/**
 * Estado y lógica de la pestaña "En vivo": categorías, canales, búsqueda y favoritos.
 *
 * Pinta primero lo que hay en caché (Room) y refresca contra [api] después -- ver [cargar] --
 * para que la sección abra al instante y siga mostrando la grilla si el gateway está lento o
 * caído. [com.arkiv.player.ui.live.LiveController] (la resolución de sesión por canal) y
 * [com.arkiv.player.data.db.LiveRecentDao] (recientes) los usa directamente `LiveScreen`, no este
 * ViewModel: acá solo vive lo que la guía (Tarea 12) también necesita reusar.
 *
 * [api] es [LiveCatalogGateway] y no [com.arkiv.player.data.gateway.LiveApi] a propósito: es la
 * interfaz angosta con las tres operaciones que este ViewModel de verdad consume (ver su KDoc
 * para el porqué completo). El call site de producción (`AppGraph`/`LiveScreen`) no cambia una
 * línea -- `LiveApi` implementa la interfaz, así que una instancia suya encaja acá tal cual.
 */
class LiveViewModel(
    private val api: LiveCatalogGateway,
    private val favoritosDao: LiveFavoriteDao,
    private val cacheDao: LiveChannelCacheDao,
    /**
     * Si ESTE aparato tiene destrabada la sección 18+. Se lee en cada carga, no una vez al
     * construir: destrabarla desde Ajustes tiene que verse en la próxima entrada a la guía sin
     * reiniciar la app. Por defecto `false` — el default seguro, y lo que usan los tests.
     */
    private val adultosDesbloqueado: () -> Boolean = { false },
) : ViewModel() {
    private val _estado = MutableStateFlow(LiveUiState())
    val estado: StateFlow<LiveUiState> = _estado

    /**
     * Job de la carga de categoría en curso. Cancelar el anterior antes de lanzar uno nuevo evita
     * trabajo de red desperdiciado cuando el usuario cambia de chip rápido -- pero la cancelación
     * no es instantánea ni alcanza sola para blindar el estado: `runCatching` atrapa hasta
     * `CancellationException`, así que una corrutina cancelada mientras espera una respuesta HTTP
     * puede terminar corriendo su `onFailure` de todos modos (nunca su `onSuccess`: para que
     * `runCatching` capture esa excepción, la cancelación tuvo que interceptar la llamada ANTES de
     * que devolviera datos, así que ese camino nunca llega a escribir `canales`).
     *
     * Por esta vía puntual -una corrutina cancelada que igual corre su `onFailure`- lo único que
     * se podría corromper sin el chequeo de `categoriaActiva` dentro de [cargar] es `error`/
     * `cargando`: la categoría VIEJA escribiendo "no se pudo cargar" encima de la que el usuario ya
     * está mirando. El caso de `canales` pisado por una respuesta vieja es una carrera DISTINTA
     * -dos pedidos EXITOSOS que vuelven fuera de orden, sin que medie cancelación- y la cubre el
     * mismo chequeo pero del lado de `onSuccess` (ver el KDoc de [cargar] para ese caso, que sí es
     * el que se midió en review). La cancelación acá es una optimización de "gastar menos"; el
     * chequeo de `categoriaActiva` en cada rama es la garantía de corrección de lo que esa rama
     * puede llegar a escribir.
     */
    private var cargaJob: Job? = null

    /**
     * Códigos con un pedido de EPG en vuelo ahora mismo -- ver KDoc de [pedirEpgDe]. Vive fuera del
     * StateFlow a propósito: es contabilidad interna de "quién ya está pidiendo qué", no algo que
     * la UI pinte. Sin sincronización explícita porque todo esto corre confinado a
     * `Dispatchers.Main` (el dispatcher de `viewModelScope`): las llamadas nunca se solapan entre
     * hilos, solo se intercalan en el mismo hilo.
     */
    private val epgEnVuelo = mutableSetOf<String>()

    init {
        viewModelScope.launch {
            favoritosDao.flowTodos().collect { favs ->
                _estado.update { it.copy(favoritos = favs.map { f -> f.code }.toSet()) }
            }
        }
        cargar(CATEGORIA_TODOS)
    }

    fun elegirCategoria(id: Int) = cargar(id)

    fun buscar(texto: String) = _estado.update { it.copy(busqueda = texto) }

    /**
     * Pinta primero lo que hay en la caché local y después refresca contra el gateway.
     * Así la sección abre al instante y sigue mostrando la grilla si el gateway está
     * lento o caído -- en ese caso solo falla al reproducir, con un mensaje concreto.
     *
     * Tocar chips rápido es la interacción NORMAL de esta pantalla, no un caso raro: si la
     * respuesta de una categoría vieja (A) llega después que la de la categoría que el usuario
     * ya está mirando (B), esa respuesta tardía no debe pisar lo que hay en pantalla -- por eso
     * cada punto que escribe `canales`/`error` primero comprueba que `categoria` siga siendo
     * `categoriaActiva`. Sin ese chequeo, el chip seleccionado terminaba siendo B con los canales
     * de A (medido en review). La caché SÍ se escribe siempre aunque la respuesta llegue tarde:
     * sirve para la próxima vez que se pida esa categoría, no solo para esta pantalla.
     */
    private fun cargar(categoria: Int) {
        cargaJob?.cancel()
        cargaJob = viewModelScope.launch {
            _estado.update { it.copy(cargando = true, error = null, categoriaActiva = categoria) }

            if (categoria == CATEGORIA_FAVORITOS) {
                val favs = favoritosDao.flowTodos().first()
                if (_estado.value.categoriaActiva != categoria) return@launch
                val canales = favs.map { LiveChannel(it.code, it.nombre, it.numero, it.logo) }
                _estado.update { it.copy(canales = canales, cargando = false) }
                pedirEpgDe(canales.take(40).map { it.code })
                return@launch
            }

            val cacheados = cacheDao.deCategoria(categoria)
                .map { LiveChannel(it.code, it.nombre, it.numero, it.logo) }
            if (cacheados.isNotEmpty() && _estado.value.categoriaActiva == categoria) {
                _estado.update { it.copy(canales = cacheados, cargando = false) }
                pedirEpgDe(cacheados.take(40).map { it.code })
            }

            runCatching {
                if (_estado.value.categorias.isEmpty()) {
                    val cats = api.categorias(incluirAdultos = adultosDesbloqueado())
                    _estado.update { it.copy(categorias = cats) }
                }
                api.canales(categoria)
            }.onSuccess { frescos ->
                val ahoraMs = System.currentTimeMillis()
                cacheDao.reemplazar(categoria, frescos.map {
                    LiveChannelCacheEntity(it.code, categoria, it.nombre, it.numero, it.logo, ahoraMs)
                })
                if (_estado.value.categoriaActiva == categoria) {
                    _estado.update { it.copy(canales = frescos, cargando = false, error = null) }
                    pedirEpgDe(frescos.take(40).map { it.code })
                }
            }.onFailure {
                // Con caché ya pintada, un gateway caído no vacía la pantalla.
                if (_estado.value.categoriaActiva == categoria) {
                    _estado.update {
                        it.copy(
                            cargando = false,
                            error = if (it.canales.isEmpty()) "No se pudo cargar los canales" else null,
                        )
                    }
                }
            }
        }
    }

    /**
     * Programación de esos canales: guarda el día completo (lo usa la guía) y deriva el
     * programa en curso (lo usa la grilla). Lo que el gateway todavía no tenga llega en
     * una vuelta posterior; acá no se espera a nadie.
     *
     * [cargar] llama a esto DOS veces por carga normal (al pintar la caché y otra vez con la
     * respuesta fresca), casi siempre con la misma lista de códigos. El filtro original miraba
     * solo `programacion` (lo que YA volvió), y la primera llamada todavía no había vuelto cuando
     * la segunda miraba ese mapa -- lo encontraba vacío y pedía la EPG de nuevo. Medido: el
     * gateway recibía DOS pedidos de EPG idénticos por cada carga normal, contra un endpoint
     * limitado a 1 pedido cada 1,5s, global. [epgEnVuelo] marca un código como "pedido" ANTES de
     * lanzar la corrutina (no después de que vuelva), así la segunda llamada lo ve y lo descarta.
     *
     * No hay barrido de fondo en el servidor (decisión tomada aparte): el worker del gateway
     * recién llena su caché por canal a 1,5s cada uno, así que la PRIMERA consulta de cualquier
     * canal casi siempre vuelve con ese canal en `missing` -EPG vacía, no un error-. Sin más, la
     * guía se quedaba en "Cargando programación…" hasta que el usuario sacara esa fila de pantalla
     * y la volviera a meter (hallazgo F3 de la revisión final). [reintentar] hace un solo reintento
     * acotado -a los [REINTENTO_EPG_MS]- de lo que vino en `missing`: `false` en la llamada
     * recursiva de más abajo corta la cadena ahí, así un canal que el gateway nunca llegue a tener
     * no dispara reintentos para siempre. El reintento reusa esta misma función -y por lo tanto
     * [epgEnVuelo]- así que no compite con la protección de pedidos duplicados: si para cuando
     * corre ya llegó por otro camino (otra carga, otro scroll), el filtro de `faltantes` de abajo
     * lo descarta solo.
     */
    fun pedirEpgDe(codes: List<String>, reintentar: Boolean = true) {
        val faltantes = codes.filter { it !in _estado.value.programacion && it !in epgEnVuelo }
        if (faltantes.isEmpty()) return
        epgEnVuelo.addAll(faltantes)
        viewModelScope.launch {
            var siguenFaltando: List<String> = emptyList()
            try {
                runCatching { api.epg(faltantes) }.onSuccess { (mapa, faltan) ->
                    siguenFaltando = faltan
                    val instante = System.currentTimeMillis() / 1000
                    val enCurso = mapa.mapValues { (_, progs) ->
                        progs.firstOrNull { p -> instante >= p.inicio && instante < p.fin }
                    }
                    _estado.update {
                        it.copy(programacion = it.programacion + mapa, ahora = it.ahora + enCurso)
                    }
                }
            } finally {
                // Pase lo que pase (éxito o fallo): libera los códigos para que un pedido futuro
                // -otra carga, otro scroll- pueda reintentarlos. Un fallo no debe bloquearlos para
                // siempre.
                epgEnVuelo.removeAll(faltantes)
            }
            if (reintentar && siguenFaltando.isNotEmpty()) {
                delay(REINTENTO_EPG_MS)
                pedirEpgDe(siguenFaltando, reintentar = false)
            }
        }
    }

    fun alternarFavorito(c: LiveChannel) {
        viewModelScope.launch {
            if (c.code in _estado.value.favoritos) favoritosDao.borrar(c.code)
            else favoritosDao.guardar(LiveFavoriteEntity(c.code, c.nombre, c.numero, c.logo))
        }
    }
}
