package com.arkiv.player.ui.search

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Que cada fuente apague su "Buscando…" apenas termina, sin esperar a las demás. */
class FuentesBuscandoTest {

    private fun FuentesBuscando.buscan(vararg tabs: SourceTab) =
        SourceTab.entries.forEach { t -> assertTrue("$t", buscando(t) == (t in tabs)) }

    @Test fun `al empezar, todas buscan`() {
        FuentesBuscando.empezando().buscan(SourceTab.TODO, SourceTab.MAGIS, SourceTab.CARACOL)
    }

    /** El caso que había: Magis ya había traído todo y su pestaña seguía girando por Caracol. */
    @Test fun `magis respondio, su pestana se apaga y caracol y todo siguen`() {
        FuentesBuscando.empezando().terminoLaFuente("magis").buscan(SourceTab.TODO, SourceTab.CARACOL)
    }

    @Test fun `caracol se cayo, su pestana se apaga igual`() {
        FuentesBuscando.empezando().terminoLaFuente("ditu").buscan(SourceTab.TODO, SourceTab.MAGIS)
    }

    @Test fun `con las dos terminadas todo se apaga aunque no haya llegado el final`() {
        FuentesBuscando.empezando().terminoLaFuente("magis").terminoLaFuente("ditu").buscan()
    }

    /** Una fuente que no mandó ni su SourceDone ni su SourceError no deja nada girando. */
    @Test fun `el final apaga todo aunque una fuente no avisara`() {
        FuentesBuscando.empezando().terminoLaFuente("magis").terminoTodo().buscan()
        FuentesBuscando.empezando().terminoTodo().buscan()
    }

    /** `FuenteCompuesta` nombra "desconocida" a una fuente que se cae antes de anunciarse. */
    @Test fun `una fuente sin nombre no apaga a ninguna`() {
        FuentesBuscando.empezando().terminoLaFuente("desconocida")
            .buscan(SourceTab.TODO, SourceTab.MAGIS, SourceTab.CARACOL)
    }

    @Test fun `sin busqueda en curso nada gira`() {
        FuentesBuscando().buscan()
        assertFalse(FuentesBuscando().alguna)
    }
}
