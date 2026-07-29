package com.arkiv.player.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TransportCommandTest {

    @Test
    fun `ida y vuelta de cada comando`() {
        val todos = listOf(
            TransportCommand.Pause,
            TransportCommand.Resume,
            TransportCommand.Next,
            TransportCommand.Prev,
            TransportCommand.Seek(754_000),
        )
        for (cmd in todos) {
            val type = TransportCommandCodec.typeOf(cmd)
            val payload = TransportCommandCodec.payloadOf(cmd)
            assertEquals(cmd, TransportCommandCodec.parse(type, payload))
        }
    }

    @Test
    fun `los tipos coinciden con el select de commands`() {
        assertEquals("pause", TransportCommandCodec.typeOf(TransportCommand.Pause))
        assertEquals("resume", TransportCommandCodec.typeOf(TransportCommand.Resume))
        assertEquals("seek", TransportCommandCodec.typeOf(TransportCommand.Seek(0)))
        assertEquals("next", TransportCommandCodec.typeOf(TransportCommand.Next))
        assertEquals("prev", TransportCommandCodec.typeOf(TransportCommand.Prev))
        assertEquals("stop", TransportCommandCodec.typeOf(TransportCommand.Stop))
        assertEquals(
            setOf("pause", "resume", "seek", "next", "prev", "stop"),
            TransportCommandCodec.TYPES,
        )
    }

    @Test
    fun `un tipo desconocido no es un comando de transporte`() {
        assertNull(TransportCommandCodec.parse("play", ""))
        assertNull(TransportCommandCodec.parse("key", "23"))
    }

    @Test
    fun `un seek sin posicion valida se descarta`() {
        assertNull(TransportCommandCodec.parse("seek", ""))
        assertNull(TransportCommandCodec.parse("seek", "abc"))
        assertNull(TransportCommandCodec.parse("seek", null))
    }

    @Test
    fun `un seek negativo se lleva a cero`() {
        assertEquals(TransportCommand.Seek(0), TransportCommandCodec.parse("seek", "-5000"))
    }

    @Test
    fun `los comandos sin payload lo ignoran`() {
        assertTrue(TransportCommandCodec.payloadOf(TransportCommand.Pause).isEmpty())
    }

    @Test
    fun `stop va y vuelve como los demas`() {
        val type = TransportCommandCodec.typeOf(TransportCommand.Stop)
        assertEquals("stop", type)
        assertEquals(
            TransportCommand.Stop,
            TransportCommandCodec.parse(type, TransportCommandCodec.payloadOf(TransportCommand.Stop)),
        )
    }

    @Test
    fun `stop esta entre los tipos que el select debe admitir`() {
        assertTrue(TransportCommandCodec.TYPES.contains("stop"))
    }
}
