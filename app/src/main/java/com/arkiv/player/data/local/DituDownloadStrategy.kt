package com.arkiv.player.data.local

import android.net.Uri
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.StreamKey
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.dash.DashUtil
import androidx.media3.exoplayer.dash.manifest.DashManifest
import com.arkiv.player.data.ArkivRepository
import com.arkiv.player.data.caracol.CaracolStore
import com.arkiv.player.data.caracol.CaracolQuality
import com.arkiv.player.data.caracol.TrackKey
import com.arkiv.player.data.caracol.CaracolDownload
import com.arkiv.player.data.caracol.CaracolTrack
import com.arkiv.player.data.gateway.ContentSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Caracol: guarda los segmentos CIFRADOS de un capítulo en el caché de media3.
 *
 * Es la estrategia rara de las dos. Magis baja UN archivo y ese archivo después se reproduce solo,
 * sin depender de nada. Caracol no puede: su video es DASH con Widevine, y lo que queda en disco
 * son segmentos que solo el CDM del aparato sabe abrir, con una licencia que hay que pedir de
 * nuevo cada vez. Acá no se descifra nada — ver [CaracolStore].
 *
 * El resultado no es entonces un video reproducible sino el REGISTRO de la descarga
 * ([CaracolDownload]): un archivito JSON al lado de las descargas normales, con la URL del
 * manifiesto y qué calidad se bajó. Eso es lo que devuelve [DownloadOutcome.Done] y lo que queda
 * en `downloads.filePath`. Va así, y no como una columna nueva, porque toda la maquinaria que ya
 * existe —el barrido por prefijo de `LocalDownloadManager.remove`, la fila de la UI, el gemelo—
 * trabaja con un path; y porque `LocalLibrary.fileFor` sabe no entregárselo al reproductor de
 * archivos locales (miraría un JSON y mostraría pantalla negra).
 *
 * Se resuelve en el momento de bajar y no al encolar: el `playback_token` de Caracol dura horas.
 */
