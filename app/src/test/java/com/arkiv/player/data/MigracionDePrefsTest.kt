package com.arkiv.player.data

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `valorMigrado(deSettings, deStoreViejo, default)`: `null` in either of the first two means
 * "that key doesn't exist there".
 */
class MigracionDePrefsTest {

    @Test
    fun `el candado 18+ desbloqueado sobrevive a la migracion`() {
        assertEquals(true, valorMigrado(deSettings = null, deStoreViejo = true, default = false))
    }

    @Test
    fun `lo que ya esta en Settings manda, el valor viejo no resucita`() {
        // The person locked it again AFTER migrating: the old store's true is still there,
        // but it can't come back on the next launch.
        assertEquals(false, valorMigrado(deSettings = false, deStoreViejo = true, default = false))
    }

    @Test
    fun `sin store viejo se queda el default`() {
        assertEquals(false, valorMigrado(deSettings = null, deStoreViejo = null, default = false))
    }

    @Test
    fun `la purga ya migrada no se repite aunque el store viejo ya no se pueda leer`() {
        // The day after Task 9: `SecureDeviceStore` no longer exists (or the Keystore broke
        // before getting there) and `deStoreViejo` comes in as `null`, but the purge had already
        // been recorded here -- the old value isn't needed to avoid running it again.
        assertEquals(true, valorMigrado(deSettings = true, deStoreViejo = null, default = false))
    }
}
