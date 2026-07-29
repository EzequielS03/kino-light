package com.arkiv.player.remote

import kotlinx.coroutines.flow.Flow

/** Un transporte para enviar comandos al otro dispositivo y recibir los suyos. */
interface RemoteTransport {
    /** ¿Este transporte puede alcanzar al otro dispositivo ahora mismo? */
    suspend fun reachable(): Boolean
    suspend fun sendPlay(p: PlayPayload, seq: Long): Boolean
    suspend fun sendKey(key: String, seq: Long): Boolean
    /** Sincroniza las preferencias de subtítulos (JSON). Por defecto no soportado (→ failover). */
    suspend fun sendSubPrefs(json: String, seq: Long): Boolean = false
    suspend fun sendWebQuality(value: String, seq: Long): Boolean = false
    /**
     * Comando de transporte del miniplayer. Genérico a propósito: un método en vez de cinco.
     * Por defecto no soportado → el router hace failover a la nube (LAN no lo implementa).
     */
    suspend fun sendTransport(type: String, payload: String, seq: Long): Boolean = false
    /** Comandos entrantes dirigidos a este dispositivo (lo consume el TV). */
    fun incoming(): Flow<RemoteCommand>
}
