package com.arkiv.player.ui

import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Downloading
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.SettingsRemote
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.TextButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.arkiv.player.sync.SyncStatus
import kotlinx.coroutines.launch
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.navArgument
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.arkiv.player.ui.add.AddScreen
import com.arkiv.player.ui.catalog.AnimeShowDetailScreen
import com.arkiv.player.ui.catalog.CatalogDetailScreen
import com.arkiv.player.ui.catalog.CineCatalogScreen
import com.arkiv.player.ui.catalog.CineDetailScreen
import com.arkiv.player.ui.catalog.CatalogScreen
import com.arkiv.player.ui.catalog.ShowDetailScreen
import com.arkiv.player.ui.detail.DetailScreen
import com.arkiv.player.ui.downloads.DownloadsScreen
import com.arkiv.player.ui.home.HomeScreen
import com.arkiv.player.ui.library.LibraryScreen
import com.arkiv.player.ui.pairing.QrScannerScreen
import com.arkiv.player.ui.player.PlayerScreen
import com.arkiv.player.ui.remote.RemoteScreen
import com.arkiv.player.ui.search.SearchScreen
import com.arkiv.player.ui.settings.SettingsScreen
import com.arkiv.player.ui.torrent.TorrentScreen
import com.arkiv.player.ui.theme.ArkivBlack
import com.arkiv.player.ui.theme.ArkivRed
import com.arkiv.player.ui.theme.ArkivSurface
import com.arkiv.player.ui.theme.ArkivTextSecondary

internal fun syncMessage(status: SyncStatus): String = when (status) {
    is SyncStatus.Done ->
        if (status.changes > 0) "Sincronizado: ${status.changes} cambio(s) con ${status.peers} dispositivo(s)"
        else "Sincronizado con ${status.peers} dispositivo(s) · al día"
    is SyncStatus.Error -> status.message
    else -> "Sincronizando…"
}

private data class Tab(val route: String, val label: String, val icon: @Composable () -> Unit)

