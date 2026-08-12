package com.arkiv.player.playback

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/**
 * Los dos viajes al CDN que hay que hacer ANTES de abrir el video, hechos A LA VEZ.
 *
 * Son la sonda de duración ([TsDurationProbe], que baja las dos puntas del TS) y el arranque
 * caliente ([ArchiveCacheProxy.precalentar], que se guarda los primeros 2 MB). Los dos hablan con
 * el mismo CDN, ninguno depende del resultado del otro, y hasta el 2026-08-11 iban EN SERIE: medido
 * en el Fire TV, la sonda costaba entre 0,54 s y 9,01 s y el precalentado otros 0,49 s a 2,53 s,
 * uno detrás del otro, con el usuario mirando el spinner todo ese rato.
 *
 * Que convivan está medido, no supuesto: con tres conexiones drenando el mismo archivo, un rango de
 * cola seguía contestando en 0,44-0,82 s. Además piden tramos distintos, así que ni siquiera
 * compiten por lo mismo.
 *
 * Los dos son best-effort y fallan distinto:
 * - sin sonda se reproduce sin barra de duración,
 * - sin arranque caliente se reproduce sin la garantía de identificar el stream a la primera.
 *
 * Ninguno de los dos puede impedir que la película arranque, y por eso los dos van envueltos: acá
 * una excepción NUNCA se propaga.
 */
object ArranqueDeMagis {

    /**
     * Arranca [precalentar] y [sonda] a la vez y no vuelve hasta que los DOS terminaron.
     *
     * Esperar al precalentado es el punto: sus 2 MB tienen que estar en la mano antes de que el
     * reproductor abra la URL, o no sirve para lo que existe. Devuelve lo que dio la sonda, o 0 si
     * no se pudo.
     */
    suspend fun duracionYArranque(
        sonda: suspend () -> Long,
        precalentar: suspend () -> Unit,
    ): Long = coroutineScope {
        val calentando = async { runCatching { precalentar() } }
        val ms = runCatching { sonda() }.getOrDefault(0L)
        calentando.await()
        ms
    }
}
