package com.arkiv.player.playback

import android.content.Context
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.Composition
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.InAppMuxer
import androidx.media3.transformer.Transformer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume

/**
 * Rewrites an MPEG-TS into an MP4 without touching the video or the audio.
 *
 * Nothing is re-encoded: media3's Transformer copies the compressed samples straight across when
 * the format already fits the output container, so this costs I/O and almost no CPU, and the
 * picture is bit for bit what it was. It is `ffmpeg -c copy`, using a library the project already
 * depends on -- ffmpeg-kit was retired in January 2025, and re-adding a native blob right after
 * libVLC was removed from this branch would undo that.
 *
 * Why it is worth doing at all: the Cast receiver refuses a bare transport stream outright, and
 * even fed the same bytes as HLS segments it still has to derive every frame's presentation time
 * from PTS/DTS. On the KALLEY that produced hundreds of `Failed to get frame timestamps` a minute
 * and visible judder while the decoder itself was healthy. An MP4 states each sample's timing in
 * a table, so there is nothing left to derive.
 *
 * **Writes to a `.part` file and renames on success.** A half-written remux that looked finished
 * would be handed to the TV as a complete title and truncate mid-playback; a rename is atomic on
 * the same filesystem, so what exists under the final name is always whole.
 */
@androidx.annotation.OptIn(UnstableApi::class)
class TsRemuxer(
    private val context: Context,
    cacheDir: File,
    /**
     * Where the export actually runs, and it must OUTLIVE whoever asked for it. Kicking it off
     * inside a `LaunchedEffect` was measured to fail: casting churns those keys, each change
     * cancelled a remux minutes from finishing and the next one started from zero, so it never
     * completed once (`remux cancelled, partial file removed` at 44 s, then `remux starts` again).
     */
    private val scope: CoroutineScope,
) {

    private val carpeta = File(cacheDir, PoliticaDeRemux.CARPETA)

    /**
     * Exports in flight, by key. A second caller for the same title joins the one already running
     * instead of starting a rival export over the same output file -- and because the job lives in
     * [scope], a caller giving up on the wait does not take the export with it.
     */
    private val enCurso = ConcurrentHashMap<String, Deferred<Resultado>>()

    /** Result of asking for a remux. `Listo` carries a file that is complete and playable. */
    sealed interface Resultado {
        data class Listo(val archivo: File) : Resultado
        data class Fallo(val motivo: String) : Resultado
    }

    /** The finished remux for [clave] if one is already on disk, or null. */
    fun yaHecho(clave: String): File? =
        File(carpeta, PoliticaDeRemux.nombreDeArchivo(clave)).takeIf { it.exists() && it.length() > 0 }

    /**
     * Remuxes [uriDeEntrada] into the cache and returns the finished file.
     *
     * Idempotent: a remux already on disk is returned without redoing the work. Suspends until the
     * export finishes, and cancelling the coroutine cancels the export and removes the partial
     * file -- an abandoned `.part` would otherwise sit there forever, since nothing else knows
     * what it belonged to.
     *
     * Transformer needs a Looper, so the export is driven on the main thread; the actual work
     * happens on its own threads, so this does not block the UI.
     */
    suspend fun remuxear(uriDeEntrada: String, clave: String): Resultado {
        yaHecho(clave)?.let {
            Log.i(TAG, "already remuxed: ${it.name} (${it.length()}B), reusing it")
            return Resultado.Listo(it)
        }
        val job = enCurso.computeIfAbsent(clave) {
            scope.async { exportar(uriDeEntrada, clave) }
                .also { j -> j.invokeOnCompletion { enCurso.remove(clave) } }
        }
        return job.await()
    }

    /** The export itself. One per key at a time; see [remuxear]. */
    private suspend fun exportar(uriDeEntrada: String, clave: String): Resultado {
        if (!carpeta.exists() && !carpeta.mkdirs()) {
            return Resultado.Fallo("could not create ${carpeta.path}")
        }
        hacerSitio()
        val destino = File(carpeta, PoliticaDeRemux.nombreDeArchivo(clave))
        val parcial = File(carpeta, "${destino.name}.part")
        runCatching { parcial.delete() }

        val t0 = System.currentTimeMillis()
        Log.w(TAG, "remux starts → ${destino.name}")

        return withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { cont ->
                val transformer = Transformer.Builder(context)
                    // media3's OWN muxer, never the platform one. Transformer defaults to
                    // `FrameworkMuxer`, which is `MediaMuxer` and underneath it libstagefright's
                    // `MPEG4Writer` -- and that one ABORTS THE PROCESS on the HEVC samples coming
                    // out of a Magis transport stream: `FORTIFY: write: count
                    // 18446744073709551615 > SSIZE_MAX` (a sample size of -1 read as unsigned),
                    // SIGABRT on the MPEG4Writer thread, measured 2026-09-12. A native abort is
                    // not catchable, so the only defence is not to use that muxer. The in-app one
                    // is pure Java, and it is also what can write fragmented MP4.
                    .setMuxerFactory(InAppMuxer.Factory.Builder().build())
                    .addListener(object : Transformer.Listener {
                        override fun onCompleted(composition: Composition, result: ExportResult) {
                            val ok = runCatching { parcial.renameTo(destino) }.getOrDefault(false)
                            val ms = System.currentTimeMillis() - t0
                            if (ok) {
                                Log.w(
                                    TAG,
                                    "remux done in ${ms}ms → ${destino.name} (${destino.length()}B" +
                                        (result.durationMs.takeIf { it > 0 }?.let { ", ${it}ms" } ?: "") + ")",
                                )
                                if (cont.isActive) cont.resume(Resultado.Listo(destino))
                            } else {
                                runCatching { parcial.delete() }
                                Log.w(TAG, "remux finished but the rename failed")
                                if (cont.isActive) cont.resume(Resultado.Fallo("rename failed"))
                            }
                        }

                        override fun onError(
                            composition: Composition,
                            result: ExportResult,
                            exception: ExportException,
                        ) {
                            runCatching { parcial.delete() }
                            // The code matters more than the message: it tells "this device cannot"
                            // from "this file cannot", and only the second is worth giving up on.
                            Log.w(TAG, "remux failed (code=${exception.errorCode}): ${exception.message}")
                            if (cont.isActive) cont.resume(Resultado.Fallo("error ${exception.errorCode}"))
                        }
                    })
                    .build()

                // Only reached if [scope] itself is cancelled -- the app is going away. A caller
                // that stops waiting no longer lands here, which is the whole point of running in
                // an outer scope.
                cont.invokeOnCancellation {
                    runCatching { transformer.cancel() }
                    runCatching { parcial.delete() }
                    Log.w(TAG, "remux cancelled with the app, partial file removed")
                }

                runCatching {
                    transformer.start(MediaItem.fromUri(uriDeEntrada), parcial.absolutePath)
                }.onFailure {
                    runCatching { parcial.delete() }
                    Log.w(TAG, "remux could not start: ${it.message}")
                    if (cont.isActive) cont.resume(Resultado.Fallo(it.message ?: "could not start"))
                }
            }
        }
    }

    /**
     * Evicts the oldest remuxes until the cache is back under its ceiling.
     *
     * Runs before starting a new one rather than after finishing it: the point is to have room,
     * and discovering there was none only once a gigabyte is already written helps nobody.
     */
    private fun hacerSitio() {
        val archivos = carpeta.listFiles().orEmpty().filter { it.isFile }
        val fuera = PoliticaDeRemux.aBorrar(
            archivos.map { Triple(it.name, it.length(), it.lastModified()) },
        )
        if (fuera.isEmpty()) return
        var liberado = 0L
        fuera.forEach { nombre ->
            val f = File(carpeta, nombre)
            val bytes = f.length()
            if (runCatching { f.delete() }.getOrDefault(false)) liberado += bytes
        }
        Log.w(TAG, "cache over its ceiling: dropped ${fuera.size} file(s), freed ${liberado}B")
    }

    /** Drops every remux on disk. For the settings screen, and for tests. */
    fun limpiar(): Int {
        val archivos = carpeta.listFiles().orEmpty()
        var n = 0
        archivos.forEach { if (runCatching { it.delete() }.getOrDefault(false)) n++ }
        Log.i(TAG, "cleared $n remuxed file(s)")
        return n
    }

    /** Bytes the remuxes are taking up, so the caller can decide when to clear them. */
    fun bytesEnDisco(): Long = carpeta.listFiles().orEmpty().sumOf { it.length() }

    private companion object { const val TAG = "ArkivRemux" }
}
