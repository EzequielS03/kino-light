package com.arkiv.player.data.nuevos

/**
 * Cuántos capítulos nuevos mostrar en el badge de una serie de la biblioteca.
 *
 * **Por qué no se cuenta con fechas**, que es lo primero que uno intentaría: `EpisodeEntity` no
 * guarda fecha de alta — solo tiene `updatedAt`, que es el reloj LWW del sync. Y `refreshItem()`
 * reemplaza el ítem con `replaceItem`, que **borra y re-inserta TODOS los episodios**. O sea que
 * después de cualquier refresco los 26 capítulos tendrían `updatedAt` recién puesto y el badge
 * diría "26 nuevos" cada vez. Cualquier diseño basado en timestamps de episodio nace roto acá.
 *
 * Así que se cuenta contra **lo que ya viste listado**: el conteo de episodios que tenía la serie
 * la última vez que abriste su detalle. La diferencia contra el conteo de ahora es lo que apareció
 * desde entonces, sin depender de relojes que se pisan.
 */
object ContadorDeNuevos {

    /**
     * [vistos] es cuántos episodios tenía la serie la última vez que se abrió su detalle, o `null`
     * si nunca se abrió desde que existe este contador.
     *
     * El `null` devuelve 0 a propósito: es el estado de TODA la biblioteca existente el día que
     * esto se estrene, y arrancar mostrando un badge con el total de cada serie sería ruido puro
     * en lugar de una novedad.
     */
    fun cuantos(actuales: Int, vistos: Int?): Int {
        if (vistos == null) return 0
        return (actuales - vistos).coerceAtLeast(0)
    }

    /** Si corresponde pintar el badge. Azúcar sobre [cuantos] para que la UI no compare a mano. */
    fun hayQuePintar(actuales: Int, vistos: Int?): Boolean = cuantos(actuales, vistos) > 0

    /**
     * Qué dejar en `episodiosVistosEnLista` después de guardar capítulos que trajo el usuario (no el
     * portal), como al guardar la temporada entera para reproducir uno.
     *
     * Si el contador ya estaba sellado, se re-sella al total de ahora: el badge es para "salieron
     * capítulos nuevos", no para "acabás de guardar la temporada". Si era `null` (nunca se abrió el
     * detalle) sigue `null`, porque sellarlo acá apagaría el badge de novedades que todavía no
     * ocurrieron.
     */
    fun reSellar(vistos: Int?, totalAhora: Int): Int? = if (vistos == null) null else totalAhora
}
