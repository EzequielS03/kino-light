package com.arkiv.player.remote

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

private class FakeTransport(
    val reachableValue: Boolean,
    val sendResult: Boolean = true,
    var playCount: Int = 0,
    var reachableCount: Int = 0,
    var transportCount: Int = 0,
) : RemoteTransport {
    override suspend fun reachable(): Boolean { reachableCount++; return reachableValue }
    override suspend fun sendPlay(p: PlayPayload, seq: Long): Boolean { playCount++; return sendResult }
    override suspend fun sendKey(key: String, seq: Long) = sendResult
    override suspend fun sendTransport(type: String, payload: String, seq: Long): Boolean {
        transportCount++
        return sendResult
    }
    override fun incoming(): Flow<RemoteCommand> = emptyFlow()
}

class TransportRouterTest {
    private val payload = PlayPayload(PlayKind.ARCHIVE, "i", "e")

    @Test fun prefersLanWhenReachable() = runBlocking {
        val lan = FakeTransport(reachableValue = true)
        val cloud = FakeTransport(reachableValue = true)
        TransportRouter(lan, cloud).sendPlay(payload, 1)
        // El play sí sondea la LAN (y la usa): el atajo de sendTransport no debe contagiarse acá.
        assertEquals(1, lan.reachableCount)
        assertEquals(1, lan.playCount); assertEquals(0, cloud.playCount)
    }

    @Test fun usesCloudWhenLanUnreachable() = runBlocking {
        val lan = FakeTransport(reachableValue = false)
        val cloud = FakeTransport(reachableValue = true)
        TransportRouter(lan, cloud).sendPlay(payload, 1)
        assertEquals(0, lan.playCount); assertEquals(1, cloud.playCount)
    }

    @Test fun transportGoesStraightToCloudWithoutProbingLan() = runBlocking {
        // lan.reachable() cuesta ~2,5s de descubrimiento multicast y LanTransport ni siquiera
        // implementa sendTransport: sondearla era pagar ese retardo en cada botón para nada.
        val lan = FakeTransport(reachableValue = true)
        val cloud = FakeTransport(reachableValue = true)
        val ok = TransportRouter(lan, cloud).sendTransport("pause", "", 1)
        assertEquals(0, lan.reachableCount); assertEquals(0, lan.transportCount)
        assertEquals(1, cloud.transportCount); assertEquals(true, ok)
    }

    @Test fun transportFailsWhenCloudUnreachable() = runBlocking {
        val lan = FakeTransport(reachableValue = true)
        val cloud = FakeTransport(reachableValue = false)
        val ok = TransportRouter(lan, cloud).sendTransport("pause", "", 1)
        assertEquals(false, ok); assertEquals(0, cloud.transportCount); assertEquals(0, lan.transportCount)
    }

    @Test fun failsOverToCloudWhenLanSendFails() = runBlocking {
        val lan = FakeTransport(reachableValue = true, sendResult = false)
        val cloud = FakeTransport(reachableValue = true)
        val ok = TransportRouter(lan, cloud).sendPlay(payload, 1)
        assertEquals(1, lan.playCount); assertEquals(1, cloud.playCount); assertEquals(true, ok)
    }
}
