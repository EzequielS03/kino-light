package com.arkiv.player.playback

/**
 * Cuándo avisarle a la capa de entrega que el reproductor va a saltar, y a qué fracción del archivo.
 *
 * Portado del reproductor de magis original, cuya técnica central es justamente esta: su `B0(name,
 * pos)` NO le manda el seek al player — se lo manda al motor de entrega (`NativeJni.Seek`) y recién
 * en el callback de ese motor mueve el reproductor (`yc/C6280e.java:2115` en el decompilado). Los
 * bytes del destino ya se están buscando cuando el player llega.
 *
 * Nosotros teníamos la mitad construida: [ArchiveCacheProxy.precalentarSalto] existe, está probado y
 * ahorra segundos —3,4 s de imagen congelada medidos en el Fire TV— pero solo se llamaba AL ABRIR,
 * desde `PlayerViewModel`. Un salto hecho a mano con la barra no avisaba nada, así que el proxy se
 * enteraba del destino recién cuando VLC le pedía el rango; contra un CDN que tarda entre 0,2 s y
 * 20 s en contestar (ver [PoliticaOrigen]) eso es tarde.
 *
 * Acá va solo la REGLA, para poder fijar los bordes por test. El aviso lo ejecuta el proxy.
 */
object AvisoDeSalto {

    /**
     * Lo mínimo entre dos avisos. Cada uno abre una conexión NUEVA al origen, y arrastrar la barra
     * emite decenas de posiciones: sin freno, cada una abriría la suya contra el mismo CDN que
     * estamos tratando de no molestar. Es el mismo motivo (y el mismo orden de magnitud) que el
     * freno de reaperturas de [VlcPlayer.REAPERTURA_MINIMA_MS].
     */
    const val MINIMO_ENTRE_AVISOS_MS = 1_500L

    /**
     * La fracción [0..1] a precalentar, o null si no hay nada que avisar.
     *
     * Los dos extremos quedan fuera y no es prolijidad: [ArchiveCacheProxy.precalentarSalto] los
     * descarta igual, así que avisar ahí gastaría una conexión al CDN para nada. Del principio ya se
     * encarga el arranque caliente y del final, [ColaCaliente].
     */
    fun fraccion(posicionMs: Long, duracionMs: Long): Float? {
        if (duracionMs <= 0L || posicionMs <= 0L) return null
        val f = posicionMs.toFloat() / duracionMs
        return f.takeIf { it > 0f && it < 1f }
    }

    /**
     * Si corresponde avisar ahora. [esDelProxy] porque el aviso solo lo entiende nuestro proxy: un
     * torrent lo sirve `TorrentStreamServer` —que ya prioriza piezas por su cuenta— y un archivo
     * local no tiene nada que precalentar.
     */
    fun hayQueAvisar(esDelProxy: Boolean, msDesdeElUltimo: Long): Boolean =
        esDelProxy && msDesdeElUltimo >= MINIMO_ENTRE_AVISOS_MS
}
