package com.arkiv.player.crash

import java.io.File

/**
 * Intenta mandar el reporte EN EL ACTO, con el proceso ya muriéndose.
 *
 * Es para el caso que más duele: un app que revienta apenas abre. Ahí el drenado de fondo del
 * arranque siguiente puede no llegar a terminar nunca, porque el proceso se muere antes de que la
 * petición salga. Este intento corre igual y, si pega, el reporte ya está del otro lado.
 *
 * Va en un hilo aparte por dos motivos: el handler de crash suele correr en el principal, y red en
 * el hilo principal es `NetworkOnMainThreadException`; y así se le puede poner un tope de espera,
 * para que una red colgada no deje al app congelado muriéndose.
 *
 * Si falla, no pasa nada: el archivo sigue en la cola y se reintenta en el arranque siguiente.
 */
class EnvioDeUltimoMomento(
    private val store: CrashStore,
    private val subir: (String) -> Boolean,
    private val esperaMaximaMs: Long = 4_000,
) : (File) -> Unit {
    override fun invoke(archivo: File) {
        val hilo = Thread({
            val subido = runCatching { subir(archivo.readText()) }.getOrDefault(false)
            if (subido) store.borrar(archivo)
        }, "arkiv-crash-ultimo-momento")
        hilo.isDaemon = true
        hilo.start()
        hilo.join(esperaMaximaMs)
    }
}
