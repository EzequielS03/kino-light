package com.arkiv.player.playback

import com.arkiv.player.data.ditu.DituCanal

/**
 * Un canal en vivo de Caracol camino al reproductor.
 *
 * `PlayerViewModel.loadDitu` lee el `ref` de la biblioteca, y un canal en vivo nunca está ahí: no
 * tiene `ref` —lo identifica el par `channelId`/`assetId` que vino con la lista, ver [DituCanal]— ni
 * progreso que guardar. Así que el canal viaja por afuera de la ruta de navegación, con el mismo
 * patrón que [MagisEfimero]: la sección de Caracol lo deja con [dejar] y el reproductor lo toma con
 * [tomar].
 *
 * [tomar] NO lo vacía, igual que [MagisEfimero.tomar]. Cuando vence el token del stream, el
 * reproductor vuelve a resolver el mismo canal (`PlayerViewModel.onDituExoError` → `loadDitu`), y
 * para eso lo tiene que seguir encontrando acá.
 */
internal object DituVivo {

    /** Empieza con `ditu:` para que [PlayerSource.kindFor] lo mande a Caracol (`loadDitu`). */
    const val PREFIX = "ditu:vivo:"

    fun esVivo(episodeId: String): Boolean = episodeId.startsWith(PREFIX)

    private class Pendiente(val episodeId: String, val canal: DituCanal)

    @Volatile
    private var pendiente: Pendiente? = null

    /** Deja [canal] para el reproductor y devuelve el `episodeId` con el que hay que navegar. */
    fun dejar(canal: DituCanal): String {
        val id = "$PREFIX${canal.channelId}"
        pendiente = Pendiente(id, canal)
        return id
    }

    /**
     * El canal dejado para [episodeId], o null si lo que hay guardado es de otra reproducción: así un
     * vivo no reabre un canal viejo.
     */
    fun tomar(episodeId: String): DituCanal? = pendiente?.takeIf { it.episodeId == episodeId }?.canal
}
