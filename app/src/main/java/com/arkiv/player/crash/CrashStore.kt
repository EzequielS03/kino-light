package com.arkiv.player.crash

import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/**
 * Cola de reportes locales en disco.
 *
 * Existe por una sola razón: cuando salta una excepción no atrapada, el proceso se está muriendo, y
 * escribir un archivo es lo único que alcanza a terminar antes de que se vaya. Ya no hay subida a
 * ningún lado (Task 9, sub-proyecto 2B: se fue el `CrashUploader` que mandaba esto a PocketBase) --
 * el reporte se queda acá y se lee por `adb logcat`.
 */
class CrashStore(
    private val dir: File,
    /** Tope de reportes guardados. Pasado el tope se van los más viejos: si el app entró en un
     *  bucle de crashes, lo último que pasó es lo que sirve para debuguear. */
    private val maxPendientes: Int = 20,
    private val ahora: () -> Long = System::currentTimeMillis,
) {
    /** Desempata dos reportes del mismo milisegundo (un crash que arrastra a otro hilo). */
    private val secuencia = AtomicInteger(0)

    fun guardar(json: String): File {
        dir.mkdirs()
        val nombre = String.format(
            Locale.US,
            "%013d-%04d.json",
            ahora(),
            secuencia.getAndIncrement() % 10_000,
        )
        // Write aside and rename: a cut mid-write this way leaves a `.json.tmp` the queue ignores,
        // instead of a broken `.json` that whoever reads this locally (adb logcat) couldn't parse.
        val temporal = File(dir, "$nombre.tmp")
        temporal.writeText(json)
        val destino = File(dir, nombre)
        if (!temporal.renameTo(destino)) {
            destino.writeText(json)
            temporal.delete()
        }
        podar()
        return destino
    }

    /** Los pendientes del más viejo al más nuevo (el nombre está zero-padded a propósito). */
    fun pendientes(): List<File> =
        (dir.listFiles { f: File -> f.isFile && f.name.endsWith(".json") } ?: emptyArray())
            .sortedBy { it.name }

    private fun podar() {
        val actuales = pendientes()
        if (actuales.size <= maxPendientes) return
        actuales.take(actuales.size - maxPendientes).forEach { it.delete() }
    }
}
