package com.arkiv.player.data.ia

import org.json.JSONObject

/** Un modelo de Kilo que sirve para esta app: gratis, con `tools`, y de chat de verdad. */
internal data class ModeloDeKilo(val id: String)

/**
 * Qué modelos del catálogo de Kilo sirven. Las reglas son las de
 * `llm-libre/src/llm_libre/catalog.py`.
 *
 * - **Gratis** es `pricing.prompt == 0`, nada más.
 * - **`tools`** no es porque se usen: exigirlo deja afuera los frentes que empalman su propio texto
 *   dentro del `content`, y la app dibuja ese texto tal cual en la pantalla.
 * - **Descarte por lo que el modelo dice de sí mismo** (`name` y `description`), no por su id: una
 *   lista negra de ids se pudre; un guardrail que aparezca mañana con otro nombre se va a seguir
 *   describiendo como guardrail. En llm-libre, `nvidia/nemotron-3.5-content-safety:free` —un
 *   clasificador que responde "User Safety: safe" a todo— llegó a ser el primero del ranking.
 */
internal object CatalogoDeKilo {

    private val DESCARTE = Regex(
        listOf(
            // Especialidades que no son chat.
            "guardrail", "content safety", "\\bmoderation\\b", "\\bmoderates\\b", "\\bclassifier\\b",
            "\\breranker\\b", "\\bre-ranker\\b", "\\breranking\\b",
            "embeddings? model", "text embeddings?\\b",
            "speech[- ]to[- ]text", "text[- ]to[- ]speech",
            // Meta-routers: no son un modelo, son una lotería entre otros.
            "\\bmodels? router\\b", "\\bis a router\\b", "rotates through",
            "\\brouter\\b.{0,60}\\bselects\\b", "selects .{0,40}\\bmodels\\b.{0,20}at random",
        ).joinToString("|"),
        RegexOption.IGNORE_CASE,
    )

    fun candidatos(json: JSONObject): List<ModeloDeKilo> {
        val data = json.optJSONArray("data") ?: return emptyList()
        return (0 until data.length()).mapNotNull { i ->
            val m = data.optJSONObject(i) ?: return@mapNotNull null
            val id = m.optString("id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            if (!esGratis(m)) return@mapNotNull null
            if (!aceptaTools(m)) return@mapNotNull null
            if (DESCARTE.containsMatchIn(m.optString("name") + " " + m.optString("description"))) {
                return@mapNotNull null
            }
            ModeloDeKilo(id)
        }
    }

    fun esGratis(modelo: JSONObject): Boolean {
        val precio = modelo.optJSONObject("pricing")?.opt("prompt") ?: return false
        return precio.toString().toDoubleOrNull() == 0.0
    }

    private fun aceptaTools(modelo: JSONObject): Boolean {
        val params = modelo.optJSONArray("supported_parameters") ?: return false
        return (0 until params.length()).any { params.optString(it) == "tools" }
    }
}
