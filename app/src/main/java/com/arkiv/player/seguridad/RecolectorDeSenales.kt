package com.arkiv.player.seguridad

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.io.File

/**
 * Mira el dispositivo y arma las [SenalesDeRoot]. Todo lo que toca Android vive acá para que
 * [DeteccionDeRoot] quede pura.
 *
 * Nada de esto puede tirar la app: cada lectura va protegida. Un dispositivo que niegue el acceso a
 * `/proc` no es motivo para dejar a alguien sin reproductor.
 */
object RecolectorDeSenales {

    fun recoger(context: Context): SenalesDeRoot = SenalesDeRoot(
        binariosSu = DeteccionDeRoot.RUTAS_SU.filter { existe(it) },
        paquetesDeRoot = DeteccionDeRoot.PAQUETES_DE_ROOT.filter { instalado(context, it) },
        rastrosDeMagisk = DeteccionDeRoot.RASTROS_DE_MAGISK.filter { existe(it) },
        tags = Build.TAGS.orEmpty(),
        tipo = Build.TYPE.orEmpty(),
        montajesSospechosos = montajesSospechosos(),
        montajesInconsistentes = montajesInconsistentes(),
    )

    private fun existe(ruta: String): Boolean = runCatching { File(ruta).exists() }.getOrDefault(false)

    private fun instalado(context: Context, paquete: String): Boolean = runCatching {
        context.packageManager.getPackageInfo(paquete, 0)
        true
    }.getOrDefault(false)

    /** Se guardan las primeras pocas: alcanzan para decidir y para explicar, sin volcar la tabla entera. */
    private fun montajesSospechosos(): List<String> = runCatching {
        File("/proc/self/mountinfo").readLines()
            .filter { DeteccionDeRoot.montajeEsSospechoso(it) }
            .take(3)
    }.getOrDefault(emptyList())

    /**
     * ¿Dos hilos del mismo proceso ven tablas de montaje distintas?
     *
     * No deberían: el namespace de montaje es del proceso. Cuando se manipula en el arranque para
     * esconder el root, los hilos que ya existían conservan la vista anterior y aparece la
     * diferencia. Es la señal que sobrevive a Shamiko, porque no busca un rastro concreto sino una
     * contradicción del propio sistema.
     *
     * Se compara contra el hilo de menor tid (el principal, el más viejo). Con un solo hilo no hay
     * nada que comparar y se responde `false`: ausencia de prueba, no prueba de ausencia.
     */
    private fun montajesInconsistentes(): Boolean = runCatching {
        val propio = File("/proc/self/mountinfo").readText()
        val hilos = File("/proc/self/task").list()?.mapNotNull { it.toIntOrNull() }?.sorted().orEmpty()
        val masViejo = hilos.firstOrNull() ?: return@runCatching false
        val delHilo = File("/proc/self/task/$masViejo/mountinfo").readText()
        propio.lines().size != delHilo.lines().size
    }.getOrDefault(false)
}