@androidx.annotation.OptIn(UnstableApi::class)
class DituDownloadStrategy(
    private val repo: ArkivRepository,
    private val gateway: ContentSource,
    private val almacen: CaracolStore,
    /** Techo de calidad. Inyectado para poder probar la decisión sin tocar la constante global. */
    private val altoObjetivo: Int = CaracolQuality.TARGET_HEIGHT,
) : DownloadStrategy {

    override suspend fun download(
        episodeId: String,
        alreadyConfirmed: Boolean,
        targetDir: File,
        onProgress: (Long, Long) -> Unit,
    ): DownloadOutcome {
        val ref = repo.magisRefForEpisode(episodeId)
            ?: return DownloadOutcome.Failed("No se encontró la fuente de Caracol")

        val play = runCatching { gateway.resolve(ref) }.getOrElse {
            return DownloadOutcome.Failed(
                it.message ?: "No se pudo resolver el capítulo de Caracol",
                transient = DownloadRetryPolicy.isTransient(it),
            )
        }
        if (play.url.isBlank()) return DownloadOutcome.Failed("Caracol no devolvió un manifiesto")

        val headers = play.drmLicenseHeaders
        val manifest = runCatching {
            withContext(Dispatchers.IO) {
                DashUtil.loadManifest(almacen.httpFactory(headers).createDataSource(), Uri.parse(play.url))
            }
        }.getOrElse {
            return DownloadOutcome.Failed(
                it.message ?: "No se pudo leer el manifiesto de Caracol",
                transient = DownloadRetryPolicy.isTransient(it),
            )
        }

        val elegidas = CaracolQuality.choose(pistasDe(manifest), altoObjetivo)
        if (elegidas.isEmpty()) return DownloadOutcome.Failed("Ese capítulo de Caracol no trae video")
        val claves = elegidas.map { StreamKey(PERIODO, it.group, it.track) }
        val alto = elegidas.firstOrNull { it.isVideo }?.height ?: 0
        val estimado = CaracolQuality.estimatedBytes(elegidas, manifest.durationMs)
        Log.i(
            TAG,
            "$episodeId: downloading ${alto}p (~${estimado / 1_000_000}MB of ${manifest.durationMs}ms) keys=$claves",
        )

        // El registro se escribe ANTES de bajar un solo byte. Si la descarga se corta a la mitad,
        // lo que quedó en el caché sigue siendo identificable: sin el registro, esos megas serían
        // basura anónima que nadie sabría ni reanudar ni borrar.
        val registro = File(targetDir, nombreDelRegistro(episodeId))
        val datos = CaracolDownload(
            mpd = play.url,
            keys = elegidas.map { TrackKey(PERIODO, it.group, it.track) },
            height = alto,
        )
        runCatching { registro.writeText(datos.toJson()) }
            .onFailure { return DownloadOutcome.Failed("No se pudo anotar la descarga: ${it.message}") }

        val item = MediaItem.Builder()
            .setUri(play.url)
            .setMimeType(MimeTypes.APPLICATION_MPD)
            .setStreamKeys(claves)
            .build()

        return try {
            // `runInterruptible` y no un `withContext` pelado: `DashDownloader.download` BLOQUEA el
            // hilo, y una corrutina cancelada no interrumpe por su cuenta una llamada bloqueante.
            // Sin esto, "Cancelar" dejaba la descarga corriendo hasta terminar — gastando datos de
            // una fila que ya no existe, que es exactamente lo que `LocalDownloadManager.remove`
            // documenta querer evitar.
            runInterruptible(Dispatchers.IO) {
                almacen.downloader(item, headers).download { contentLength, bytesDownloaded, _ ->
                    onProgress(bytesDownloaded, if (contentLength > 0) contentLength else estimado)
                }
            }
            Log.i(TAG, "$episodeId: done, ${almacen.bytesOnDisk() / 1_000_000}MB of Caracol on disk")
            DownloadOutcome.Done(registro)
        } catch (ce: kotlinx.coroutines.CancellationException) {
            // Se propaga: el worker la distingue de un fallo, y lo bajado queda en el caché para
            // que "Reintentar" siga desde ahí en vez de empezar de cero.
            throw ce
        } catch (t: Throwable) {
            DownloadOutcome.Failed(
                t.message ?: "Falló la descarga de Caracol",
                transient = DownloadRetryPolicy.isTransient(t),
            )
        }
    }

    /**
     * Borra del caché lo que bajó ESTE capítulo, sin tocar los demás.
     *
     * El caché es uno solo para todos, así que no alcanza con borrar una carpeta: quien sabe qué
     * bytes son de quién es el propio descargador, reconstruido con el mismo manifiesto y las
     * mismas pistas que quedaron anotadas en el registro.
     */
    override suspend fun borrarRestos(episodeId: String, targetDir: File) {
        val registro = File(targetDir, nombreDelRegistro(episodeId))
        val datos = runCatching { CaracolDownload.fromJson(registro.readText()) }.getOrNull()
        if (datos == null) {
            Log.w(TAG, "$episodeId: no download record, nothing to clear from the cache")
            return
        }
        val item = MediaItem.Builder()
            .setUri(datos.mpd)
            .setMimeType(MimeTypes.APPLICATION_MPD)
            .setStreamKeys(datos.keys.map { StreamKey(it.period, it.group, it.track) })
            .build()
        runCatching { runInterruptible(Dispatchers.IO) { almacen.downloader(item, emptyMap()).remove() } }
            .onFailure { Log.w(TAG, "$episodeId: couldn't clear the cache: ${it.message}") }
        Log.i(TAG, "$episodeId: cleared; ${almacen.bytesOnDisk() / 1_000_000}MB of Caracol left on disk")
    }

    /** El manifiesto, reducido a lo que [CaracolQuality] necesita para elegir. */
    private fun pistasDe(manifest: DashManifest): List<CaracolTrack> {
        if (manifest.periodCount == 0) return emptyList()
        val periodo = manifest.getPeriod(PERIODO)
        return buildList {
            periodo.adaptationSets.forEachIndexed { grupo, conjunto ->
                val esVideo = conjunto.type == C.TRACK_TYPE_VIDEO
                if (!esVideo && conjunto.type != C.TRACK_TYPE_AUDIO) return@forEachIndexed
                conjunto.representations.forEachIndexed { pista, representacion ->
                    val f = representacion.format
                    add(
                        CaracolTrack(
                            group = grupo,
                            track = pista,
                            isVideo = esVideo,
                            height = f.height.takeIf { it != androidx.media3.common.Format.NO_VALUE } ?: 0,
                            bitsPerSecond = f.bitrate.takeIf { it != androidx.media3.common.Format.NO_VALUE } ?: 0,
                        ),
                    )
                }
            }
        }
    }

    companion object {
        private const val TAG = "ArkivDituDl"

        /**
         * Caracol sirve un solo período. Se nombra en vez de escribir 0 suelto en cinco lugares:
         * un manifiesto multi-período necesitaría bajar todos, y así se ve dónde habría que mirar.
         */
        private const val PERIODO = 0

        /** Nombre del registro de un capítulo. El prefijo es el que barre `LocalDownloadManager.remove`. */
        fun nombreDelRegistro(episodeId: String): String =
            "${LocalFilePaths.sanitize(episodeId)}.${CaracolDownload.EXTENSION}"
    }
}
