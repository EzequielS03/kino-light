package com.arkiv.player.pocketbase

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SseFrameTest {
    @Test
    fun parsesCompleteFrame() {
        val p = SseFrameParser()
        assertNull(p.feed("id:abc"))
        assertNull(p.feed("event:library_items/xyz"))
        assertNull(p.feed("data:{\"action\":\"create\"}"))
        val frame = p.feed("") // línea en blanco = fin de frame
        assertEquals("abc", frame!!.id)
        assertEquals("library_items/xyz", frame.event)
        assertEquals("{\"action\":\"create\"}", frame.data)
    }

    @Test
    fun multilineDataJoinedWithNewline() {
        val p = SseFrameParser()
        p.feed("data:line1")
        p.feed("data:line2")
        val frame = p.feed("")
        assertEquals("line1\nline2", frame!!.data)
    }

    @Test
    fun tolerantToSpaceAfterColon() {
        val p = SseFrameParser()
        p.feed("event: PB_CONNECT")
        p.feed("data: {\"clientId\":\"c1\"}")
        val frame = p.feed("")
        assertEquals("PB_CONNECT", frame!!.event)
        assertEquals("{\"clientId\":\"c1\"}", frame.data)
    }
}
