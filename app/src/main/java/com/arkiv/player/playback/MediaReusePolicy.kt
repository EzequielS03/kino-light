package com.arkiv.player.playback

/** Un media cargado (o por cargar) visto como lo que importa para decidir: qué es y de dónde sale. */
data class LoadedMedia(val mediaId: String, val uri: String)

/**
 * Si el media que ya está en el player sirve para lo que se pide, o hay que recargarlo.
 *
 * Vive aparte de la pantalla para poder probarse: es la regla que decide si aprovechás el buffer o
 * empezás de cero, y no debería depender de Compose ni de media3.
 */
object MediaReusePolicy {

    enum class Decision { REUSAR_ACTUAL, SALTAR_EN_PLAYLIST, RECARGAR }

    fun decide(
        episodeId: String,
        cargado: List<LoadedMedia>,
        actualMediaId: String?,
        fresco: List<LoadedMedia>,
        isWeb: Boolean,
    ): Decision {
        if (isWeb) return Decision.RECARGAR
        // La identidad del episodio NO alcanza para reusar: hay que mirar de dónde sale. Un torrent
        // se sirve en 127.0.0.1:<puerto efímero>, y `startStream()` mata el servidor anterior y abre
        // otro en un puerto nuevo — el mismo episodeId puede estar cargado apuntando a un puerto ya
        // muerto. Reusarlo dejaba al player sin abrir nada: pantalla negra con el torrent bajando bien.
        val urlCargada = cargado.firstOrNull { it.mediaId == episodeId }?.uri
        val urlFresca = fresco.firstOrNull { it.mediaId == episodeId }?.uri
        if (urlCargada != null && urlCargada != urlFresca) return Decision.RECARGAR
        if (actualMediaId == episodeId) return Decision.REUSAR_ACTUAL
        if (cargado.isNotEmpty() && cargado.map { it.mediaId } == fresco.map { it.mediaId }) {
            return Decision.SALTAR_EN_PLAYLIST
        }
        return Decision.RECARGAR
    }
}
