package com.arkiv.player.playback

/**
 * Si ESTE instante cuenta como "el decodificador no está dando imagen".
 *
 * De la racha que se arma con esto salen los dos rescates de [VlcPlayer] —caer a software y volver
 * a hardware—, y los dos recargan el media entero. O sea que contar de más no es un detalle: es
 * tirar la reproducción y las conexiones al origen sin que nadie estuviera fallando.
 *
 * "No hay imagen" tiene tres causas que desde afuera se ven IDÉNTICAS (pantalla negra) y que piden
 * cosas opuestas:
 *
 * 1. **No hay dónde pintar** (la superficie está desenganchada porque la app se fue al fondo). No
 *    dice nada del decodificador.
 * 2. **No llegaron datos todavía** (`pistas=v0/a0`): el demuxer ni siquiera pudo identificar el
 *    stream. El decodificador no tiene nada que hacer — el problema está en la red.
 * 3. **Llegan datos y no se decodifican**: el fallo real, y el único que un rescate arregla.
 *
 * Solo el caso 3 puede acumular racha. El 2 es el que faltaba distinguir: medido el 2026-08-11 en
 * el Fire TV, VLC pasó 10 s en `pistas=v0/a0 video=false dur=0ms` esperando un rango de cola cuya
 * conexión al CDN había salido muerta, y el rescate lo interpretó como códec roto: recargó la
 * película entera y la dejó en decodificación por software. Que igual terminara reproduciendo fue
 * casualidad —la recarga abrió un socket nuevo, que sí contestó— y salió carísimo comparado con el
 * reintento del proxy, que cuesta ~100 ms.
 */
object RachaSinVideo {

    /**
     * [pistas] es cuántas pistas demuxeó libVLC (video + audio). Negativo = no se pudo consultar:
     * ante la duda se acumula igual, porque desactivar el rescate por no poder medir cambiaría un
     * fallo conocido por uno peor (negro para siempre).
     */
    fun cuenta(hayVideo: Boolean, superficieEnganchada: Boolean, pistas: Int): Boolean =
        !hayVideo && superficieEnganchada && pistas != 0
}
