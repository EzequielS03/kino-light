package com.arkiv.player.data.catalog.web

import com.arkiv.player.data.catalog.providers.SearchContext
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch

/**
 * Fan-out sobre TODAS las webs activas del registro. Lee las definiciones FRESCAS en cada llamada
 * (vía [definitions]) para reflejar el hot-update remoto sin reconstruir. Cada web corre en su
 * corrutina con runCatching: el fallo de una no rompe a las demás.
 */
class WebSourceEngine(
    private val fetcher: PageFetcher,
    private val definitions: () -> List<WebSourceDefinition>,
) {
    // Cache en memoria de resultados web por búsqueda (mismo patrón que TorrentSearchApi.rawCache):
    // re-abrir la misma peli/episodio devuelve al instante sin re-consultar las webs (que son lentas).
    private data class CacheEntry(val results: List<WebResult>, val atMs: Long)
    private val rawCache = java.util.concurrent.ConcurrentHashMap<String, CacheEntry>()
    private val cacheTtlMs = 30 * 60 * 1000L // 30 min
    private fun cacheKey(ctx: SearchContext) =
        ctx.titles.map { it.trim().lowercase() }.sorted().joinToString("|") +
            "#${ctx.type}#${ctx.season}#${ctx.episode}#${ctx.year}"

    suspend fun browse(kind: String, page: Int): List<WebResult> = coroutineScope {
        definitions().filter { it.enabled }.map { def ->
            async { runCatching { WebSourceBackend(def, fetcher).browse(kind, page) }.getOrDefault(emptyList()) }
        }.awaitAll().flatten()
    }

    suspend fun search(ctx: SearchContext): List<WebResult> = coroutineScope {
        definitions().filter { it.enabled }.map { def ->
            async { runCatching { WebSourceBackend(def, fetcher).search(ctx) }.getOrDefault(emptyList()) }
        }.awaitAll().flatten()
    }

    /**
     * Igual que [search] pero PROGRESIVO: emite los resultados de CADA web apenas esa web termina,
     * en vez de esperar a todas. El Flow se completa cuando terminaron todas. Una web que falla o no
     * devuelve nada no emite (no rompe a las demás). Ideal para ir mostrando resultados en la UI.
     */
    fun searchFlow(ctx: SearchContext): Flow<List<WebResult>> = channelFlow {
        // Cache hit: emite el resultado cacheado de una y no re-consulta las webs.
        val key = cacheKey(ctx)
        val now = System.currentTimeMillis()
        rawCache[key]?.takeIf { now - it.atMs < cacheTtlMs }?.let { hit ->
            if (hit.results.isNotEmpty()) send(hit.results)
            return@channelFlow
        }
        val active = definitions().filter { it.enabled }
        runCatching {
            android.util.Log.i(
                "ArkivWeb",
                "searchFlow ctx: titles=${ctx.titles} type=${ctx.type} s=${ctx.season} e=${ctx.episode} year=${ctx.year} · webs activas=${active.map { it.id }}",
            )
        }
        // Acumula lo emitido para cachearlo al terminar todas las webs (coroutineScope espera a todas).
        val accumulated = java.util.concurrent.CopyOnWriteArrayList<WebResult>()
        coroutineScope {
            active.forEach { def ->
                launch {
                    val r = runCatching { WebSourceBackend(def, fetcher).search(ctx) }.getOrDefault(emptyList())
                    if (r.isNotEmpty()) { accumulated.addAll(r); send(r) }
                }
            }
        }
        if (accumulated.isNotEmpty()) rawCache[key] = CacheEntry(accumulated.toList(), now)
    }
}
