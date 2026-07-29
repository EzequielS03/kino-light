package com.arkiv.player.data.catalog.web

import android.util.Log
import org.json.JSONArray

/** Carga y mergea definiciones web (asset bundled + remota de blog). Espejo de ProviderRegistry. */
object WebSourceRegistry {

    fun parseDefinitions(json: String): List<WebSourceDefinition> = runCatching {
        val arr = JSONArray(json)
        (0 until arr.length()).mapNotNull { i ->
            arr.optJSONObject(i)?.let { WebSourceDefinition.fromJson(it) }
        }
    }.onFailure { runCatching { Log.w("ArkivWeb", "web_sources.json ilegible: $it") } }.getOrDefault(emptyList())

    /** La versión remota (de blog) gana sobre la bundled cuando comparten `id`. */
    fun merge(bundled: List<WebSourceDefinition>, remote: List<WebSourceDefinition>): List<WebSourceDefinition> {
        val byId = LinkedHashMap<String, WebSourceDefinition>()
        bundled.forEach { byId[it.id] = it }
        remote.forEach { byId[it.id] = it }
        return byId.values.sortedByDescending { it.priority }
    }
}
