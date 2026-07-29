package com.arkiv.player.data.catalog.web

import java.util.concurrent.ConcurrentHashMap

/**
 * Caché en memoria de cookies `cf_clearance` por host, con TTL. Replica lo que hace el server (Jackett
 * guarda el cf_clearance por indexer) para no relanzar el WebView en cada búsqueda al mismo host.
 * Puro y testeable: reloj inyectable.
 */
class CfClearanceStore(
    private val ttlMs: Long = 45 * 60 * 1000L,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) {
    private data class Entry(val clearance: CfClearance, val atMs: Long)
    private val map = ConcurrentHashMap<String, Entry>()

    fun get(host: String): CfClearance? {
        val e = map[host] ?: return null
        if (nowMs() - e.atMs > ttlMs) { map.remove(host); return null }
        return e.clearance
    }

    fun put(host: String, clearance: CfClearance) {
        map[host] = Entry(clearance, nowMs())
    }

    fun hostOf(url: String): String? =
        runCatching { java.net.URL(url).host?.lowercase() }.getOrNull()?.ifBlank { null }
}
