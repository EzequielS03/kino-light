package com.arkiv.player.ui.player

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Cuántas veces se vuelve a preparar el MISMO stream antes de pedirle a Caracol una URL nueva. */
internal const val MAX_REPREPARADOS_DITU = 3

/** Cuántas veces se le pide a Caracol una URL nueva para lo que está sonando antes de avisar. */
internal const val MAX_RECARGAS_DITU = 2

/**
 * Cuánto tiene que avanzar la posición DE CORRIDO, desde la última recuperación, para que los dos
 * topes ([MAX_REPREPARADOS_DITU] y [MAX_RECARGAS_DITU]) se repongan.
 *
 * Es un número ELEGIDO, no medido. Lo que tiene que separar es "se recuperó y está andando" de
 * "llegó a READY y se murió". Si fuera muy corto, un stream que arranca y se cae a los pocos
 * segundos repondría los topes en cada vuelta y se recuperaría sin fin: martillaría la API de
 * Caracol y el error no le llegaría nunca a la persona. Si fuera muy largo, dos cortes legítimos
 * separados por menos que esto (la publicidad llega varias veces en un programa) gastarían el
 * mismo tope, y el error llegaría antes de tiempo en una película que en realidad andaba.
 */
internal const val REPRODUCCION_ESTABLE_MS = 30_000L

/**
 * El mayor avance entre dos lecturas de la posición que todavía cuenta como reproducir de corrido.
 *
 * El reloj de [DituExoPlayer] lee cada 500 ms, así que reproduciendo a 1× cada lectura avanza
 * alrededor de medio segundo; esto deja margen para una lectura que llegue tarde o una velocidad
 * mayor. Un avance más grande ya no es reproducir: es un salto (un seek), y corta la racha.
 */
internal const val MAX_AVANCE_POR_LECTURA_MS = 3_000L

/**
 * Lo que [PlayerViewModel] decide sobre Caracol, aparte y sin Android para poder probarlo en la JVM.
 *
 * Cuida tres cosas:
 *
 * - **Que una resolución tardía no pise el pedido vigente.** `PlayerViewModel.load` no cancela la
 *   carga anterior: si una resolución de Caracol vuelve cuando ya se pidió otro episodio (de
 *   cualquier fuente), publicarla dejaría a `PlayerScreen` componiendo dos reproductores a la vez.
 *   [publicar] la descarta si su episodio ya no es el que se pidió último ([nuevoPedido]).
 * - **Los dos escalones de recuperación.** Ante un error recuperable, [DituExoPlayer] vuelve a
 *   preparar el mismo stream mientras [pedirRepreparado] lo permita. Agotados esos, lo más probable
 *   es que se haya vencido el `playback_token` que viaja en las cookies, y eso ningún `prepare()` lo
 *   arregla: [pedirRecarga] dice si queda una URL nueva por pedir o si el error tiene que llegarle a
 *   la persona. Una URL nueva es un stream nuevo, así que sus re-preparados empiezan completos; las
 *   recargas no. Aun en el peor caso (cada vuelta se cae enseguida) todo termina en error: 3
 *   re-preparados, 1 recarga, 3, 1 y 3.
 * - **Cuándo se reponen los topes.** Nunca al llegar a READY —eso deja recuperar sin fin un stream
 *   que llega y se muere—, sino cuando la posición avanzó [REPRODUCCION_ESTABLE_MS] de corrido desde
 *   la última recuperación. El reproductor informa cada lectura de su reloj en [avanzo].
 */
internal class EstadoDeDitu(
    private val maxRepreparados: Int = MAX_REPREPARADOS_DITU,
    private val maxRecargas: Int = MAX_RECARGAS_DITU,
) {

    private val _actual = MutableStateFlow<DituReproducible?>(null)

    /** Lo de Caracol que la pantalla tiene que reproducir, o `null`. */
    val actual: StateFlow<DituReproducible?> = _actual.asStateFlow()

    /** El episodio que se pidió último, de cualquier fuente. */
    private var vigente: String? = null

    private var repreparados = 0
    private var recargas = 0

    /** Cuánto avanzó la posición de corrido desde la última recuperación. */
    private var racha = 0L

    /** La última posición leída, o `null` si hay que tomar una base nueva. */
    private var ultimaPosicion: Long? = null

    /** Cuántas cosas se publicaron. Ver [DituReproducible.generacion]. */
    private var publicaciones = 0

    /** Llegó un pedido nuevo: lo de Caracol que hubiera deja de valer y los topes se reponen. */
    fun nuevoPedido(episodeId: String) {
        vigente = episodeId
        repreparados = 0
        recargas = 0
        reiniciarRacha()
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
     * Un error que se arregla volviendo a preparar el mismo stream. `true` = prepararlo otra vez;
     * `false` = ya no quedan, y el error sigue hacia [pedirRecarga].
     */
    fun pedirRepreparado(): Boolean {
        if (repreparados >= maxRepreparados) return false
        repreparados++
        reiniciarRacha()
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
        repreparados = 0
        reiniciarRacha()
        return sonando
    }

    /**
     * Una lectura del reloj del reproductor. Si la posición lleva [REPRODUCCION_ESTABLE_MS]
     * avanzando de corrido desde la última recuperación, los dos topes se reponen.
     *
     * "De corrido": una pausa, un rebuffer, una lectura sin avance o un salto (ver
     * [MAX_AVANCE_POR_LECTURA_MS]) vuelven la racha a cero. Parar a los 20 s y seguir no suma 20 +
     * lo que venga: hay que volver a juntar los 30 s enteros.
     */
    fun avanzo(posicionMs: Long, reproduciendo: Boolean) {
        val anterior = ultimaPosicion
        ultimaPosicion = posicionMs
        val avance = if (anterior == null) 0L else posicionMs - anterior
        if (!reproduciendo || avance <= 0L || avance > MAX_AVANCE_POR_LECTURA_MS) {
            racha = 0L
            return
        }
        racha += avance
        if (racha >= REPRODUCCION_ESTABLE_MS) {
            repreparados = 0
            recargas = 0
        }
    }

    private fun reiniciarRacha() {
        racha = 0L
        ultimaPosicion = null
    }
}
