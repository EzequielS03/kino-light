package com.arkiv.player.data.ia

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** No se pudo sacar el JSON pedido del texto del modelo. */
internal class JsonIlegible(mensaje: String) : Exception(mensaje)

/**
 * Sacar el JSON de lo que contesta un modelo, aunque venga envuelto.
 *
 * Port de `arkiv-api/src/arkiv_api/llm_json.py`: pedirle al modelo que no envuelva en
 * ```` ```json ```` no alcanza, y tirar una respuesta buena por el envoltorio sería perder el
 * trabajo que ya se hizo.
 */
internal object JsonDelModelo {

    private val CERCO = Regex("^```(?:json)?|```$", RegexOption.MULTILINE)

    private fun sinCerco(texto: String): String = texto.trim().replace(CERCO, "").trim()

    fun arreglo(texto: String): JSONArray {
        val crudo = sinCerco(texto)
        val inicio = crudo.indexOf('[')
        val fin = crudo.lastIndexOf(']')
        if (inicio < 0 || fin < inicio) throw JsonIlegible("no vino ningún arreglo JSON")
        return try {
            JSONArray(crudo.substring(inicio, fin + 1))
        } catch (e: JSONException) {
            throw JsonIlegible("JSON inválido: ${e.message}")
        }
    }

    /**
     * Como [arreglo], pero para UN objeto. Un arreglo NO cuenta: si un corchete abre antes que la
     * primera llave, lo que llegó ES una lista, y el objeto encontrado sería un elemento suyo.
     */
    fun objeto(texto: String): JSONObject {
        val crudo = sinCerco(texto)
        val inicio = crudo.indexOf('{')
        val fin = crudo.lastIndexOf('}')
        if (inicio < 0 || fin < inicio) throw JsonIlegible("no vino ningún objeto JSON")
        val corchete = crudo.indexOf('[')
        if (corchete in 0 until inicio) throw JsonIlegible("vino un arreglo donde iba un objeto")
        return try {
            JSONObject(crudo.substring(inicio, fin + 1))
        } catch (e: JSONException) {
            throw JsonIlegible("JSON inválido: ${e.message}")
        }
    }
}
