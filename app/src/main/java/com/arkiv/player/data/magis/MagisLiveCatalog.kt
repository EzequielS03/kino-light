package com.arkiv.player.data.magis

import com.arkiv.player.data.gateway.LiveCatalogGateway
import com.arkiv.player.data.gateway.LiveCategory
import com.arkiv.player.data.gateway.LiveChannel
import com.arkiv.player.data.gateway.LiveProgram
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

/**
 * Categorías y canales de TV en vivo, directo del portal. Implementa la misma
 * [LiveCatalogGateway] que traía el gateway, así que las pantallas de vivo no cambian.
 *
 * El plan del sub-proyecto no incluía esto (solo la resolución del canal), pero sin el listado
 * la sección de vivo seguiría pidiéndole el catálogo al servidor — justo lo que esta rama saca.
 *
 * Hay caché en memoria porque el portal tiene un ritmo mínimo entre llamadas y la lista completa
 * son 3 páginas: sin caché, abrir el cajón de canales costaría ~1,5 s cada vez. Se pierde al morir
 * el proceso, que es exactamente lo que se quiere (el `main_addr` de la resolución no se cachea,
 * pero el catálogo sí se puede).
 */
internal class MagisLiveCatalog(
    private val catalogo: MagisCatalog,
    private val portal: MagisPortalClientLike,
    private val session: MagisSession,
    private val ahoraMs: () -> Long = { System.currentTimeMillis() },
) : LiveCatalogGateway {

    private val candado = Mutex()
    private var categoriasCache: List<CategoriaDePortal> = emptyList()
    private var categoriasVencen = 0L
    private val canalesCache = mutableMapOf<Int, Pair<Long, List<LiveChannel>>>()

    /** Una categoría tal como la entiende el portal, con la marca de adultos que él no trae. */
    private data class CategoriaDePortal(val id: Int, val nombre: String, val adulto: Boolean)

    override suspend fun categorias(incluirAdultos: Boolean): List<LiveCategory> =
        todasLasCategorias()
            .filter { incluirAdultos || !it.adulto }
            .map { LiveCategory(id = it.id, nombre = it.nombre) }

    /**
     * TODOS los canales de la categoría, no la primera página. Medido contra el portal el
     * 2026-08-14: la categoría "Todos" (76182) devuelve 500 por página y tiene TRES páginas sin un
     * código repetido — 1040 canales de verdad. Pidiendo una sola, la guía, el cajón y el zapeo
     * trabajaban con el 48% del catálogo y el buscador no podía encontrar lo que nunca se cargó.
     */
    override suspend fun canales(categoria: Int): List<LiveChannel> {
        candado.withLock {
            canalesCache[categoria]?.takeIf { ahoraMs() < it.first }?.let { return it.second }
        }
        val esAdulta = todasLasCategorias().any { it.id == categoria && it.adulto }
        val todos = mutableListOf<LiveChannel>()
        val vistos = mutableSetOf<String>()
        for (pagina in 1..MAX_PAGINAS) {
            val lote = unaPagina(categoria, pagina, esAdulta) ?: break
            val nuevos = lote.filter { vistos.add(it.code) }
            todos.addAll(nuevos)
            // Una página incompleta es la última. Sin este corte se seguiría pidiendo hasta el
            // tope, y cada pedido de más gasta un turno del ritmo del portal para no traer nada.
            if (lote.size < PAGINA || nuevos.isEmpty()) break
        }
        if (todos.isNotEmpty()) {
            candado.withLock { canalesCache[categoria] = (ahoraMs() + TTL_MS) to todos }
        }
        return todos
    }

    /**
     * El portal NO tiene programación: lo que publica como "EPG" son endpoints de deportes, no una
     * guía (comprobado; está fuera del alcance del sub-proyecto). Se contesta "no sé de ninguno",
     * que es lo que la UI ya sabe mostrar, en vez de inventar horarios.
     */
    override suspend fun epg(codes: List<String>): Pair<Map<String, List<LiveProgram>>, List<String>> =
        emptyMap<String, List<LiveProgram>>() to codes

    private suspend fun todasLasCategorias(): List<CategoriaDePortal> {
        candado.withLock {
            if (categoriasCache.isNotEmpty() && ahoraMs() < categoriasVencen) return categoriasCache
        }
        // `pageSize` 200 y no 30: con 30 el portal devolvía exactamente 30 —el número redondo era
        // el corte, no el total— y se perdían OCHO categorías enteras (las reales son 38).
        val r = catalogo.nextColumns(RAIZ_DE_VIVO, tamano = 200)
        val lista = r.dato()?.optJSONArray("recommendList") ?: return emptyList()
        val salida = mutableListOf<CategoriaDePortal>()
        lista.forEachObjeto { c ->
            val id = c.opt("columnId")?.toString()?.toIntOrNull() ?: return@forEachObjeto
            val nombre = nombreDeCategoria(c.optString("name"))
            salida.add(CategoriaDePortal(id = id, nombre = nombre, adulto = esDeAdultos(nombre)))
        }
        if (salida.isNotEmpty()) {
            candado.withLock {
                categoriasCache = salida
                categoriasVencen = ahoraMs() + TTL_MS
            }
        }
        return salida
    }

    /** `null` = el portal no contestó esta página (distinto de "la categoría no tiene más"). */
    private suspend fun unaPagina(
        columnId: Int,
        pagina: Int,
        esAdulta: Boolean,
    ): List<LiveChannel>? {
        val sesion = session.ensureSession()
        if (sesion !is MagisResult.Ok) return null
        val r = session.conSesionValida {
            portal.call(
                path = "v6/getLiveData",
                bean = mapOf(
                    "columnId" to columnId,
                    "pageNum" to pagina,
                    "pageSize" to PAGINA,
                    "dataVersion" to "",
                    "expireTimeStr" to "",
                ),
                userId = session.userId,
                userToken = session.userToken,
            )
        }
        val lista = r.dato()?.optJSONArray("channelList") ?: return null
        val salida = mutableListOf<LiveChannel>()
        lista.forEachObjeto { c ->
            val code = c.optString("channelCode").takeIf { it.isNotBlank() } ?: return@forEachObjeto
            salida.add(
                LiveChannel(
                    code = code,
                    nombre = c.optString("name"),
                    numero = c.opt("channelNumber")?.toString()?.toIntOrNull() ?: 0,
                    logo = logoDe(c),
                    // Marcado en el CANAL y no solo en la categoría: el canal viaja solo hasta el
                    // reproductor (zapeo, deep link, recientes) y ahí ya no hay categoría a mano.
                    adulto = esAdulta,
                ),
            )
        }
        return salida
    }

    private companion object {
        const val RAIZ_DE_VIVO = "masnew_live"
        const val PAGINA = 500
        const val MAX_PAGINAS = 6
        const val TTL_MS = 6 * 60 * 60 * 1000L

        /** El portal llama "ChannelList" a la categoría de todos los canales — un nombre interno
         *  suyo, en inglés, que terminaba tal cual en la pantalla. */
        val NOMBRES = mapOf("ChannelList" to "Todos")

        /** Se reconocen por NOMBRE porque es lo único que da el portal: no hay ningún campo que
         *  las marque. */
        val DE_ADULTOS = setOf("18+", "adultos", "adulto", "xxx", "+18")

        fun nombreDeCategoria(nombre: String): String = NOMBRES[nombre] ?: nombre

        fun esDeAdultos(nombre: String): Boolean = nombre.trim().lowercase() in DE_ADULTOS

        /**
         * La imagen del canal en la forma REAL que manda el portal (diagnosticada en producción):
         * NO hay `logo`/`icon`/`logoUrl` ni `posterList[].url` — esos campos eran especulativos y
         * nunca vienen. Sí viene `posterList[]` con `fileType`/`fileUrl` (el `fileType` es el único
         * criterio confiable, no el orden ni el tamaño) y, como respaldo, `posterUrl` suelta.
         */
        fun logoDe(canal: JSONObject): String? {
            canal.optJSONArray("posterList")?.forEachObjeto { p ->
                if (p.optString("fileType") != "icon") return@forEachObjeto
                p.optString("fileUrl").takeIf { it.isNotBlank() }?.let { return it }
            }
            return canal.optString("posterUrl").takeIf { it.isNotBlank() }
        }
    }
}
