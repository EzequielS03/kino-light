package com.arkiv.player.remote

/** Estado de transporte del TV. BUFFERING existe aparte para no mostrar la barra congelada sin explicación. */
enum class TvPlaybackState { PLAYING, PAUSED, BUFFERING }

/** Foto del estado de reproducción del TV, tal como viaja en `devices.nowPlaying`. */
data class TvNowPlaying(
    val episodeId: String,
    val itemId: String,
    val kind: String,
    val title: String,
    val subtitle: String,
    val posterUrl: String,
    val positionMs: Long,
    val durationMs: Long,
    val state: TvPlaybackState,
    val hasNext: Boolean,
    val hasPrev: Boolean,
    val at: String,
    /** Instante (reloj del TV) en que se abrió el reproductor. Junto con `episodeId` forma la
     *  identidad de "arranque" que usa el celu para notar que el TV reinició el MISMO capítulo (ver
     *  `NowPlayingCoordinator`); episodeId solo no alcanza. Default 0 para que las construcciones ya
     *  existentes sigan compilando. */
    val startedAtMs: Long = 0,
)

/**
 * Una foto más el instante del reloj LOCAL en que llegó. La extrapolación usa `receivedAtMs`, nunca
 * `nowPlaying.at`: comparar relojes de dos dispositivos exigiría sincronizarlos.
 */
data class TvSnapshot(val nowPlaying: TvNowPlaying, val receivedAtMs: Long)
