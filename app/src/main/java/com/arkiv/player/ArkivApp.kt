package com.arkiv.player

import android.app.Application
import android.content.Context
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import kotlinx.coroutines.launch

class ArkivApp : Application(), ImageLoaderFactory {
    lateinit var graph: AppGraph
        private set

    /**
     * Lo más temprano que corre en el proceso: antes que los ContentProviders (WorkManager y
     * compañía) y antes de [onCreate]. El reporte de errores se instala acá a propósito, para que
     * un crash al abrir -incluido el de armar el [AppGraph]- también quede capturado.
     */
    override fun attachBaseContext(base: Context?) {
        super.attachBaseContext(base)
        com.arkiv.player.crash.Crash.instalar(this)
    }

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph.from(this)

        // ADOPCION DE LA BASE LOCAL. Quien ya venia usando la app tiene datos que SI son suyos y
        // todavia no hay dueño anotado; sin esto, el primer login despues de actualizar los tomaria
        // por huerfanos y le vaciaria la biblioteca. Con sesion viva y sin dueño, el dueño pasa a
        // ser esa cuenta y no se borra nada. Corre una sola vez: despues siempre hay dueño.
        // Ver [com.arkiv.player.pocketbase.DuenoDeLaBase].
        graph.applicationScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                val cuenta = graph.deviceAuth.session.value?.accountId
                if (com.arkiv.player.pocketbase.DuenoDeLaBase.hayQueAdoptar(graph.deviceStore.duenoDeLaBase(), cuenta)) {
                    graph.deviceStore.saveDuenoDeLaBase(cuenta!!)
                    android.util.Log.w("ArkivCuenta", "base local adoptada por la cuenta de la sesion")
                }
            }.onFailure { reportar(it, "arranque: adoptar la base local") }
        }

        // Purga única del 2026-08-14: canales de adultos que quedaron anotados en "Recientes"
        // ANTES de que `abrirCanalActual` dejara de anotarlos. Estaban saliendo en la fila
        // "Canales en vivo" del inicio, a la vista de cualquiera, con su nombre y su logo.
        //
        // Se borra TODO y no solo los de adultos porque el aparato no puede saber cuáles lo eran:
        // los recientes guardan código y nombre, nunca la categoría. Y no cuesta nada — los de la
        // nube ya se limpiaron a mano, así que el próximo sync repuebla la lista con los legítimos.
        graph.applicationScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                if (!graph.deviceStore.recientesPurgados()) {
                    graph.database.liveRecentDao().borrarTodos()
                    graph.deviceStore.setRecientesPurgados(true)
                    android.util.Log.w("ArkivCuenta", "recientes purgados (fuga de canales de adultos)")
                }
            }.onFailure { reportar(it, "arranque: purgar recientes") }
        }

        graph.iniciarMonitorDeRed()

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
        graph.applicationScope.launch {
            runCatching { graph.checkForUpdate() }.onFailure { reportar(it, "arranque: buscar actualización") }
        }

        // Capítulos nuevos de las series que estás viendo. Va en background y sin bloquear nada:
        // es una mejora oportunista, no un camino crítico. La cota de "una vez cada N horas" está
        // adentro porque el arranque de la app pasa muchas veces por día (basta con salir y volver
        // a entrar), y revisar en cada una sería gastar red para nada.
        graph.applicationScope.launch {
            runCatching { graph.buscarCapitulosNuevos() }
                .onFailure { reportar(it, "arranque: buscar capítulos nuevos") }
        }
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
    /**
     * Cada tarea de arranque corre en su propio `runCatching` para que un fallo no tumbe a las
     * otras — pero eso también las volvía mudas: si el sync nunca arrancaba, no quedaba rastro de
     * por qué. Reportar no cambia el aislamiento, solo deja constancia.
     */
    private fun reportar(error: Throwable, etiqueta: String) {
        android.util.Log.w("ArkivArranque", "$etiqueta: ${error.message}", error)
        com.arkiv.player.crash.Crash.reportar(error, etiqueta)
    }

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
