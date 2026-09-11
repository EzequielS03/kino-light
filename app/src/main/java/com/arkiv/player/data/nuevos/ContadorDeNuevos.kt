package com.arkiv.player.data.nuevos

/**
 * How many new chapters to show in a library series' badge.
 *
 * **Why this doesn't count by date**, the obvious first attempt: `EpisodeEntity` doesn't store a
 * creation date — it only has `updatedAt`, which is the sync LWW clock, and any write that
 * touches an episode row bumps it. Any design based on episode timestamps starts broken here.
 *
 * So it's counted against **what was already listed**: the episode count the series had the last
 * time its detail was opened. The difference against the current count is what appeared since
 * then, without depending on clocks that get overwritten.
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