private val TABS = listOf(
    Tab("home", "Inicio") { Icon(Icons.Default.Home, contentDescription = "Inicio") },
    Tab("live", "En vivo") { Icon(Icons.Default.LiveTv, contentDescription = "En vivo") },
    // Catálogo oculto: el home de descubrimiento lo reemplaza. La ruta y CineCatalogScreen siguen
    // vivas — para volver a mostrarlo basta devolver esta línea.
    // Tab("catalog", "Catálogo") { Icon(Icons.Default.Movie, contentDescription = "Catálogo") },
    Tab("downloads", "Descargas") { Icon(Icons.Default.Download, contentDescription = "Descargas") },
    Tab("settings", "Ajustes") { Icon(Icons.Default.Settings, contentDescription = "Ajustes") },
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArkivRoot(
    deepLinkEpisodeId: String? = null,
    onDeepLinkConsumed: () -> Unit = {},
) {
    val navController = rememberNavController()
    val graph = rememberGraph()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val syncStatus by graph.syncManager.status.collectAsStateWithLifecycle()
    // Hay TV a la que enviar solo si este teléfono pareó una: la LAN y la nube dicen POR DÓNDE
    // llegarle, no SI existe. El descubrimiento LAN es anónimo (ver tvTargetAvailable), así que
    // sin pareo no hay diálogo de destino ni icono de control remoto: se reproduce acá y punto
    // (Chromecast/DLNA siguen disponibles dentro del player).
    val tvLinked by graph.settings.tvLinked.collectAsStateWithLifecycle()
    val lanTvAvailable by graph.syncManager.tvAvailable.collectAsStateWithLifecycle()
    val pairedTvAvailable by graph.remoteController.tvPaired.collectAsStateWithLifecycle()
    val tvAvailable = com.arkiv.player.remote.tvTargetAvailable(tvLinked, lanTvAvailable, pairedTvAvailable)

    // Episodio pendiente de elegir dónde reproducir (teléfono vs TV).
    var playChoice by remember { mutableStateOf<String?>(null) }
    // Hoja de conexión (estado del pareo / re-parear / desvincular).
    var showConnection by remember { mutableStateOf(false) }
    // Reproductor unificado: archive y torrent van a la misma ruta; la pantalla resuelve la fuente
    // por el prefijo "torrent:" del id.
    fun goToPlayer(id: String) {
        navController.navigate("player/${Uri.encode(id)}")
    }
    fun playEpisode(id: String) {
        if (tvAvailable) playChoice = id else goToPlayer(id)
    }

    // Preferencias de subtítulos sincronizadas desde el otro dispositivo -> aplicarlas acá.
    androidx.compose.runtime.LaunchedEffect(Unit) {
        graph.remoteController.incomingSubPrefs.collect { json -> graph.subtitlePrefs.applyFromRemote(json) }
    }

    // Calidad de fuentes web sincronizada desde el otro dispositivo -> aplicarla acá.
    androidx.compose.runtime.LaunchedEffect(Unit) {
        graph.remoteController.incomingWebQuality.collect { v ->
            runCatching { graph.settings.setWebQuality(com.arkiv.player.data.WebQuality.valueOf(v)) }
        }
    }

    // Deep-link desde la notificación: abrir el player en ese capítulo.
    androidx.compose.runtime.LaunchedEffect(deepLinkEpisodeId) {
        if (deepLinkEpisodeId != null) {
            navController.navigate("player/${Uri.encode(deepLinkEpisodeId)}") {
                launchSingleTop = true
            }
            onDeepLinkConsumed()
        }
    }
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val isTab = currentRoute in TABS.map { it.route }

    val barState by graph.nowPlayingCoordinator.state.collectAsStateWithLifecycle()
    val overlay = remember { com.arkiv.player.remote.OptimisticOverlay() }
    // Un pin (pausa/seek optimista) pertenece a la fuente que estaba en pantalla cuando se mandó el
    // comando: no significa nada en la otra. Con la regla de "gana el último" el flip entre TV y
    // Chromecast es más frecuente y ahora bidireccional, así que sin este clear un pin pedido a una
    // fuente podía quedar pintado encima de la foto de la OTRA cuando la barra cambiaba de dueño.
    androidx.compose.runtime.LaunchedEffect(barState?.fuente) { overlay.clear() }
    // Solo se hace poll con la app al frente.
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            when (event) {
                androidx.lifecycle.Lifecycle.Event.ON_START -> graph.tvNowPlaying.setActive(true)
                androidx.lifecycle.Lifecycle.Event.ON_STOP -> graph.tvNowPlaying.setActive(false)
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Scaffold(
        containerColor = ArkivBlack,
        topBar = {
            if (currentRoute == "home") {
                TopAppBar(
                    title = {
                        Text("ARKIV", color = ArkivRed, fontWeight = FontWeight.Black)
                    },
                    actions = {
                        IconButton(onClick = { navController.navigate("torrent") }) {
                            Icon(Icons.Default.Downloading, contentDescription = "Reproducir torrent", tint = Color.White)
                        }
                        // Parear con el TV. Va acá, con el resto de los íconos, y no dentro de la
                        // lista del home: ahí se mezclaba con el contenido. Antes su único acceso
                        // era el ícono de la pantalla Biblioteca, que no se encuentra si uno no lo
                        // sabe de antes -- y sin pareo la TV no puede entrar a la app.
                        IconButton(onClick = { showConnection = true }) {
                            Icon(Icons.Default.QrCodeScanner, contentDescription = "Conectar con el TV", tint = Color.White)
                        }
                        // El control remoto solo tiene sentido si hay una TV Arkiv en la red.
                        if (tvAvailable) {
                            IconButton(onClick = { navController.navigate("remote") }) {
                                Icon(Icons.Default.SettingsRemote, contentDescription = "Control remoto de la TV", tint = Color.White)
                            }
                        }
                        if (syncStatus is SyncStatus.Syncing) {
                            CircularProgressIndicator(
                                color = Color.White,
                                strokeWidth = 2.dp,
                                modifier = Modifier.padding(end = 16.dp).size(22.dp),
                            )
                        } else {
                            IconButton(onClick = {
                                scope.launch {
                                    // LAN es best-effort y silencioso: en un setup por nube no hay
                                    // TV en la red WiFi, y eso no debe verse como un fallo.
                                    runCatching { graph.syncManager.syncNow() }
                                    graph.cloudSync.syncNow()
                                    Toast.makeText(context, "Sincronizado", Toast.LENGTH_LONG).show()
                                }
                            }) {
                                Icon(Icons.Default.Sync, contentDescription = "Sincronizar", tint = Color.White)
                            }
                        }
                        // Buscar: el primer icono desde la derecha (la acción más usada del home).
                        IconButton(onClick = { navController.navigate("search") }) {
                            Icon(Icons.Default.Search, contentDescription = "Buscar", tint = Color.White)
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = ArkivBlack),
                )
            }
        },
        // Sin FAB. El "+" abría `AddScreen` (agregar por identifier de archive.org) y quedó sin
        // uso: hoy el contenido entra por la búsqueda. Tapaba contenido del home flotando encima,
        // que es caro para un botón que nadie toca. La ruta "add" y `AddScreen` siguen vivas —
        // mismo criterio que la pestaña Catálogo de más arriba: para volver a mostrarlo alcanza
        // con devolver este bloque.
        bottomBar = {
            Column {
                val render = com.arkiv.player.ui.remote.rememberMiniPlayerRender(
                    bar = barState,
                    overlay = overlay,
                    scrubbing = false,
                )
                if (isTab && render != null && !render.hidden) {
                    // El stream transcodificado hacia el Chromecast sale en vivo (sin duración ni
                    // Range): reposicionarlo exige rearmar la petición de cast, algo que solo sabe
                    // hacer el reproductor (ver Resolución A). `activeUrl` es un `var` plano, no
                    // estado de Compose, pero acá alcanza: mientras se castea la barra recompone
                    // cada 500ms con un BarState fresco, así que el valor se refresca solo con eso.
                    val puedeBuscar = barState?.fuente != com.arkiv.player.remote.BarFuente.CAST ||
                        graph.castTranscoder.activeUrl == null
                    com.arkiv.player.ui.remote.MiniPlayerBar(
                        nowPlaying = render.nowPlaying,
                        positionMs = render.positionMs,
                        stale = render.stale,
                        fuente = barState?.fuente ?: com.arkiv.player.remote.BarFuente.TV,
                        puedeBuscar = puedeBuscar,
                        onCommand = { cmd ->
                            aplicarOptimista(overlay, barState?.fuente, cmd)
                            scope.launch {
                                com.arkiv.player.ui.remote.enviarComandoDeBarra(graph, barState, cmd, context, ::goToPlayer)
                            }
                        },
                        onExpand = { navController.navigate("nowplaying") },
                    )
                }
                if (isTab) {
                    NavigationBar(containerColor = ArkivBlack) {
                        TABS.forEach { tab ->
                            val selected = backStackEntry?.destination?.hierarchy?.any { it.route == tab.route } == true
                            NavigationBarItem(
                                selected = selected,
                                onClick = {
                                    // Entrar al Catálogo desde otra pestaña resetea su búsqueda (el VM
                                    // sobrevive al cambio de pestaña y si no, quedaría mostrando resultados
                                    // viejos con el campo de texto vacío).
                                    if (tab.route == "catalog") graph.catalogResetSignal.tryEmit(Unit)
                                    navController.navigate(tab.route) {
                                        popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                },
                                icon = tab.icon,
                                label = { Text(tab.label) },
                            )
                        }
                    }
                }
            }
        },
    ) { padding ->
        NavHost(navController = navController, startDestination = "home") {
            composable("home") {
                HomeScreen(
                    onOpenItem = { navController.navigate("detail/${Uri.encode(it)}") },
                    onPlayEpisode = { playEpisode(it) },
                    // Directo a este teléfono, sin el diálogo de destino de LiveScreen: ese diálogo
                    // manda el comando remoto con PlayKind.LIVE (ver LiveScreen.enviarATv), algo
                    // que este atajo del home no reconstruye -- reproducir acá siempre funciona.
                    onPlayLive = { code ->
                        goToPlayer("${com.arkiv.player.playback.PlayerSource.LIVE_PREFIX}$code")
                    },
                    // "Ver más canales": mismas opciones que tocar la pestaña "En vivo" abajo, para
                    // que quede marcada como seleccionada y el back stack no crezca por entrar acá.
                    onOpenLive = {
                        navController.navigate("live") {
                            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                    onOpenConnect = { showConnection = true },
                    onOpenSearchRoute = { route -> navController.navigate(route) },
                    onOpenLibrary = { navController.navigate("library") },
                    contentPadding = padding,
                )
            }
            composable("live") {
                com.arkiv.player.ui.live.LiveScreen(
                    // Tarea 14: el reproductor en modo vivo ya existe (bandera `enVivo` en
                    // PlayerViewModel/PlayerScreen). `LiveScreen.abrir()` ya dejó en
                    // LiveZappingSource la lista con la que se entró -- acá solo hace falta navegar
                    // con el prefijo que PlayerSource.kindFor() reconoce como vivo.
                    onAbrirCanal = { code ->
                        goToPlayer("${com.arkiv.player.playback.PlayerSource.LIVE_PREFIX}$code")
                    },
                    contentPadding = padding,
                )
            }
            composable("library") {
                LibraryScreen(
                    onOpenItem = { navController.navigate("detail/${Uri.encode(it)}") },
                    onPlayEpisode = { playEpisode(it) },
                    onOpenConnect = { showConnection = true },
                    contentPadding = padding,
                )
            }
            composable("downloads") {
                DownloadsScreen(
                    contentPadding = padding,
                    onPlayEpisode = { playEpisode(it) },
                )
            }
            composable("settings") { SettingsScreen(contentPadding = padding) }
            composable("catalog") {
                CineCatalogScreen(
                    onOpen = { navController.navigate("cine/${it.type}/${it.id}") },
                    onOpenAnime = { navController.navigate("catalog_anime/$it") },
                    contentPadding = padding,
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
                Box(Modifier.fillMaxSize().padding(padding)) {
                    SearchScreen(
                        onOpenDetail = { route -> navController.navigate(route) },
                        onPlay = { id -> playEpisode(id) },
                        onBack = { navController.popBackStack() },
                        shortcutKind = entry.arguments?.getString("kind"),
                        shortcutTmdbId = entry.arguments?.getString("tmdbId")?.toIntOrNull(),
                        shortcutAnilistId = entry.arguments?.getString("anilistId")?.toLongOrNull(),
                    )
                }
            }
            composable(
                "cine/{type}/{tmdbId}?season={season}&episode={episode}",
                arguments = listOf(
                    navArgument("season") { nullable = true; type = NavType.StringType; defaultValue = null },
                    navArgument("episode") { nullable = true; type = NavType.StringType; defaultValue = null },
                ),
            ) { entry ->
                val type = entry.arguments?.getString("type") ?: "tv"
                val tmdbId = entry.arguments?.getString("tmdbId")?.toIntOrNull() ?: 0
                val season = entry.arguments?.getString("season")?.toIntOrNull()
                val episode = entry.arguments?.getString("episode")?.toIntOrNull()
                Box(Modifier.fillMaxSize().padding(padding)) {
                    CineDetailScreen(
                        tmdbId = tmdbId,
                        type = type,
                        onPlay = { playEpisode(it) },
                        onBack = { navController.popBackStack() },
                        onOpenItem = { navController.navigate("detail/${Uri.encode(it)}") },
                        deepLinkSeason = season,
                        deepLinkEpisode = episode,
                    )
                }
            }
            composable("catalog/{imdbId}") { entry ->
                val imdbId = Uri.decode(entry.arguments?.getString("imdbId").orEmpty())
                Box(Modifier.fillMaxSize().padding(padding)) {
                    CatalogDetailScreen(
                        imdbId = imdbId,
                        onPlay = { playEpisode(it) },
                        onBack = { navController.popBackStack() },
                    )
                }
            }
            composable("catalog_show/{imdbId}") { entry ->
                val imdbId = Uri.decode(entry.arguments?.getString("imdbId").orEmpty())
                Box(Modifier.fillMaxSize().padding(padding)) {
                    ShowDetailScreen(
                        imdbId = imdbId,
                        onPlay = { playEpisode(it) },
                        onBack = { navController.popBackStack() },
                        onOpenItem = { navController.navigate("detail/${Uri.encode(it)}") },
                    )
                }
            }
            composable(
                "catalog_anime/{anilistId}?episode={episode}",
                arguments = listOf(
                    navArgument("episode") { nullable = true; type = NavType.StringType; defaultValue = null },
                ),
            ) { entry ->
                val anilistId = entry.arguments?.getString("anilistId")?.toLongOrNull() ?: 0L
                val episode = entry.arguments?.getString("episode")?.toIntOrNull()
                Box(Modifier.fillMaxSize().padding(padding)) {
                    AnimeShowDetailScreen(
                        anilistId = anilistId,
                        onPlay = { playEpisode(it) },
                        onBack = { navController.popBackStack() },
                        onOpenItem = { navController.navigate("detail/${Uri.encode(it)}") },
                        deepLinkEpisode = episode,
                    )
                }
            }
            composable("remote") {
                Box(Modifier.fillMaxSize().padding(padding)) {
                    RemoteScreen(onBack = { navController.popBackStack() })
                }
            }
            composable("nowplaying") {
                Box(Modifier.fillMaxSize().padding(padding)) {
                    com.arkiv.player.ui.remote.NowPlayingScreen(
                        onBack = { navController.popBackStack() },
                        onOpenRemote = { navController.navigate("remote") },
                        onAbrirEpisodio = { id ->
                            navController.popBackStack()
                            goToPlayer(id)
                        },
                    )
                }
            }
            composable("scanner") {
                Box(Modifier.fillMaxSize().padding(padding)) {
                    QrScannerScreen(
                        pairing = com.arkiv.player.AppGraph.from(context).pairing,
                        onResult = { navController.popBackStack() },
                    )
                }
            }
            composable("torrent") {
                Box(Modifier.fillMaxSize().padding(padding)) {
                    TorrentScreen(
                        onBack = { navController.popBackStack() },
                        onAdded = { itemId ->
                            navController.navigate("detail/${Uri.encode(itemId)}") {
                                popUpTo("torrent") { inclusive = true }
                            }
                        },
                    )
                }
            }
            composable("add") {
                AddScreen(
                    onBack = { navController.popBackStack() },
                    onAdded = { id ->
                        navController.popBackStack()
                        navController.navigate("detail/${Uri.encode(id)}")
                    },
                )
            }
            composable("detail/{itemId}") { entry ->
                val itemId = Uri.decode(entry.arguments?.getString("itemId").orEmpty())
                DetailScreen(
                    identifier = itemId,
                    onBack = { navController.popBackStack() },
                    onPlayEpisode = { playEpisode(it) },
                )
            }
            composable("player/{episodeId}") { entry ->
                val episodeId = Uri.decode(entry.arguments?.getString("episodeId").orEmpty())
                val itemId = episodeId.substringBefore("::")
                PlayerScreen(
                    episodeId = episodeId,
                    onBack = { navController.popBackStack() },
                    onOpenEpisodes = {
                        navController.navigate("detail/${Uri.encode(itemId)}") {
                            popUpTo("player/{episodeId}") { inclusive = true }
                            launchSingleTop = true
                        }
                    },
                    onNextEpisode = { goToPlayer(it) },
                )
            }
        }

        playChoice?.let { epId ->
            Dialog(onDismissRequest = { playChoice = null }) {
                Surface(shape = RoundedCornerShape(20.dp), color = ArkivSurface) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(
                            "¿Qué quieres hacer?",
                            style = MaterialTheme.typography.titleLarge,
                            color = Color.White,
                        )
                        Text(
                            "Detectamos tu TV con Arkiv en la red.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = ArkivTextSecondary,
                        )
                        Spacer(Modifier.height(8.dp))
                        Button(
                            onClick = {
                                playChoice = null
                                scope.launch {
                                    // Torrents (ej. del catálogo) son nuevos: empujar el ítem a la TV primero.
                                    if (epId.startsWith("torrent:")) runCatching { graph.syncManager.syncNow() }
                                    // Transporte híbrido: LAN si están en la misma WiFi, si no por PocketBase (remoto).
                                    val kind = if (epId.startsWith("torrent:")) com.arkiv.player.remote.PlayKind.TORRENT
                                    else com.arkiv.player.remote.PlayKind.ARCHIVE
                                    val ok = graph.remoteController.sendPlay(
                                        com.arkiv.player.remote.PlayPayload(kind, epId, epId),
                                    )
                                    if (ok) {
                                        Toast.makeText(context, "Reproduciendo en la TV", Toast.LENGTH_SHORT).show()
                                    } else {
                                        Toast.makeText(context, "No se pudo conectar con la TV, reproduzco acá", Toast.LENGTH_LONG).show()
                                        goToPlayer(epId)
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = ArkivRed, contentColor = Color.White),
                        ) { Text("En la TV") }
                        Button(
                            onClick = {
                                playChoice = null
                                goToPlayer(epId)
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = ArkivRed, contentColor = Color.White),
                        ) { Text("Este teléfono") }
                        TextButton(
                            onClick = {
                                playChoice = null
                                Toast.makeText(context, "Guardado en tu biblioteca", Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Guardar para después") }
                    }
                }
            }
        }

        if (showConnection) {
            com.arkiv.player.ui.pairing.ConnectionSheet(
                pairing = graph.pairing,
                onRepair = { showConnection = false; navController.navigate("scanner") },
                onUnlink = { showConnection = false; scope.launch { graph.remoteController.unlinkTv() } },
                onDismiss = { showConnection = false },
            )
        }
    }
}

/**
 * Refleja el efecto del comando en la UI antes de que el TV lo confirme.
 *
 * Solo para [com.arkiv.player.remote.BarFuente.TV]: con el Chromecast el `CastPlayer` cambia de
 * estado local y el siguiente tick (≤250ms) ya lo refleja, así que fijar un estado optimista encima
 * solo agrega parpadeo. Si la fuente es CAST (o no hay fuente) se limpia el overlay para no dejar
 * pineado algo que quedó de un comando previo al TV.
 */
private fun aplicarOptimista(
    overlay: com.arkiv.player.remote.OptimisticOverlay,
    fuente: com.arkiv.player.remote.BarFuente?,
    cmd: com.arkiv.player.remote.TransportCommand,
) {
    if (fuente != com.arkiv.player.remote.BarFuente.TV) {
        overlay.clear()
        return
    }
    val ahora = System.currentTimeMillis()
    when (cmd) {
        com.arkiv.player.remote.TransportCommand.Pause ->
            overlay.expectState(com.arkiv.player.remote.TvPlaybackState.PAUSED, ahora)
        com.arkiv.player.remote.TransportCommand.Resume ->
            overlay.expectState(com.arkiv.player.remote.TvPlaybackState.PLAYING, ahora)
        is com.arkiv.player.remote.TransportCommand.Seek ->
            overlay.expectPosition(cmd.positionMs, ahora)
        // Next/Prev cambian de episodio entero: no hay nada útil que fijar, se espera la foto nueva.
        else -> overlay.clear()
    }
}
