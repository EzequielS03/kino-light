package com.arkiv.player.playback

import com.arkiv.player.data.magis.TweakedMd5
import com.arkiv.player.data.gateway.LiveSignature

/**
 * De dónde sale el `sign2` de cada petición al CDN.
 *
 * En esta rama hay una sola implementación ([FirmaLocal]) y la interfaz se conserva igual: lo que
 * se fue es el respaldo, que consistía en pedirle la firma al gateway y conmutar a él tras dos
 * rechazos seguidos (`FirmaDelGateway`/`FirmaConRespaldo`/`FirmaSegunAjustes`, más el interruptor
 * "Forzar servidor" de Ajustes). Sin servidor propio no hay a dónde conmutar: si Magis cambia el
 * algoritmo se arregla publicando un APK. [rechazada]/[aceptada] siguen existiendo porque
 * `LiveHlsProxy` las llama, y porque son el gancho natural si algún día hay otra firma local.
 */
interface FirmaDeSegmentos {
    suspend fun firmar(token: String): LiveSignature

    /** El CDN rechazó la última firma entregada. */
    fun rechazada() {}

    /** El CDN aceptó la última firma entregada (`pedirAlOrigen` la usó y NO le siguió un 403). */
    fun aceptada() {}
}

/** Firma en el aparato. Es aritmética local: ni red, ni espera, ni pool. */
class FirmaLocal : FirmaDeSegmentos {
    override suspend fun firmar(token: String): LiveSignature {
        val momento = System.currentTimeMillis()
        return LiveSignature(momento, TweakedMd5.signO3(token, momento))
    }
}

/**
 * Si esta respuesta del CDN significa "no te autorizo", o sea que la firma no sirvio.
 *
 * Existe porque este CDN rechaza con **401**, no con 403, y el codigo solo miraba el 403. Medido en
 * el Google TV el 2026-08-14: ningun canal cargaba mientras el gateway resolvia perfecto
 * (`live resolve OK ... direcciones=2`, 200 en todos). El log del aparato lo destapo:
 *
 * ```
 * 12:56:58.653  playlist → 401 en 346ms
 * 12:56:58.654  502 al reproductor: playlist con codigo 401
 * ```
 *
 * Con un 401, `pedirAlOrigen` llamaba a `aceptada()` -- daba la firma por BUENA-- el contador de
 * rechazos seguidos se reseteaba y [FirmaConRespaldo] no conmutaba nunca al firmador del gateway.
 * Tampoco se llegaba a dar la sesion por muerta, que es el otro camino de recuperacion: las dos
 * defensas estaban mirando el codigo equivocado y el canal moria en un 502 sin remedio.
 *
 * Solo 401 y 403. Un 5xx o un timeout NO son rechazo de firma -- son el CDN teniendo un problema--
 * y contarlos haria conmutar al respaldo por cualquier bache de red.
 */
fun esRechazoDeFirma(codigo: Int): Boolean = codigo == 401 || codigo == 403
