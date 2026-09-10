package com.arkiv.player.data.ditu

import org.json.JSONObject

/**
 * Cliente de mentira para probar las capas de arriba sin red. Guarda lo que le pidieron —el test
 * afirma sobre la RUTA y los PARÁMETROS, que es donde están los errores de puerto— y devuelve el
 * JSON que se le haya cargado para esa ruta.
 */
internal class FakeDituCliente : DituClienteLike {
    val llamadas = mutableListOf<Pair<String, Map<String, String>>>()
    private val respuestas = mutableMapOf<String, JSONObject>()
    var token: String = ""
    var falla: Throwable? = null

    fun responde(path: String, json: String) {
        respuestas[path] = JSONObject(json)
    }

    override suspend fun get(path: String, params: Map<String, String>): JSONObject {
        llamadas += path to params
        falla?.let { throw it }
        return respuestas[path] ?: JSONObject("""{"resultObj":{"containers":[]}}""")
    }

    override suspend fun getConToken(path: String): DituRespuesta =
        DituRespuesta(get(path, emptyMap()), token)
}
