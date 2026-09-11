package com.arkiv.player.ui

import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.navArgument
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.arkiv.player.ui.catalog.AnimeShowDetailScreen
import com.arkiv.player.ui.catalog.CineCatalogScreen
import com.arkiv.player.ui.catalog.CineDetailScreen
import com.arkiv.player.ui.detail.DetailScreen
import com.arkiv.player.ui.downloads.DownloadsScreen
import com.arkiv.player.ui.home.HomeScreen
import com.arkiv.player.ui.home.RowBrowseScreen
import com.arkiv.player.ui.library.LibraryScreen
import com.arkiv.player.ui.player.PlayerScreen
import com.arkiv.player.ui.search.SearchScreen
import com.arkiv.player.ui.settings.SettingsScreen
import com.arkiv.player.ui.theme.ArkivBlack
import com.arkiv.player.ui.theme.ArkivRed

private data class Tab(val route: String, val label: String, val icon: @Composable () -> Unit)

// The "Magis" tab (route "catalog" → CineCatalogScreen/CineDetailScreen) was pulled from the bar:
// it was a TMDB catalog whose only CTA ("Buscar fuentes") opened a panel that only listed
// archive.org (deleted in this branch's pruning) — Magis never hooked into it, so the panel was
// always empty (see the finding from this branch's spec final review). The real path to play
// Magis from TMDB already exists and isn't touched here: "Categorías" → a row → a card → the
// search screen (SearchScreen/SearchViewModel.runSourceSearch), which does search Magis. The
// "catalog" route and its screens stay alive in the NavHost in case a future sub-project hooks a
// real Magis search there; to show the tab again it's enough to add it back to this list.
private val TABS = listOf(
    Tab("home", "Inicio") { Icon(Icons.Default.Home, contentDescription = "Inicio") },
    Tab("categorias_home", "Categorías") { Icon(Icons.Default.GridView, contentDescription = "Categorías") },
    Tab("library", "Biblioteca") { Icon(Icons.Default.VideoLibrary, contentDescription = "Biblioteca") },
    Tab("live", "En vivo") { Icon(Icons.Default.LiveTv, contentDescription = "En vivo") },
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
    // Unified player: every source shares this one route. PlayerSource.kindFor() reads the id's
    // prefix to route to Magis/Ditu/live; a legacy id from a source removed in this branch
    // (torrent, archive.org, web) falls into SourceKind.UNKNOWN and PlayerViewModel.loadUnknownSource
    // shows a "no longer available" message instead. Without cloud sync or pairing (Task 5) there's
    // nobody to offer "play on the TV" to: Chromecast/DLNA are still available from inside the player.
    fun goToPlayer(id: String) {
        android.util.Log.w("ArkivNav", "goToPlayer id=$id ruta=${navController.currentBackStackEntry?.destination?.route}")
        navController.navigate("player/${Uri.encode(id)}") { launchSingleTop = true }
    }
    fun playEpisode(id: String) = goToPlayer(id)

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

    // Una sola definición de "ir a una pestaña", para que el rail y la barra no puedan
    // divergir en el comportamiento (reset del catálogo, popUpTo, restoreState).
    fun irA(tab: Tab) {
        if (tab.route == "catalog") graph.catalogResetSignal.tryEmit(Unit)
        navController.navigate(tab.route) {
            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    val ancho = esTabletHorizontal()
    val drawerState = rememberDrawerState(DrawerValue.Closed)

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = isTab && !ancho,
        drawerContent = {
            ModalDrawerSheet(drawerContainerColor = ArkivBlack) {
                Spacer(Modifier.height(24.dp))
                Text(
                    "KINO",
                    color = ArkivRed,
                    fontWeight = FontWeight.Black,
                    style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.padding(horizontal = 28.dp, vertical = 8.dp),
                )
                Spacer(Modifier.height(8.dp))
                TABS.forEach { tab ->
                    val selected = backStackEntry?.destination?.hierarchy?.any { it.route == tab.route } == true
                    NavigationDrawerItem(
                        icon = tab.icon,
                        label = { Text(tab.label) },
                        selected = selected,
                        colors = NavigationDrawerItemDefaults.colors(
                            selectedContainerColor = ArkivRed.copy(alpha = 0.15f),
                            selectedIconColor = ArkivRed,
                            selectedTextColor = ArkivRed,
                            unselectedIconColor = Color.White,
                            unselectedTextColor = Color.White,
                        ),
                        onClick = {
                            scope.launch { drawerState.close() }
                            irA(tab)
                        },
                        modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding),
                    )
                }
            }
        },
    ) {

    Row(Modifier.fillMaxSize()) {
    if (ancho && isTab) {
        NavigationRail(containerColor = ArkivBlack) {
            TABS.forEach { tab ->
                val selected = backStackEntry?.destination?.hierarchy?.any { it.route == tab.route } == true
                NavigationRailItem(
                    selected = selected,
                    onClick = { irA(tab) },
                    icon = tab.icon,
                    label = { Text(tab.label) },
                )
            }
        }
    }
    Scaffold(
        modifier = Modifier.weight(1f),
        containerColor = ArkivBlack,
        topBar = {
            if (isTab) {
                TopAppBar(
                    navigationIcon = {
                        if (!ancho) {
                            IconButton(onClick = { scope.launch { drawerState.open() } }) {
                                Icon(Icons.Default.Menu, contentDescription = "Menú", tint = Color.White)
                            }
                        }
                    },
                    title = {
                        Text("KINO", color = ArkivRed, fontWeight = FontWeight.Black)
                    },
                    actions = {
                        if (currentRoute == "home") {
                            IconButton(onClick = { navController.navigate("search") }) {
                                Icon(Icons.Default.Search, contentDescription = "Buscar", tint = Color.White)
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = ArkivBlack),
                )
            }
        },
        // No FAB. The "+" used to open "add by archive.org identifier", deleted along with the
        // rest of archive.org: content now comes in through search. It covered home content
        // floating on top, which is expensive for a button nobody taps.
        //
        // Sin bottomBar: la barra de "reproduciendo en la TV/Chromecast" dependía de
        // NowPlayingCoordinator/RemoteController, borrados en Task 5 junto con el resto del pareo.
        // Chromecast sigue disponible DESDE DENTRO del reproductor (botón de casteo en PlayerScreen).
    ) { padding ->
        NavHost(navController = navController, startDestination = "home") {
            composable("home") {
                HomeScreen(
                    onOpenItem = { navController.navigate("detail/${Uri.encode(it)}") },
                    onPlayEpisode = { playEpisode(it) },
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
                    onOpenSearchRoute = { route -> navController.navigate(route) },
                    onOpenLibrary = { navController.navigate("library") },
                    onBrowseRow = { rowId, title ->
                        navController.navigate("row_browse/$rowId?title=${android.net.Uri.encode(title)}")
                    },
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
                    contentPadding = padding,
                )
            }
            composable("downloads") {
                DownloadsScreen(
                    contentPadding = padding,
                    onPlayEpisode = { playEpisode(it) },
                )
            }
            composable("settings") {
    SettingsScreen(
        contentPadding = padding,
        onOpenDownloads = { navController.navigate("downloads") },
    )
}
            composable("categorias_home") {
                com.arkiv.player.ui.home.CategoriasScreen(
                    contentPadding = padding,
                    onBrowseRow = { rowId, title ->
                        navController.navigate("row_browse/$rowId?title=${android.net.Uri.encode(title)}")
                    },
                )
            }
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
                        onBrowseRow = { rowId, title ->
                            navController.navigate("row_browse/$rowId?title=${android.net.Uri.encode(title)}")
                        },
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
            composable(
                "row_browse/{rowId}?title={title}",
                arguments = listOf(
                    navArgument("rowId") { type = NavType.StringType },
                    navArgument("title") { type = NavType.StringType; defaultValue = "" },
                ),
            ) { entry ->
                val rowId = entry.arguments?.getString("rowId").orEmpty()
                val title = entry.arguments?.getString("title").orEmpty()
                RowBrowseScreen(
                    rowId = rowId,
                    title = title,
                    onOpenSearchRoute = { route -> navController.navigate(route) },
                    onBack = { navController.popBackStack() },
                    graph = graph,
                )
            }
        }

    }
    } // end Row
    } // end ModalNavigationDrawer
}
