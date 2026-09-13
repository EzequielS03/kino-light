package com.arkiv.player.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.arkiv.player.AppGraph
import com.arkiv.player.data.gateway.GatewayResult
import com.arkiv.player.ui.search.SearchPlayback
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Debug-only: exercises the REAL Caracol download, end to end, without touching the screen.
 *
 * Unlike [SpikeDeDescargaDeCaracol] -- which built its own downloader to answer "is this possible
 * at all" -- this one goes through the shipping path: it saves the series the way the chapter
 * dialog does, enqueues with the source `DownloadSource` picks, and then `LocalDownloadWorker`
 * runs `DituDownloadStrategy`. Nothing here is a stand-in for production code; it only replaces
 * the finger that would tap the button.
 *
 * ```
 * adb shell am broadcast -n com.arkiv.player.light/com.arkiv.player.debug.PruebaDeDescargaDeCaracol
 * adb logcat -s ArkivPruebaDitu ArkivDituDl ArkivLocalDl
 * ```
 * `--es serie <contentId>` and `--ei cap <n>` pick something other than the defaults.
 *
 * Pass `--es paso estado` instead to ask what the app thinks it has on disk for that chapter --
 * that is, what the player will read at play time.
 */
class PruebaDeDescargaDeCaracol : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        val serieId = intent.getStringExtra("serie")?.takeIf { it.isNotBlank() } ?: SERIE
        val cual = intent.getIntExtra("cap", 1)
        val paso = intent.getStringExtra("paso")
        CoroutineScope(Dispatchers.IO).launch {
            runCatching {
                when (paso) {
                    "estado" -> estado(app, serieId, cual)
                    "borrar" -> borrar(app, serieId, cual)
                    else -> encolar(app, serieId, cual)
                }
            }.onFailure { Log.e(TAG, "the probe blew up", it) }
        }
    }

    private suspend fun encolar(context: Context, serieId: String, cual: Int) {
        val graph = AppGraph.from(context)
        val (temporada, capitulos, serie) = cargar(graph, serieId) ?: return
        val elegido = capitulos.getOrNull(cual - 1) ?: run {
            Log.e(TAG, "chapter $cual doesn't exist (${capitulos.size} in the season)")
            return
        }
        Log.w(TAG, "queuing «${elegido.title}» (#${elegido.number}) of ${capitulos.size}")

        val playback = SearchPlayback(graph)
        val encolados = playback.encolarDescargaDeCaracol(temporada, capitulos, listOf(elegido), serie)
        val epId = playback.dituEpisodeIdDe(temporada, capitulos, elegido, serie)
        Log.w(TAG, "queued=$encolados episodeId=$epId · source=${epId?.let { fuenteDe(it) }}")
        Log.w(TAG, "now watch ArkivLocalDl / ArkivDituDl; then run this with `--es paso estado`")
    }

    private suspend fun estado(context: Context, serieId: String, cual: Int) {
        val graph = AppGraph.from(context)
        val (temporada, capitulos, serie) = cargar(graph, serieId) ?: return
        val elegido = capitulos.getOrNull(cual - 1) ?: return
        val epId = SearchPlayback(graph).dituEpisodeIdDe(temporada, capitulos, elegido, serie) ?: return

        val fila = graph.database.downloadDao().get(epId)
        val descarga = graph.localLibrary.descargaDeCaracol(epId)
        val comoArchivo = graph.localLibrary.fileFor(epId)
        Log.w(
            TAG,
            "episodeId=$epId\n" +
                "  row      : state=${fila?.state} source=${fila?.source} bytes=${fila?.bytesDone}/${fila?.bytes}\n" +
                "  record   : ${descarga?.let { "${it.height}p, ${it.keys.size} tracks, mpd=${it.mpd.take(60)}" } ?: "none"}\n" +
                "  asFile   : ${comoArchivo ?: "null (correct: Caracol must NOT go to the local-file player)"}\n" +
                "  onDisk   : ${graph.almacenDeCaracol.bytesOnDisk() / 1_000_000}MB of Caracol",
        )
    }

    /**
     * Quita la descarga, que para Caracol es lo más delicado del lote: sus bytes viven en un caché
     * COMPARTIDO por todos los capítulos, así que borrar de más se lleva puesto lo que alguien más
     * guardó, y borrar de menos deja cientos de megas que nada vuelve a reclamar.
     */
    private suspend fun borrar(context: Context, serieId: String, cual: Int) {
        val graph = AppGraph.from(context)
        val (temporada, capitulos, serie) = cargar(graph, serieId) ?: return
        val elegido = capitulos.getOrNull(cual - 1) ?: return
        val epId = SearchPlayback(graph).dituEpisodeIdDe(temporada, capitulos, elegido, serie) ?: return

        val antes = graph.almacenDeCaracol.bytesOnDisk()
        graph.localDownloads.remove(epId)
        val despues = graph.almacenDeCaracol.bytesOnDisk()
        val fila = graph.database.downloadDao().get(epId)
        val registro = java.io.File(
            graph.localDownloads.targetDir(),
            com.arkiv.player.data.local.DituDownloadStrategy.recordFileName(epId),
        )
        Log.w(
            TAG,
            "REMOVED $epId · cache ${antes / 1_000_000}MB -> ${despues / 1_000_000}MB " +
                "(freed ${(antes - despues) / 1_000_000}MB) · row=${fila?.state ?: "gone"} " +
                "· record=${if (registro.exists()) "STILL THERE" else "gone"}",
        )
    }

    /** La temporada tal como la arma la ventana de capítulos, más su lista y su serie. */
    private suspend fun cargar(
        graph: AppGraph,
        serieId: String,
    ): Triple<GatewayResult, List<com.arkiv.player.data.gateway.GatewayEpisode>, com.arkiv.player.data.gateway.GatewaySerie?>? {
        val ref = "ditu1:BUNDLE:$serieId"
        val (capitulos, serie) = runCatching { graph.fuenteDeContenido.episodesWithSeries(ref) }
            .getOrElse { Log.e(TAG, "couldn't list the season", it); return null }
        if (capitulos.isEmpty()) { Log.e(TAG, "that season has no chapters"); return null }
        val temporada = GatewayResult(
            source = "ditu",
            title = serie?.titulo?.takeIf { it.isNotBlank() } ?: "Caracol $serieId",
            ref = ref,
            kind = "series",
        )
        return Triple(temporada, capitulos, serie)
    }

    private fun fuenteDe(epId: String) = com.arkiv.player.data.local.DownloadSource.sourceFor(epId)

    private companion object {
        const val TAG = "ArkivPruebaDitu"

        /** "Dulce Amor": el BUNDLE con el que se midió todo lo demás. */
        const val SERIE = "1500000246"
    }
}
