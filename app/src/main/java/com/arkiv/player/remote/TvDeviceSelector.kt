package com.arkiv.player.remote

import org.json.JSONObject

/**
 * Elige a qué TV apuntar cuando hay varios `kind=tv` en la cuenta: evita quedarse con uno viejo o
 * fantasma. Extraído de RemoteController.resolveTvId para poder testearlo y reusarlo.
 */
object TvDeviceSelector {
    fun pick(records: List<JSONObject>): JSONObject? = records.sortedWith(
        compareByDescending<JSONObject> { it.optBoolean("online", false) }
            .thenByDescending { it.optString("lastSeen") },
    ).firstOrNull()
}
