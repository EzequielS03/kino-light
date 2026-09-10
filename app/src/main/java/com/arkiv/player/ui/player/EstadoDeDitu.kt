package com.arkiv.player.ui.player

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Cuántas veces se le pide a Caracol una URL nueva para lo que está sonando antes de avisar. */
internal const val MAX_RECARGAS_DITU = 2

/**
 * Lo que [PlayerViewModel] decide sobre Caracol, aparte y sin Android para poder probarlo en la JVM.
 *
 * Cuida dos cosas:
 *
 * - **Que una resolución tardía no pise el pedido vigente.** `PlayerViewModel.load` no cancela la
 *   carga anterior: si una resolución de Caracol vuelve cuando ya se pidió otro episodio (de
 *   cualquier fuente), publicarla dejaría a `PlayerScreen` componiendo dos reproductores a la vez.
 *   [publicar] la descarta si su episodio ya no es el que se pidió último ([nuevoPedido]).
 * - **Las recargas.** Cuando [DituExoPlayer] agota sus re-preparados, lo más probable es que se haya
 *   vencido el `playback_token` que viaja en las cookies, y eso ningún `prepare()` lo arregla: hace
 *   falta una URL nueva, que viene con otro token. [pedirRecarga] dice si queda alguna (tope
 *   [MAX_RECARGAS_DITU]) o si el error tiene que llegarle a la persona; [volvioAReproducir] repone
 *   el tope.
 */
internal class EstadoDeDitu(private val maxRecargas: Int = MAX_RECARGAS_DITU) {

    private val _actual = MutableStateFlow<DituReproducible?>(null)

    /** Lo de Caracol que la pantalla tiene que reproducir, o `null`. */
    val actual: StateFlow<DituReproducible?> = _actual.asStateFlow()

    /** El episodio que se pidió último, de cualquier fuente. */
    private var vigente: String? = null

    private var recargas = 0

    /** Cuántas cosas se publicaron. Ver [DituReproducible.generacion]. */
    private var publicaciones = 0

    /** Llegó un pedido nuevo: lo de Caracol que hubiera deja de valer y el tope se repone. */
    fun nuevoPedido(episodeId: String) {
        vigente = episodeId
        recargas = 0
        _actual.value = null
    }

    /** Suelta lo que haya sin cambiar el pedido vigente (el vivo, al abrir un canal). */
    fun limpiar() {
        _actual.value = null
    }

    fun esVigente(episodeId: String): Boolean = episodeId == vigente

    /** Publica [r] si su episodio sigue siendo el vigente. Devuelve si lo publicó. */
    fun publicar(r: DituReproducible): Boolean {
        if (!esVigente(r.episodeId)) return false
        publicaciones++
        _actual.value = r.copy(generacion = publicaciones)
        return true
    }

    /**
     * El reproductor se rindió con lo que está sonando. Devuelve el episodio al que hay que pedirle
     * una URL nueva, o `null` si ya no quedan recargas (o no hay nada vigente sonando): ahí el error
     * tiene que llegarle a la persona.
     */
    fun pedirRecarga(): String? {
        val sonando = _actual.value?.episodeId ?: return null
        if (!esVigente(sonando)) return null
        if (recargas >= maxRecargas) return null
        recargas++
        return sonando
    }

    /** El reproductor volvió a READY: las recargas se reponen, igual que sus re-preparados. */
    fun volvioAReproducir() {
        recargas = 0
    }
}
