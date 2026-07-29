package com.arkiv.player.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsDefaultsTest {
    @Test fun `el providers url por defecto apunta a github raw, no a blog`() {
        assertEquals(
            "https://raw.githubusercontent.com/lordmacu/arkiv-providers/main/providers.json",
            SettingsStore.DEFAULT_PROVIDERS_URL,
        )
        assertTrue(!SettingsStore.DEFAULT_PROVIDERS_URL.contains("comparadorinternet"))
    }
}
