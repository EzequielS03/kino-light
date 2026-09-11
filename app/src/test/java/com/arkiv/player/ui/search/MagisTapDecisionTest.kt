package com.arkiv.player.ui.search

import com.arkiv.player.data.gateway.GatewayResult
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure decision for tapping a Magis result on the phone: a series opens the season dialog (as
 * today); a movie shows the watch/download dialog, with "download" offered only when a download
 * strategy exists for Magis. Kept pure and Compose-free so it is unit-testable — the dialog itself
 * is not (this repo does not unit-test Compose dialogs).
 */
class MagisTapDecisionTest {

    private fun result(programType: String) =
        GatewayResult(source = "magis", title = "X", ref = "r", extra = mapOf("program_type" to programType))

    @Test fun `series abre la temporada`() {
        val r = result("series")
        assertEquals(MagisTapDecision.OpenSeasonDialog(r), decideMagisTap(r, canDownload = true))
    }

    @Test fun `teleplay tambien abre la temporada`() {
        val r = result("teleplay")
        assertEquals(MagisTapDecision.OpenSeasonDialog(r), decideMagisTap(r, canDownload = true))
    }

    @Test fun `variety tambien abre la temporada`() {
        val r = result("variety")
        assertEquals(MagisTapDecision.OpenSeasonDialog(r), decideMagisTap(r, canDownload = false))
    }

    @Test fun `una serie no ofrece dialogo aunque se pueda bajar`() {
        val r = result("series")
        val decision = decideMagisTap(r, canDownload = true)
        assert(decision is MagisTapDecision.OpenSeasonDialog)
    }

    @Test fun `pelicula muestra el dialogo con descarga ofrecida`() {
        val r = result("movie")
        assertEquals(
            MagisTapDecision.ShowMovieDialog(r, canDownload = true),
            decideMagisTap(r, canDownload = true),
        )
    }

    @Test fun `pelicula sin estrategia de descarga no la ofrece`() {
        val r = result("movie")
        assertEquals(
            MagisTapDecision.ShowMovieDialog(r, canDownload = false),
            decideMagisTap(r, canDownload = false),
        )
    }

    @Test fun `program_type ausente se trata como pelicula`() {
        val r = GatewayResult(source = "magis", title = "X", ref = "r")
        assertEquals(
            MagisTapDecision.ShowMovieDialog(r, canDownload = true),
            decideMagisTap(r, canDownload = true),
        )
    }
}
