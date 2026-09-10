package com.arkiv.player.data.ia

import android.content.Context

/** La memoria de modelos, persistida en SharedPreferences. Perderla solo cuesta volver a aprender. */
internal class AlmacenEnPreferencias(context: Context) : AlmacenDeMemoria {
    private val prefs = context.applicationContext.getSharedPreferences("arkiv_ia", Context.MODE_PRIVATE)
    override fun leer(): String? = prefs.getString(CLAVE, null)
    override fun guardar(json: String) { prefs.edit().putString(CLAVE, json).apply() }
    private companion object { const val CLAVE = "memoria_de_modelos" }
}
