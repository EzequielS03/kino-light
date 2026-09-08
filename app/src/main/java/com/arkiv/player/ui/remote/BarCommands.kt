package com.arkiv.player.ui.remote

import com.arkiv.player.AppGraph
import com.arkiv.player.remote.BarFuente
import com.arkiv.player.remote.BarState
import com.arkiv.player.remote.TransportCommand
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Manda un comando a donde está sonando de verdad: al Fire TV por PocketBase, o al Chromecast
 * directo. La barra y "Reproduciendo ahora" comparten esto para no poder divergir.
 *
 * No exige hilo principal de quien llama: lo único que toca `graph.castSession` —construirlo la
 * primera vez fuera del hilo principal revienta el check explícito de su init— ocurre DENTRO de
 * cada `withContext(Dispatchers.Main)` de abajo, nunca antes.
 *
 * El `when` de acá es exhaustivo a propósito sobre el sealed interface, SIN `else`: un
 * `TransportCommand` nuevo (ej. `Stop`) tiene que romper la build acá, no caer callado en un
 * `else` y quedar mudo casteando —el mismo tipo de bug que esta función existe para eliminar—.
 * `TransportCommandCodec.typeOf` (mismo paquete `remote`) ya se apoya en esta misma garantía.
 */
internal suspend fun enviarComandoDeBarra(
    graph: AppGraph,
    bar: BarState?,
    cmd: TransportCommand,
    context: android.content.Context,
    abrirEpisodio: (String) -> Unit,
) {
    if (bar?.fuente != BarFuente.CAST) {
        graph.remoteController.sendTransport(cmd)
        if (cmd == TransportCommand.Stop) graph.tvNowPlaying.clearState()
        return
    }
    when (cmd) {
        // Siguiente/anterior no se pueden armar acá: para castear un episodio hay que saber su códec
        // de audio (si el receptor no lo decodifica hay que transcodificarlo — es lo que arregla el
        // video mudo) y, si es torrent, tener el motor corriendo con su URL de LAN. Las dos cosas
        // viven en el reproductor, así que se abre el reproductor con el vecino y su rama de casteo
        // hace el resto. Un solo camino que arma peticiones de cast, no dos.
        TransportCommand.Next, TransportCommand.Prev -> {
            val id = bar.nowPlaying.episodeId
            val vecino = if (cmd == TransportCommand.Next) {
                graph.repository.nextEpisode(id)
            } else {
                graph.repository.previousEpisode(id)
            }
            vecino?.let { withContext(Dispatchers.Main) { abrirEpisodio(it.id) } }
        }
        TransportCommand.Pause -> withContext(Dispatchers.Main) { graph.castSession?.player?.pause() }
        TransportCommand.Resume -> withContext(Dispatchers.Main) { graph.castSession?.player?.play() }
        // Parar de verdad: termina la sesión de cast (no solo pausa) — ver CastSessionManager.
        // También suelta los recursos de red de ESTE proceso, porque el receptor les estaba sacando
        // los bytes. Es el mismo par que suelta PlaybackService.releaseNetworkResources(), que en
        // este camino NO corre: está gateado por `isCasting()` justo para no cortarle el video a la
        // TV al salir del reproductor.
        //
        // A diferencia del onDispose de PlayerScreen (que deja el servicio vivo a propósito mientras
        // se castea, porque la TV sigue jalando bytes), este botón solo es alcanzable desde
        // pantallas donde el reproductor NO está compuesto, así que nada más en la app está usando
        // el stream en este instante. Las llamadas son idempotentes y a prueba de nulls.
        TransportCommand.Stop -> withContext(Dispatchers.Main) {
            graph.castSession?.stopIntentionally()
            runCatching { graph.archiveCacheProxy.stop() }
        }
        // La barra maneja la posición del CONTENIDO; el receptor cuenta desde su propio cero cuando
        // el stream va transcodificado, así que hay que restarle el desfase.
        is TransportCommand.Seek -> withContext(Dispatchers.Main) {
            graph.castSession?.let { s ->
                s.player.seekTo((cmd.positionMs - s.baseOffsetMs).coerceAtLeast(0))
            }
        }
    }
}
