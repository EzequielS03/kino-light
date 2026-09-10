package com.arkiv.player.ui.tv

import com.arkiv.player.data.magis.EstadoDeMagis
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Cubre `debeOfrecerVincularMagis` (Task 10; firma actualizada en Task 8, sub-proyecto 2B: ya no
 * mira ninguna sesión de Kino, solo [EstadoDeMagis]), la condición pura detrás de "ofrecer vincular
 * Magis apenas se entra a la TV". Separada de la Composable a propósito -este proyecto no tiene
 * infraestructura de tests de UI de Compose, ver el KDoc de `entrarDesdeTv`-, así que esta función es
 * la única parte de la feature que se puede probar en un test JVM plano.
 */
class TvOfertaVincularMagisTest {

    @Test fun `sin Magis vinculado y sin descartar SI se ofrece`() {
        assertEquals(true, debeOfrecerVincularMagis(EstadoDeMagis.Sin, descartada = false))
    }

    @Test fun `ya vinculado no se ofrece, aunque descartada sea false`() {
        assertEquals(
            false,
            debeOfrecerVincularMagis(EstadoDeMagis.Vinculada("a@b.co"), descartada = false),
        )
    }

    @Test fun `sin vincular pero ya descartada (Ahora no) no se ofrece`() {
        assertEquals(false, debeOfrecerVincularMagis(EstadoDeMagis.Sin, descartada = true))
    }

    @Test fun `vinculado Y descartada tampoco se ofrece (caso limite, las dos razones a la vez)`() {
        assertEquals(
            false,
            debeOfrecerVincularMagis(EstadoDeMagis.Vinculada("a@b.co"), descartada = true),
        )
    }
}
