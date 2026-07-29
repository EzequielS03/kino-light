package com.arkiv.player.cloudsync

import android.content.Context

/**
 * Cursores de sincronización persistidos en SharedPreferences: por cada colección, guarda hasta
 * qué `updatedAt` ya se empujó (push) y hasta cuál ya se trajo (pull) desde PocketBase.
 */
class SyncCursors(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("arkiv_cloudsync", Context.MODE_PRIVATE)

    fun lastPushed(c: String): Long = prefs.getLong("push_$c", 0)
    fun setLastPushed(c: String, v: Long) { prefs.edit().putLong("push_$c", v).apply() }

    fun lastPulled(c: String): Long = prefs.getLong("pull_$c", 0)
    fun setLastPulled(c: String, v: Long) { prefs.edit().putLong("pull_$c", v).apply() }

    /**
     * Reinicia TODOS los cursores para forzar un sync completo (reparación manual).
     *
     * Hace falta porque un cursor dañado entierra datos de forma permanente: el de push pudo haber
     * pasado por encima de filas que nunca llegaron al servidor, y el de pull pudo haberse saltado
     * registros subidos tarde con fecha vieja. Volver a cero es seguro: el upsert es idempotente y
     * el merge es LWW.
     */
    fun resetAll(cols: List<String>) {
        prefs.edit().apply {
            cols.forEach { remove("push_$it"); remove("pull_$it") }
        }.apply()
    }

    /**
     * Reparación de UNA SOLA VEZ tras actualizar: los cursores viejos pueden venir dañados (el bug
     * del cursor de push enterraba filas que nunca llegaron al servidor) y eso no se arregla solo.
     * Se hace automática porque el otro dispositivo es un Fire Stick: nadie va a entrar ahí a
     * apretar "Sincronizar".
     */
    fun necesitaReparacion(): Boolean = !prefs.getBoolean(REPARACION_KEY, false)
    fun marcarReparado() { prefs.edit().putBoolean(REPARACION_KEY, true).apply() }

    private companion object {
        const val REPARACION_KEY = "reparacion_cursores_v1"
    }
}
