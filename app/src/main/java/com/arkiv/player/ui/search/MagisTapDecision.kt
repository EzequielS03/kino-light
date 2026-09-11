package com.arkiv.player.ui.search

import com.arkiv.player.data.gateway.GatewayResult
import com.arkiv.player.data.gateway.MAGIS_SERIES

/**
 * What tapping a Magis result on the phone should do. Kept as a pure decision, separate from the
 * Compose dialogs it drives, so it can be unit-tested (this repo does not unit-test Compose
 * dialogs — see [decideMagisTap]).
 */
sealed class MagisTapDecision {
    /** Series: open the season dialog to pick a chapter, same as today. */
    data class OpenSeasonDialog(val result: GatewayResult) : MagisTapDecision()

    /** Movie: ask whether to watch or download it. [canDownload] hides the download choice when
     *  no download strategy is registered for Magis (defensive — Magis movies always have one). */
    data class ShowMovieDialog(val result: GatewayResult, val canDownload: Boolean) : MagisTapDecision()
}

/**
 * Decides what a tap on a Magis result should do: series still open the season dialog
 * ([MagisTapDecision.OpenSeasonDialog], unchanged behavior); movies now ask to watch or download
 * ([MagisTapDecision.ShowMovieDialog]) instead of playing right away.
 */
fun decideMagisTap(result: GatewayResult, canDownload: Boolean): MagisTapDecision =
    if (result.extra["program_type"] in MAGIS_SERIES) MagisTapDecision.OpenSeasonDialog(result)
    else MagisTapDecision.ShowMovieDialog(result, canDownload)
