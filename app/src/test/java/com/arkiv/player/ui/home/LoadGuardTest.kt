package com.arkiv.player.ui.home

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LoadGuardTest {
    @Test fun `solo deja cargar una vez por id`() {
        val guard = LoadGuard()
        assertTrue(guard.shouldLoad("cartelera"))
        assertFalse(guard.shouldLoad("cartelera"))
    }

    @Test fun `ids distintos son independientes`() {
        val guard = LoadGuard()
        assertTrue(guard.shouldLoad("a"))
        assertTrue(guard.shouldLoad("b"))
        assertFalse(guard.shouldLoad("a"))
    }
}
