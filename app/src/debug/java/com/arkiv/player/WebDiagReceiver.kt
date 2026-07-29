package com.arkiv.player

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.launch

/**
 * DEBUG-only. Dispara el diagnóstico de fuentes web (browse por-canal contra el sitio real) para
 * validar en vivo qué canales funcionan. Se lanza por adb (componente explícito):
 *   adb shell am broadcast -n com.arkiv.player/.WebDiagReceiver -a com.arkiv.player.WEB_DIAG
 * Los resultados salen en logcat con tag "ArkivWebDiag".
 *
 * El trabajo corre en graph.applicationScope (no en goAsync, que tiene ~10s de límite) para que un
 * diagnóstico de varios minutos (Cloudflare por canal) no sea cortado por el sistema.
 */
class WebDiagReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val graph = AppGraph.from(context.applicationContext)
        val dump = intent.getBooleanExtra("dump", false)
        // filtro opcional por id: --es only "id1,id2". Sin él, corre todos los habilitados.
        val only = intent.getStringExtra("only")?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet()
        // fetch de URLs arbitrarias (para fingerprintear páginas de serie tras Cloudflare):
        //   --es urls "https://sitio/serie/x,https://otro/serie/y"
        val urls = intent.getStringExtra("urls")?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }
        val dir = java.io.File(context.getExternalFilesDir(null), "webdiag")
        graph.applicationScope.launch {
            Log.w("ArkivWebDiag", "=== INICIO diagnóstico web (dump=$dump only=${only ?: "todos"} urls=${urls?.size ?: 0}) ===")
            runCatching {
                val lines = when {
                    urls != null -> graph.webFetchDump(dir, urls)
                    dump -> graph.webDiagnosticsDump(dir, only)
                    else -> graph.webDiagnostics(only)
                }
                lines.forEach { Log.w("ArkivWebDiag", it) }
            }.onFailure { Log.w("ArkivWebDiag", "error: $it") }
            Log.w("ArkivWebDiag", "=== FIN diagnóstico web ===")
        }
    }
}
