package com.arkiv.player.playback

/**
 * Qué contenido NO se anota en el historial: progreso, "seguir viendo", biblioteca, recientes y
 * las miniaturas de frame — esas últimas son una imagen de lo que se estaba viendo, guardada en
 * disco y sincronizada, así que son la peor de la lista y no la menos importante.
 *
 * El 2026-08-14 dos canales +18 aparecieron en la fila "Canales en vivo" de la pantalla principal.
 * Y no alcanzó con borrarlos del aparato: esa tabla se sincroniza, así que ya habían viajado a la
 * nube y podían llegar al celular y a la otra TV. Hubo que limpiarlos en las dos puntas.
 *
 * De ahí la regla: **no se escribe**, en vez de filtrarse al leer. Lo que no se escribe no se puede
 * escapar por una pantalla que nos olvidamos ni se sube a ningún lado.
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
