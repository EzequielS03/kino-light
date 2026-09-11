package com.arkiv.player.playback

/**
 * What content is NOT logged to history: progress, "continue watching", library, recents, and
 * frame thumbnails — those last ones are an image of what was being watched, saved to disk, which
 * makes them the worst one on the list, not the least important.
 *
 * On 2026-08-14, two +18 channels showed up in the "Live channels" row on the home screen.
 * Deleting them from the device wasn't enough: that table synced through this branch's
 * now-removed cloud sync, so they had already traveled to the cloud and could reach the phone and
 * the other TV. Both ends had to be cleaned up.
 *
 * Hence the rule: **don't write it**, instead of filtering it out on read. What isn't written
 * can't slip through some screen we forgot to filter, and — back when sync existed — couldn't be
 * uploaded either.
 */
object ContenidoDeAdultos {

    /**
     * Lo que NO se sabe, se anota — y esa dirección es deliberada.
     *
     * Un `null` es "no tengo el dato", no "es adulto". Tratarlo como adulto dejaría de guardar el
     * progreso de contenido normal sin que nadie se entere, que es un daño silencioso y difícil de
     * rastrear. El riesgo opuesto ya está cubierto por otro lado: al contenido de adultos solo se
     * llega por una sección que no existe sin el código de este aparato.
     */
    fun hayQueAnotar(esAdulto: Boolean?): Boolean = esAdulto != true
}
