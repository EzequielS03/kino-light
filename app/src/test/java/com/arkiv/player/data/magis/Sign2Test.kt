package com.arkiv.player.data.magis

import org.junit.Assert.assertEquals
import org.junit.Test

class Sign2Test {

    @Test
    fun `los cinco vectores reales capturados de la app coinciden`() {
        val vectores = listOf(
            Triple("941d98961990d67e249dcd1ac57378c8", 1786228951248L, "42eda1217c11706f8034f00831f11645"),
            Triple("941d98961990d67e249dcd1ac57378c8", 1786229028826L, "7b7a1751bd8dc9fa4cb38bcc8dd8acb3"),
            Triple("941d98961990d67e249dcd1ac57378c8", 1786229709567L, "0cccdfc85f900a6ee407eedd13003494"),
            Triple("c3ec544b53a526c59ab677ffbdffa1e0", 1786223278615L, "2e055d6f2c0407c82017286e8f4a31ad"),
            Triple("c3ec544b53a526c59ab677ffbdffa1e0", 1786225491689L, "095a0c6ebc25e6570705fd9d16c6b67b"),
        )
        for ((token, momento, esperado) in vectores) {
            assertEquals("momento $momento", esperado, Sign2.signO3(token, momento))
        }
    }
}
