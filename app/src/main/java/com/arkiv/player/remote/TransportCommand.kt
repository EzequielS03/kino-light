package com.arkiv.player.remote

/**
 * Comandos de transporte del miniplayer (celu → TV). Son SEMÁNTICOS, a diferencia de las teclas del
 * pad: no dependen del foco de la UI del TV, se aplican directo sobre el player.
 */
sealed interface TransportCommand {
    data object Pause : TransportCommand
    data object Resume : TransportCommand
    data object Next : TransportCommand
    data object Prev : TransportCommand

    /** Parar de verdad: cortar la reproducción donde esté sonando, no solo pausarla. */
    data object Stop : TransportCommand

    /** Posición ABSOLUTA. Los ±10s los calcula el celu: así el comando es idempotente. */
    data class Seek(val positionMs: Long) : TransportCommand
}

object TransportCommandCodec {

    const val PAUSE = "pause"
    const val RESUME = "resume"
    const val SEEK = "seek"
    const val NEXT = "next"
    const val PREV = "prev"
    const val STOP = "stop"

    /** Debe coincidir con los valores del select `type` de la colección `commands`. */
    val TYPES: Set<String> = setOf(PAUSE, RESUME, SEEK, NEXT, PREV, STOP)

    fun typeOf(cmd: TransportCommand): String = when (cmd) {
        TransportCommand.Pause -> PAUSE
        TransportCommand.Resume -> RESUME
        TransportCommand.Next -> NEXT
        TransportCommand.Prev -> PREV
        TransportCommand.Stop -> STOP
        is TransportCommand.Seek -> SEEK
    }

    fun payloadOf(cmd: TransportCommand): String = when (cmd) {
        is TransportCommand.Seek -> cmd.positionMs.toString()
        else -> ""
    }

    fun parse(type: String, payload: String?): TransportCommand? = when (type) {
        PAUSE -> TransportCommand.Pause
        RESUME -> TransportCommand.Resume
        NEXT -> TransportCommand.Next
        PREV -> TransportCommand.Prev
        STOP -> TransportCommand.Stop
        SEEK -> payload?.toLongOrNull()?.let { TransportCommand.Seek(it.coerceAtLeast(0)) }
        else -> null
    }
}
