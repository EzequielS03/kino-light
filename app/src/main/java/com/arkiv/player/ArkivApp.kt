package com.arkiv.player

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import kotlinx.coroutines.launch

class ArkivApp : Application(), ImageLoaderFactory {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph.from(this)
        // Servidor de sincronización LAN (expone/recibe la DB entre dispositivos).
        runCatching { graph.syncManager.start() }

        // OTA: chequeo periódico cada 6 horas + chequeo inmediato al arrancar.
        androidx.work.WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "update_check",
            androidx.work.ExistingPeriodicWorkPolicy.KEEP,
            androidx.work.PeriodicWorkRequestBuilder<com.arkiv.player.data.update.UpdateWorker>(
                6, java.util.concurrent.TimeUnit.HOURS,
            ).setConstraints(
                androidx.work.Constraints.Builder().setRequiredNetworkType(androidx.work.NetworkType.CONNECTED).build()
            ).build(),
        )
        graph.applicationScope.launch { graph.checkForUpdate() }

        // Reintenta borrar de la NUC los items que ya se transfirieron al dispositivo pero cuyo
        // DELETE falló en su momento (blog caído, red cortada) — si no, el disco de la NUC se llena
        // de archivos que ya nadie va a reproducir. Best-effort: un fallo acá no debe tumbar el arranque.
        graph.applicationScope.launch { runCatching { graph.localDownloads.sweepNucOrphans() } }

        // Capítulos nuevos de las series que estás viendo. Va en background y sin bloquear nada:
        // es una mejora oportunista, no un camino crítico. La cota de "una vez cada N horas" está
        // adentro porque el arranque de la app pasa muchas veces por día (basta con salir y volver
        // a entrar), y revisar en cada una sería gastar red para nada.
        graph.applicationScope.launch { runCatching { graph.buscarCapitulosNuevos() } }
    }

    /**
     * Coil venía con los defaults, y su caché de memoria por defecto es el **25% del límite de
     * heap**. En el Fire TV Stick eso son ~48 MB reservados solo para bitmaps en un aparato de
     * 1.7 GB que corre con ~48 MB libres y el swap casi lleno (medido con `dumpsys meminfo`).
     * Con los stills de capítulo de TMDB hay bastantes más imágenes en pantalla que antes, así
     * que conviene ponerle un techo explícito en vez de dejar el porcentaje por defecto.
     *
     * En TV se recorta al 10% y se permite RGB_565: pósters y stills son JPEG sin transparencia,
     * así que bajan a la mitad de bytes por bitmap sin diferencia visible a distancia de sofá.
     * En teléfono se deja un 20% (holgado, pero por debajo del default) y color completo, que es
     * donde sí se nota en una pantalla a 30 cm.
     */
    override fun newImageLoader(): ImageLoader {
        val tv = DeviceType.isTelevision(this)
        return ImageLoader.Builder(this)
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizePercent(if (tv) 0.10 else 0.20)
                    .build()
            }
            // La caché de disco evita volver a bajar la misma portada en cada arranque; el default
            // de Coil (2% del espacio libre) puede ser enorme en un teléfono con mucho disco.
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("image_cache"))
                    .maxSizeBytes(if (tv) 64L * 1024 * 1024 else 192L * 1024 * 1024)
                    .build()
            }
            .allowRgb565(tv)
            .build()
    }
}
