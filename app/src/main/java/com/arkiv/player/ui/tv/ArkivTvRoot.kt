package com.arkiv.player.ui.tv

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import android.os.SystemClock
import android.view.KeyEvent
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.arkiv.player.ui.player.PlayerScreen
import com.arkiv.player.ui.rememberGraph
import com.arkiv.player.ui.theme.ArkivBlack

@Composable
fun ArkivTvRoot(
    deepLinkEpisodeId: String? = null,
    onDeepLinkConsumed: () -> Unit = {},
) {
    val navController = rememberNavController()
    val graph = rememberGraph()
    val context = LocalContext.current

    // Reproductor unificado: archive y torrent van a la misma ruta; la pantalla resuelve la fuente
    // por el prefijo "torrent:" del id.
    fun goToPlayer(id: String) {
        navController.navigate("player/${Uri.encode(id)}") { launchSingleTop = true }
    }

    // Mantener la pantalla encendida mientras la app de TV esté abierta (no meter el wallpaper).
    val view = LocalView.current
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }

    // Teclas del control remoto del teléfono -> inyectarlas como eventos reales de D-pad.
    LaunchedEffect(Unit) {
        graph.remoteController.incomingKeys.collect { code ->
            context.findActivity()?.let { activity ->
                activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
                activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
            }
        }
    }

    LaunchedEffect(deepLinkEpisodeId) {
        if (deepLinkEpisodeId != null) {
            goToPlayer(deepLinkEpisodeId)
            onDeepLinkConsumed()
        }
    }

    // Comando remoto desde el teléfono ("reproducir en la TV") -> abrir el player acá.
    LaunchedEffect(Unit) {
        graph.remoteController.incomingPlay.collect { p -> goToPlayer(p.episodeId) }
    }

    // Preferencias de subtítulos sincronizadas desde el teléfono -> aplicarlas en la TV.
    LaunchedEffect(Unit) {
        graph.remoteController.incomingSubPrefs.collect { json -> graph.subtitlePrefs.applyFromRemote(json) }
    }

    // Calidad de fuentes web sincronizada desde el otro dispositivo -> aplicarla acá.
    LaunchedEffect(Unit) {
        graph.remoteController.incomingWebQuality.collect { v ->
            runCatching { graph.settings.setWebQuality(com.arkiv.player.data.WebQuality.valueOf(v)) }
        }
    }

    // Miniplayer del celu -> aplicar el comando directo sobre el player del servicio.
    // No se inyectan teclas acá: un seek absoluto y un salto de capítulo son semánticos, no
    // direccionales, y no deben depender de qué tiene el foco en la TV.
    LaunchedEffect(Unit) {
        graph.remoteController.incomingTransport.collect { cmd ->
            runCatching {
                when (cmd) {
                    is com.arkiv.player.remote.TransportCommand.Seek ->
                        com.arkiv.player.playback.PlaybackEngine.vlc?.seekTo(cmd.positionMs)
                    com.arkiv.player.remote.TransportCommand.Pause ->
                        com.arkiv.player.playback.PlaybackEngine.vlc?.pause()
                    com.arkiv.player.remote.TransportCommand.Resume ->
                        com.arkiv.player.playback.PlaybackEngine.vlc?.play()
                    com.arkiv.player.remote.TransportCommand.Next -> {
                        val epId = com.arkiv.player.playback.NowPlaying.episodeId ?: return@runCatching
                        graph.repository.nextEpisode(epId)?.let { goToPlayer(it.id) }
                    }
                    com.arkiv.player.remote.TransportCommand.Prev -> {
                        val epId = com.arkiv.player.playback.NowPlaying.episodeId ?: return@runCatching
                        graph.repository.previousEpisode(epId)?.let { goToPlayer(it.id) }
                    }
                    com.arkiv.player.remote.TransportCommand.Stop -> {
                        // Guardado por NowPlaying.playerOpen (el reproductor tiene que estar
                        // compuesto): incomingTransport no hace dedup por seq a propósito (ver su doc
                        // en RemoteController) y CloudTransport.processed es solo en memoria, así que
                        // un "stop" cuyo ack falló puede reentregarse tras reiniciar la app de TV. Sin
                        // esta guarda ese reintento tardío llamaría popBackStack() sobre el destino de
                        // arranque (con el reproductor ya cerrado) y dejaría el NavHost en blanco.
                        if (com.arkiv.player.playback.NowPlaying.playerOpen) {
                            // Parar de verdad: cortar y salir del reproductor, para que la señal
                            // NowPlaying.playerOpen se apague y la barra del celu desaparezca sola.
                            // pause(), no stop(): el onDispose de PlayerScreen (que corre por este pop)
                            // lee posición y duración del player para guardar el progreso; si acá ya
                            // se paró, libVLC puede reportar posición 0 con una duración > 0 todavía
                            // vigente, y ese guard (`dur > 0 && pos in 0 until dur`) lo aceptaría y
                            // pisaría el progreso real con cero. pause() no pierde nada: el pop cierra
                            // el reproductor un instante después de todos modos.
                            com.arkiv.player.playback.PlaybackEngine.vlc?.pause()
                            navController.popBackStack()
                        }
                    }
                }
            }.onFailure { e ->
                if (e is kotlinx.coroutines.CancellationException) throw e
                android.util.Log.w("ArkivRemote", "Error al aplicar comando de transporte: ${e.message}", e)
            }
        }
    }

    NavHost(
        navController = navController,
        startDestination = "home",
        modifier = Modifier.fillMaxSize().background(ArkivBlack),
    ) {
        composable("home") {
            // En home, el back sale de la app: pedimos confirmación con doble-atrás para
            // evitar salidas accidentales del control remoto.
            var lastBackAt by remember { mutableStateOf(0L) }
            BackHandler {
                val now = SystemClock.elapsedRealtime()
                if (now - lastBackAt < 2000) {
                    context.findActivity()?.finish()
                } else {
                    lastBackAt = now
                    Toast.makeText(context, "Presioná atrás de nuevo para salir", Toast.LENGTH_SHORT).show()
                }
            }
            TvHomeScreen(
                onOpenItem = { navController.navigate("detail/${Uri.encode(it)}") },
                onPlayEpisode = { goToPlayer(it) },
                onOpenSettings = { navController.navigate("settings") },
                onOpenSearch = { navController.navigate("search") },
                onOpenSearchRoute = { route -> navController.navigate(route) },
            )
        }
        composable(
            "search?kind={kind}&tmdbId={tmdbId}&anilistId={anilistId}",
            arguments = listOf(
                navArgument("kind") { nullable = true; type = NavType.StringType; defaultValue = null },
                navArgument("tmdbId") { nullable = true; type = NavType.StringType; defaultValue = null },
                navArgument("anilistId") { nullable = true; type = NavType.StringType; defaultValue = null },
            ),
        ) { entry ->
            TvSearchScreen(
                onPlay = { goToPlayer(it) },
                onBack = { navController.popBackStack() },
                shortcutKind = entry.arguments?.getString("kind"),
                shortcutTmdbId = entry.arguments?.getString("tmdbId")?.toIntOrNull(),
                shortcutAnilistId = entry.arguments?.getString("anilistId")?.toLongOrNull(),
            )
        }
        composable("detail/{itemId}") { entry ->
            val itemId = Uri.decode(entry.arguments?.getString("itemId").orEmpty())
            TvDetailScreen(
                groupKey = itemId,
                onPlayEpisode = { goToPlayer(it) },
            )
        }
        composable("settings") {
            TvSettingsScreen(onConnectPhone = { navController.navigate("pairing") })
        }
        composable("pairing") {
            val ctx = androidx.compose.ui.platform.LocalContext.current
            TvPairingScreen(
                pairing = com.arkiv.player.AppGraph.from(ctx).pairing,
                deviceName = android.os.Build.MODEL,
                onDone = { navController.popBackStack() },
            )
        }
        composable("torrent") {
            com.arkiv.player.ui.torrent.TorrentScreen(
                onBack = { navController.popBackStack() },
                onAdded = { itemId ->
                    navController.navigate("detail/${Uri.encode(itemId)}") {
                        popUpTo("torrent") { inclusive = true }
                    }
                },
            )
        }
        composable("player/{episodeId}") { entry ->
            val episodeId = Uri.decode(entry.arguments?.getString("episodeId").orEmpty())
            // El publisher usa esto para saber si de verdad hay algo reproduciéndose acá. Sin esta
            // señal se guiaba por NowPlaying.episodeId, que no se limpia nunca, así que el TV
            // seguía anunciando el último capítulo en pausa y la barra del celu no se iba jamás.
            androidx.compose.runtime.DisposableEffect(Unit) {
                com.arkiv.player.playback.NowPlaying.playerOpen = true
                // No se limpia en el onDispose: solo es significativo mientras playerOpen es true,
                // así que borrarlo acá no compra nada.
                com.arkiv.player.playback.NowPlaying.playerOpenedAtMs = System.currentTimeMillis()
                onDispose { com.arkiv.player.playback.NowPlaying.playerOpen = false }
            }
            PlayerScreen(
                episodeId = episodeId,
                onBack = { navController.popBackStack() },
                onOpenEpisodes = { navController.popBackStack() },
                onNextEpisode = { goToPlayer(it) },
                isTv = true,
            )
        }
    }
}

/** Desenvuelve el Context hasta encontrar la Activity (para inyectar eventos de tecla). */
private fun Context.findActivity(): Activity? {
    var ctx: Context? = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}
