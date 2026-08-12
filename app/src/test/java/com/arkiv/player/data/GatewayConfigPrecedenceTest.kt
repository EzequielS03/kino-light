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
                arkivApiKey = "clave-real",
            ),
        )
    }

    @Test fun `un re-pareo posterior tambien actualiza (SYNCED no bloquea, para no quedar pegado a una llave vieja)`() {
        assertTrue(
            SettingsStore.shouldApplySyncedGateway(
                currentSource = GatewayConfigSource.SYNCED,
                gatewayUrl = "https://api.comparadorinternet.co",
                arkivApiKey = "clave-rotada",
            ),
        )
    }

    @Test fun `una config MANUAL nunca se pisa por sync`() {
        assertFalse(
            SettingsStore.shouldApplySyncedGateway(
                currentSource = GatewayConfigSource.MANUAL,
                gatewayUrl = "https://api.comparadorinternet.co",
                arkivApiKey = "clave-real",
            ),
        )
    }

    @Test fun `llave en blanco no se aplica aunque la fuente sea DEFAULT (payload viejo o incompleto)`() {
        assertFalse(
            SettingsStore.shouldApplySyncedGateway(
                currentSource = GatewayConfigSource.DEFAULT,
                gatewayUrl = "https://api.comparadorinternet.co",
                arkivApiKey = "",
            ),
        )
    }

    @Test fun `url en blanco no se aplica aunque la fuente sea DEFAULT`() {
        assertFalse(
            SettingsStore.shouldApplySyncedGateway(
                currentSource = GatewayConfigSource.DEFAULT,
                gatewayUrl = "",
                arkivApiKey = "clave-real",
            ),
        )
    }

    @Test fun `ambos en blanco no se aplica`() {
        assertFalse(
            SettingsStore.shouldApplySyncedGateway(
                currentSource = GatewayConfigSource.DEFAULT,
                gatewayUrl = "",
                arkivApiKey = "",
            ),
        )
    }
}
