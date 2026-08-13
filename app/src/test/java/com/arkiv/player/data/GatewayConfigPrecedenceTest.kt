package com.arkiv.player.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Precedencia de [SettingsStore.applySyncedGatewayConfig], extraída como función pura
 * ([SettingsStore.shouldApplySyncedGateway]) justamente para poder testearla acá: SettingsStore
 * pide un Context real (SharedPreferences) y este módulo no tiene Robolectric.
 */
class GatewayConfigPrecedenceTest {

    @Test fun `TV recien pareado (DEFAULT) aplica la config sincronizada`() {
        assertTrue(
            SettingsStore.shouldApplySyncedGateway(
                currentSource = GatewayConfigSource.DEFAULT,
                gatewayUrl = "https://api.comparadorinternet.co",
            ),
        )
    }

    @Test fun `un re-pareo posterior tambien actualiza (SYNCED no bloquea, para no quedar pegado a una url vieja)`() {
        assertTrue(
            SettingsStore.shouldApplySyncedGateway(
                currentSource = GatewayConfigSource.SYNCED,
                gatewayUrl = "https://api.comparadorinternet.co",
            ),
        )
    }

    @Test fun `una config MANUAL nunca se pisa por sync`() {
        assertFalse(
            SettingsStore.shouldApplySyncedGateway(
                currentSource = GatewayConfigSource.MANUAL,
                gatewayUrl = "https://api.comparadorinternet.co",
            ),
        )
    }

    @Test fun `url en blanco no se aplica aunque la fuente sea DEFAULT (payload viejo o incompleto)`() {
        assertFalse(
            SettingsStore.shouldApplySyncedGateway(
                currentSource = GatewayConfigSource.DEFAULT,
                gatewayUrl = "",
            ),
        )
    }
}
