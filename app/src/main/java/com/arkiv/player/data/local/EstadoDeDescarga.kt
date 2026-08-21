package com.arkiv.player.data.local

import com.arkiv.player.data.db.DownloadRow

/**
 * Lo que una fila de capítulo tiene que MOSTRAR sobre su descarga.
 *
 * Existe porque la lista de capítulos solo sabía dos cosas ("está guardado" / "está haciendo algo"),
 * y con eso un capítulo en cola, uno bajando y uno esperando confirmación se veían todos igual: un
 * spinner que no dice si va en el 5% o en el 90%, ni si de verdad está pasando algo.
 */
sealed interface EstadoDeDescarga {
    /** Nadie la encoló: la fila ofrece el botón de bajar. */
    data object SinDescargar : EstadoDeDescarga

    /** Encolada, esperando turno — la cola es de una a la vez (ver [DownloadQueuePolicy]). */
    data object EnCola : EstadoDeDescarga

    /**
     * En curso. [fraccion] es `null` cuando no se puede saber cuánto falta (tamaño total
     * desconocido, o la fase de staging del servidor, que solo sabe reportar 0/1): en ese caso la
     * barra va indeterminada en vez de mentir con un 0% clavado.
     */
    data class Bajando(val fraccion: Float?) : EstadoDeDescarga

    /** Ya está en el dispositivo. */
    data object Lista : EstadoDeDescarga

    /** Falló. [motivo] es lo que guardó la fila, para poder decirlo en vez de callarlo. */
    data class Fallida(val motivo: String?) : EstadoDeDescarga

    /** Torrent que supera el umbral de tamaño: no baja nada hasta que el usuario confirme. */
    data object PideConfirmacion : EstadoDeDescarga
}

/** Traduce una fila de la tabla `downloads` a lo que se ve en la lista de capítulos. */
object EstadoDeDescargaDeCapitulo {

    fun de(fila: DownloadRow?): EstadoDeDescarga = when (fila?.state) {
        null -> EstadoDeDescarga.SinDescargar
        // COMPLETED va ANTES de mirar el error a propósito: una fila completada puede traer un
        // "error" que no es un fallo sino el motivo por el que no hubo que bajar nada
        // (DuplicateDownloadPolicy.ADOPTED_REASON, "Ya estaba descargado").
        LocalDownloadState.COMPLETED -> EstadoDeDescarga.Lista
        LocalDownloadState.FAILED -> EstadoDeDescarga.Fallida(fila.error)
        LocalDownloadState.NEEDS_CONFIRMATION -> EstadoDeDescarga.PideConfirmacion
        LocalDownloadState.STAGING -> EstadoDeDescarga.Bajando(null)
        LocalDownloadState.DOWNLOADING ->
            EstadoDeDescarga.Bajando(if (fila.bytes > 0) fila.progress else null)
        LocalDownloadState.QUEUED -> EstadoDeDescarga.EnCola
        // Un estado que no conocemos no puede dejar la fila sin su botón de bajar.
        else -> EstadoDeDescarga.SinDescargar
    }
}
