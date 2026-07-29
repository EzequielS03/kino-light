package com.arkiv.player.cloudsync

import kotlin.math.max

/** Last-Write-Wins: resuelve conflictos comparando relojes lógicos `updatedAt`. */
object LwwMerge {
    /** true si el remoto gana (remoto.updatedAt > local.updatedAt); empate = se queda el local. */
    fun pickWinner(localUpdatedAt: Long, remoteUpdatedAt: Long): Boolean = remoteUpdatedAt > localUpdatedAt
}

/** Evita que el reloj lógico retroceda si el reloj de pared va atrás. */
fun clampUpdatedAt(candidate: Long, lastKnown: Long): Long = max(candidate, lastKnown + 1)
