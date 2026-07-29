package com.arkiv.player.cloudsync

import android.content.Context

/**
 * Cuenta los fallos de subida POR FILA para poder dejar pasar el cursor sin perder datos.
 *
 * El cursor de push no puede saltarse una fila que falló ([PushFrontier]), pero si una fila es
 * inválida de forma permanente (p. ej. un campo que excede el límite del servidor) atascaría la
 * colección para siempre. Tras [MAX_INTENTOS] fallos seguidos la fila queda EN CUARENTENA: se la
 * deja de reintentar y el cursor puede avanzar, pero queda registrada y logueada — a diferencia
 * del comportamiento anterior, que la enterraba en silencio.
 */
class SyncQuarantine(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences("arkiv_cloudsync_quarantine", Context.MODE_PRIVATE)

    private fun k(col: String, key: String) = "$col::$key"

    fun fallos(col: String, key: String): Int = prefs.getInt(k(col, key), 0)

    /** Registra un fallo y devuelve el total acumulado de esa fila. */
    fun registrarFallo(col: String, key: String): Int {
        val n = fallos(col, key) + 1
        prefs.edit().putInt(k(col, key), n).apply()
        return n
    }

    /** La fila subió bien: se limpia el historial. */
    fun limpiar(col: String, key: String) {
        if (prefs.contains(k(col, key))) prefs.edit().remove(k(col, key)).apply()
    }

    fun enCuarentena(col: String, key: String): Boolean = fallos(col, key) >= MAX_INTENTOS

    /** Filas rendidas, para poder mostrarlas/diagnosticarlas. */
    fun listar(): List<String> = prefs.all.entries
        .filter { (it.value as? Int ?: 0) >= MAX_INTENTOS }
        .map { it.key }

    companion object {
        const val MAX_INTENTOS = 5
    }
}
