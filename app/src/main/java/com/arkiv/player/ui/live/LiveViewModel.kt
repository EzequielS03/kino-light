package com.arkiv.player.ui.live

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.arkiv.player.data.db.LiveChannelCacheDao
import com.arkiv.player.data.db.LiveChannelCacheEntity
import com.arkiv.player.data.db.LiveFavoriteDao
import com.arkiv.player.data.db.LiveFavoriteEntity
import com.arkiv.player.data.gateway.LiveApi
import com.arkiv.player.data.gateway.LiveCategory
import com.arkiv.player.data.gateway.LiveChannel
import com.arkiv.player.data.gateway.LiveProgram
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.Normalizer

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
 */
class LiveViewModel(
    private val api: LiveApi,
    private val favoritosDao: LiveFavoriteDao,
    private val cacheDao: LiveChannelCacheDao,
) : ViewModel() {
    private val _estado = MutableStateFlow(LiveUiState())
    val estado: StateFlow<LiveUiState> = _estado

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
     */
    private fun cargar(categoria: Int) {
        viewModelScope.launch {
            _estado.update { it.copy(cargando = true, error = null, categoriaActiva = categoria) }

            if (categoria == CATEGORIA_FAVORITOS) {
                val favs = favoritosDao.flowTodos().first()
                val canales = favs.map { LiveChannel(it.code, it.nombre, it.numero, it.logo) }
                _estado.update { it.copy(canales = canales, cargando = false) }
                pedirEpgDe(canales.take(40).map { it.code })
                return@launch
            }

            val cacheados = cacheDao.deCategoria(categoria)
                .map { LiveChannel(it.code, it.nombre, it.numero, it.logo) }
            if (cacheados.isNotEmpty()) {
                _estado.update { it.copy(canales = cacheados, cargando = false) }
                pedirEpgDe(cacheados.take(40).map { it.code })
            }

            runCatching {
                if (_estado.value.categorias.isEmpty()) {
                    val cats = api.categorias()
                    _estado.update { it.copy(categorias = cats) }
                }
                api.canales(categoria)
            }.onSuccess { frescos ->
                val ahoraMs = System.currentTimeMillis()
                cacheDao.reemplazar(categoria, frescos.map {
                    LiveChannelCacheEntity(it.code, categoria, it.nombre, it.numero, it.logo, ahoraMs)
                })
                _estado.update { it.copy(canales = frescos, cargando = false, error = null) }
                pedirEpgDe(frescos.take(40).map { it.code })
            }.onFailure {
                // Con caché ya pintada, un gateway caído no vacía la pantalla.
                _estado.update {
                    it.copy(
                        cargando = false,
                        error = if (it.canales.isEmpty()) "No se pudo cargar los canales" else null,
                    )
                }
            }
        }
    }

    /**
     * Programación de esos canales: guarda el día completo (lo usa la guía) y deriva el
     * programa en curso (lo usa la grilla). Lo que el gateway todavía no tenga llega en
     * una vuelta posterior; acá no se espera a nadie.
     */
    fun pedirEpgDe(codes: List<String>) {
        val faltantes = codes.filter { it !in _estado.value.programacion }
        if (faltantes.isEmpty()) return
        viewModelScope.launch {
            runCatching { api.epg(faltantes) }.onSuccess { (mapa, _) ->
                val instante = System.currentTimeMillis() / 1000
                val enCurso = mapa.mapValues { (_, progs) ->
                    progs.firstOrNull { p -> instante >= p.inicio && instante < p.fin }
                }
                _estado.update {
                    it.copy(programacion = it.programacion + mapa, ahora = it.ahora + enCurso)
                }
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
