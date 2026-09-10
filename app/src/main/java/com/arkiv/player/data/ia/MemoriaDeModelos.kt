package com.arkiv.player.data.ia

import org.json.JSONObject

/** Dónde se persiste la memoria. En producción, SharedPreferences (ver `AlmacenEnPreferencias`). */
internal interface AlmacenDeMemoria {
    fun leer(): String?
    fun guardar(json: String)
}

/** Por qué falló un modelo, que decide cuánto se lo deja en espera. */
internal sealed interface Falla {
    /** Un 429. [retryAfterMs] es lo que dijo `Retry-After`, o null si no dijo nada. */
    data class Limite(val retryAfterMs: Long?) : Falla
    /** Un 5xx, un error de red o una demora de más de 45 s. */
    data object Servidor : Falla
    /** Contestó, pero no se pudo leer. NO castiga: ver el KDoc de [MemoriaDeModelos]. */
    data object Ilegible : Falla
}

/**
 * Qué modelos probar primero y cuáles dejar en espera, según lo que funcionó en ESTE aparato.
 *
 * No hay sondas ni puntajes de servidor: se cuentan éxitos y fallos por modelo, persistidos, y el
 * orden sale de ahí. El tope ([TOPE_CUENTA]) evita que una historia larga deje a un modelo
 * enterrado para siempre.
 *
 * **Una respuesta ilegible no castiga.** En llm-libre costó nueve rondas de revisión aprender que un
 * fallo del lado del cliente no puede excluir una ruta.
 *
 * No es segura entre hilos: [ClienteDeIa] la usa siempre bajo su `Mutex`.
 */
internal class MemoriaDeModelos(
    private val almacen: AlmacenDeMemoria,
    private val ahoraMs: () -> Long,
) {
    private data class Registro(val exitos: Int = 0, val fallos: Int = 0, val esperaHastaMs: Long = 0L)

    private val registros: MutableMap<String, Registro> = cargar()

    fun ordenar(modelos: List<ModeloDeKilo>): List<ModeloDeKilo> {
        val ahora = ahoraMs()
        return modelos
            .filter { (registros[it.id]?.esperaHastaMs ?: 0L) <= ahora }
            // `sortedByDescending` es estable: sin historia se conserva el orden del catálogo.
            .sortedByDescending { puntaje(registros[it.id]) }
    }

    fun exito(id: String) {
        val r = registros[id] ?: Registro()
        registros[id] = r.copy(exitos = (r.exitos + 1).coerceAtMost(TOPE_CUENTA), esperaHastaMs = 0L)
        guardar()
    }

    fun fallo(id: String, falla: Falla) {
        val ahora = ahoraMs()
        val r = registros[id] ?: Registro()
        registros[id] = when (falla) {
            is Falla.Limite -> r.copy(esperaHastaMs = ahora + (falla.retryAfterMs ?: ESPERA_POR_LIMITE_MS))
            Falla.Servidor -> r.copy(
                fallos = (r.fallos + 1).coerceAtMost(TOPE_CUENTA),
                esperaHastaMs = ahora + ESPERA_POR_SERVIDOR_MS,
            )
            Falla.Ilegible -> return
        }
        guardar()
    }

    private fun puntaje(r: Registro?): Int = if (r == null) 0 else r.exitos - 2 * r.fallos

    private fun cargar(): MutableMap<String, Registro> {
        val salida = mutableMapOf<String, Registro>()
        val json = runCatching { almacen.leer()?.let { JSONObject(it) } }.getOrNull() ?: return salida
        for (id in json.keys()) {
            val o = json.optJSONObject(id) ?: continue
            salida[id] = Registro(o.optInt("e"), o.optInt("f"), o.optLong("h"))
        }
        return salida
    }

    private fun guardar() {
        val json = JSONObject()
        registros.forEach { (id, r) ->
            json.put(id, JSONObject().put("e", r.exitos).put("f", r.fallos).put("h", r.esperaHastaMs))
        }
        runCatching { almacen.guardar(json.toString()) }
    }

    internal companion object {
        const val ESPERA_POR_LIMITE_MS = 10 * 60 * 1000L
        const val ESPERA_POR_SERVIDOR_MS = 5 * 60 * 1000L
        const val TOPE_CUENTA = 20
    }
}
