package com.arkiv.player.data

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `valorMigrado(deSettings, deStoreViejo, default)`: `null` en cualquiera de los dos primeros
 * significa "esa clave no existe ahí".
 */
class MigracionDePrefsTest {

    @Test
    fun `el candado 18+ desbloqueado sobrevive a la migracion`() {
        assertEquals(true, valorMigrado(deSettings = null, deStoreViejo = true, default = false))
    }

    @Test
    fun `lo que ya esta en Settings manda, el valor viejo no resucita`() {
        // La persona volvió a trabar el candado DESPUÉS de migrar: el true del store viejo sigue
        // ahí, pero no puede volver en el próximo arranque.
        assertEquals(false, valorMigrado(deSettings = false, deStoreViejo = true, default = false))
    }

    @Test
    fun `sin store viejo se queda el default`() {
        assertEquals(false, valorMigrado(deSettings = null, deStoreViejo = null, default = false))
    }

    @Test
    fun `la purga de recientes ya aplicada no se vuelve a correr`() {
        assertEquals(true, valorMigrado(deSettings = null, deStoreViejo = true, default = false))
    }
}
