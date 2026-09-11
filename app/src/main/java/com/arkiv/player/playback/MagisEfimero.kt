package com.arkiv.player.playback

/**
 * La reproducción que NO deja rastro: se resuelve, suena, y no escribe una sola fila.
 *
 * Existe por el contenido de adultos. Todo lo demás de Magis llega al reproductor pasando primero
 * por la biblioteca (`ArkivRepository.addMagisSource` guarda el ítem y devuelve el `episodeId` con
 * el que se navega), y de ahí `loadMagis` lee el `ref` para pedirle el stream al gateway. Ese
 * camino es justo el que no puede existir acá: la regla de [ContenidoDeAdultos] es **no escribir**,
 * y una fila en la biblioteca es exactamente lo que no puede haber — it would show up in "Seguir
 * viendo" and keep local playback progress on this device (no cloud sync left to spread it further).
 *
 * Así que el `ref` viaja por afuera. Es el mismo patrón (y por el mismo motivo) que
 * `LiveZappingSource`: la ruta de navegación es un `String`, el `ref` es un token largo y opaco que
 * no tiene por qué andar metido en una URL, y esto sobrevive a que la pantalla se recree.
 *
 * No es un candado de seguridad y no pretende serlo — el candado es el código por aparato, que es
 * lo único que hace aparecer la sección 18+. Esto es la garantía de que, una vez adentro, no queda
 * rastro: sin fila en la biblioteca no hay progreso que guardar, no hay "seguir viendo" que pintar
 * y no hay nada que subir.
 */
object MagisEfimero {

    /** `magis:` para que [PlayerSource.kindFor] lo siga reconociendo como Magis. */
    const val PREFIX = "magis:efimero:"

    fun idPara(contentId: String): String = "$PREFIX$contentId"

    fun esEfimero(episodeId: String): Boolean = episodeId.startsWith(PREFIX)

    /**
     * Lo único que hace falta para reproducir sin biblioteca.
     *
     * [adulto] viaja explícito y no se deduce de "vino por acá". Hoy son lo mismo —este camino
     * existe por el contenido de adultos y nada más entra por él—, pero el día que algo más lo use
     * (una vista previa, un tráiler) esa deducción escrita en ningún lado dejaría de anotar cosas
     * que sí había que anotar, en silencio.
     */
    data class Pendiente(
        val episodeId: String,
        val ref: String,
        val titulo: String,
        val adulto: Boolean,
    )

    @Volatile
    private var pendiente: Pendiente? = null

    fun dejar(p: Pendiente) { pendiente = p }

    /** Lo dejado para [episodeId], o null si lo que hay guardado es de otra reproducción. */
    fun tomar(episodeId: String): Pendiente? = pendiente?.takeIf { it.episodeId == episodeId }
}
