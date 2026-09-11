package com.arkiv.player.ui.tv

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Width split between the keyboard and the fields (Task 11, see the KDoc of [PESO_TECLADO] in
 * `TvFormularioConTeclado.kt`): it's done by weight, and the keyboard keeps more of it, because
 * it's what gets used key by key with the remote while the fields only display text already typed.
 *
 * The TWO screens that share `TvTecladoYCampos` (the TV login and the Magis linking offer) read
 * these same constants: a wrong number here breaks both alike, so one test covers both.
 */
class TvFormularioConTecladoTest {

    @Test fun `el reparto por peso conserva que el teclado se lleve mas`() {
        assertTrue(PESO_TECLADO > PESO_CAMPOS)
    }
}
