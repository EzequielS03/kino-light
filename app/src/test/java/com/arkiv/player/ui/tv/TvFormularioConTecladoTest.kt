package com.arkiv.player.ui.tv

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Width split between the keyboard and the fields (Task 11, see the KDoc of [PESO_TECLADO] in
 * `TvFormularioConTeclado.kt`): it's done by weight, and the keyboard keeps more of it, because
 * it's what gets used key by key with the remote while the fields only display text already typed.
 *
 * The only screen that calls `TvTecladoYCampos` today is [TvOfertaVincularMagis] (the Magis
 * linking offer), so this constant only needs to be right for that one screen.
 */
class TvFormularioConTecladoTest {

    @Test fun `el reparto por peso conserva que el teclado se lleve mas`() {
        assertTrue(PESO_TECLADO > PESO_CAMPOS)
    }
}
