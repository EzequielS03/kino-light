package com.arkiv.player.ui.tv

import com.arkiv.player.pocketbase.AccountState
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Cubre `debeOfrecerVincularMagis` (Task 10), la condición pura detrás de "ofrecer vincular Magis
 * apenas se entra a la TV". Separada de la Composable a propósito -este proyecto no tiene
 * infraestructura de tests de UI de Compose, ver el KDoc de `entrarDesdeTv`-, así que esta función es
 * la única parte de la feature que se puede probar en un test JVM plano.
 */
class TvOfertaVincularMagisTest {

    @Test fun `anonimo nunca se ofrece, tenga o no descartada`() {
        assertEquals(false, debeOfrecerVincularMagis(AccountState.Anonimo, descartada = false))
        assertEquals(false, debeOfrecerVincularMagis(AccountState.Anonimo, descartada = true))
    }

    @Test fun `conectado sin Magis y sin descartar SI se ofrece`() {
        assertEquals(
            true,
            debeOfrecerVincularMagis(AccountState.Conectado("a@b.co", magisLinked = false), descartada = false),
        )
    }

    @Test fun `conectado y ya vinculado no se ofrece, aunque descartada sea false`() {
        assertEquals(
            false,
            debeOfrecerVincularMagis(AccountState.Conectado("a@b.co", magisLinked = true), descartada = false),
        )
    }

    @Test fun `conectado sin Magis pero ya descartada (Ahora no) no se ofrece`() {
        assertEquals(
            false,
            debeOfrecerVincularMagis(AccountState.Conectado("a@b.co", magisLinked = false), descartada = true),
        )
    }

    @Test fun `vinculado Y descartada tampoco se ofrece (caso limite, las dos razones a la vez)`() {
        assertEquals(
            false,
            debeOfrecerVincularMagis(AccountState.Conectado("a@b.co", magisLinked = true), descartada = true),
        )
    }
}
