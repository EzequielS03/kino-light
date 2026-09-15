package com.arkiv.player.ui.tv

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import android.os.SystemClock
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.arkiv.player.data.magis.MagisAccountState
import com.arkiv.player.playback.MagisEphemeral
import kotlinx.coroutines.launch
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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

    // Task 10 (condition updated in Task 8, sub-project 2B): offer linking Magis right on entry,
    // BEFORE anything else. No longer depends on any Kino session: `MainActivity` composes
    // `ArkivTvRoot` with no session gate (see its "No session gate" comment in MainActivity.kt)
    // and `TvPantallaDeEntrada`/`PanelDeLogin` were removed entirely in Task 9 (sub-project 2B)
    // along with the rest of Kino's login, so this screen decides using only [MagisAccountState]
    // (is Magis linked on THIS device?), never `AccountState`/`AccountManager`. Serves the TWO
    // routes that leave a device without Magis linked (freshly installed, or linked and then
    // unlinked). See `shouldOfferMagisLink`'s KDoc for the exact condition.
    //
    // `showOffer` is decided ONCE, once the real state is confirmed -not on every
    // recomposition-: this is an ENTRY offer, not a gate re-evaluated all the time. If it were,
    // unlinking Magis later from Settings (`TvSettingsAccount`, composed INSIDE the `NavHost`
    // below) would leave `magisAccount.state` at `None` again, and since this `if` is evaluated
    // ABOVE the `NavHost`, the next recomposition would give `true` again, do this `return`, and
    // destroy the Settings screen for someone who didn't ask to come back here -the likely case,
    // not the rare one: whoever linked through this same offer never touched "Ahora no", so
    // `magisOfferDismissed` stays `false`-. `MagisAccount.state` always starts at `None` -it
    // doesn't read the encrypted prefs in the constructor, see its KDoc-, so without waiting for
    // `magisConfirmed` this one-time decision would be made with a `None` that isn't the real
    // answer yet; meanwhile it goes straight through to the normal content -never the other way
    // around: a slow request can't leave anyone staring at a blank screen before reaching home-.
    var magisConfirmed by remember { mutableStateOf(false) }
    var showOffer by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        graph.magisAccount.refresh()
        showOffer = shouldOfferMagisLink(
            graph.magisAccount.state.value,
            graph.settings.magisOfferDismissed.value,
        )
        magisConfirmed = true
    }

    // Keep the screen on while the TV app is open (no wallpaper kicking in). Goes BEFORE the
    // Magis linking offer's `return` on purpose: that screen is where someone types an email
    // letter by letter with the remote, and without this the TV could turn itself off halfway
    // through typing it.
    val view = LocalView.current
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }

    if (magisConfirmed && showOffer) {
        // Reactive inside the `if`, but to CLOSE this same screen when the linking action itself
        // succeeds -not to decide again whether to show it, which is the decision above-.
        val magisState by graph.magisAccount.state.collectAsStateWithLifecycle()
        LaunchedEffect(magisState) {
            if (magisState is MagisAccountState.Linked) showOffer = false
        }
        TvMagisLinkOffer(
            account = graph.magisAccount,
            // The decision is saved (Task 10, see SettingsStore.magisOfferDismissed): "Ahora no"
            // doesn't ask again on every launch. The path stays alive in Settings
            // (TvSettingsAccount), on purpose -this is a shortcut, not the only door-.
            onNotNow = {
                graph.settings.setMagisOfferDismissed(true)
                showOffer = false
            },
        )
        return
    }

    // Unified player: every source shares this one route. PlayerSource.kindFor() resolves the
    // source from the episodeId prefix (Magis/Ditu/live); a legacy id from a source removed in
    // this branch (torrent, archive.org, web) falls into SourceKind.UNKNOWN, and PlayerScreen shows
    // a "no longer available" message for it (see PlayerViewModel.loadUnknownSource).
    fun goToPlayer(id: String) {
        navController.navigate("player/${Uri.encode(id)}") { launchSingleTop = true }
    }

    LaunchedEffect(deepLinkEpisodeId) {
        if (deepLinkEpisodeId != null) {
            goToPlayer(deepLinkEpisodeId)
            onDeepLinkConsumed()
        }
    }

    NavHost(
        navController = navController,
        startDestination = "home",
        modifier = Modifier.fillMaxSize().background(ArkivBlack),
    ) {
        composable("home") {
            // On home, back exits the app: we ask for double-back confirmation to
            // avoid accidental exits from the remote.
            var lastBackAt by remember { mutableStateOf(0L) }
            BackHandler {
                val now = SystemClock.elapsedRealtime()
                if (now - lastBackAt < 2000) {
                    context.findActivity()?.finish()
                } else {
                    lastBackAt = now
                    Toast.makeText(context, "Presiona atrás de nuevo para salir", Toast.LENGTH_SHORT).show()
                }
            }
            TvHomeScreen(
                onOpenItem = { navController.navigate("detail/${Uri.encode(it)}") },
                onPlayEpisode = { goToPlayer(it) },
                onPlayLive = { code ->
                    goToPlayer("${com.arkiv.player.playback.PlayerSource.LIVE_PREFIX}$code")
                },
                onOpenSettings = { navController.navigate("settings") },
                onOpenSearch = { navController.navigate("search") },
                onOpenLibrary = { navController.navigate("library") },
                onOpenLive = { navController.navigate("live") },
                onOpenCaracol = { navController.navigate("caracol") },
                onOpenSearchRoute = { route -> navController.navigate(route) },
                onOpenCategorias = { navController.navigate("categorias") },
                onOpenCategoriasHome = { navController.navigate("categorias_home") },
                onBrowseRow = { rowId, title ->
                    navController.navigate("row_browse/$rowId?title=${android.net.Uri.encode(title)}")
                },
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
                onBrowseRow = { rowId, title ->
                    navController.navigate("row_browse/$rowId?title=${android.net.Uri.encode(title)}")
                },
                shortcutKind = entry.arguments?.getString("kind"),
                shortcutTmdbId = entry.arguments?.getString("tmdbId")?.toIntOrNull(),
                shortcutAnilistId = entry.arguments?.getString("anilistId")?.toLongOrNull(),
            )
        }
        composable("categorias") {
            // The adults sections only if THIS device has the code set (Settings).
            // `MagisLiveCatalog.arbol` filters the 18+ section client-side and blows up with
            // `require` if its root is requested without the flag, so the default is the safe
            // one even if this screen got opened some other way.
            val unlocked = graph.settings.adultsUnlocked.value
            val scope = rememberCoroutineScope()
            TvCatalogSections(
                includeAdults = unlocked,
                onPlay = { item ->
                    scope.launch {
                        if (item.adult) {
                            // Doesn't go through the library. `addMagisSource` would write a row
                            // that shows up right here on this device -- and, until cloud sync was
                            // removed with the rest of this branch's pruning, would also have
                            // synced to the phone and the other TV, which is exactly the 2026-08-14
                            // leak. The ref travels around it instead; see [MagisEphemeral].
                            val id = MagisEphemeral.idFor(item.id)
                            MagisEphemeral.leave(
                                MagisEphemeral.Pending(id, item.ref, item.title, adulto = true),
                            )
                            goToPlayer(id)
                        } else {
                            // Usual path: saving it is what gives it "continue watching" and a
                            // card in the library, same as if it had come in through search.
                            val epId = graph.repository.addMagisSource(
                                ref = item.ref,
                                contentId = item.id,
                                title = item.title,
                                posterUrl = item.poster.orEmpty(),
                            )
                            if (epId != null) goToPlayer(epId)
                        }
                    }
                },
                onBack = { navController.popBackStack() },
            )
        }
        composable("categorias_home") {
            TvCategoriesScreen(
                onBrowseRow = { rowId, title ->
                    navController.navigate("row_browse/$rowId?title=${android.net.Uri.encode(title)}")
                },
                onOpenSearchRoute = { navController.navigate(it) },
                onBack = { navController.popBackStack() },
            )
        }
        composable("library") {
            com.arkiv.player.ui.tv.library.TvLibraryScreen(
                onOpenItem = { navController.navigate("detail/${Uri.encode(it)}") },
                onPlayEpisode = { goToPlayer(it) },
                onBack = { navController.popBackStack() },
            )
        }
        composable("live") {
            TvLiveGuideScreen(
                // Task 14: the player's live mode already exists (`enVivo` flag in
                // PlayerViewModel/PlayerScreen). `TvLiveGuideScreen.watchChannel()` already left
                // in LiveZappingSource the list it was entered with -- here it only needs to
                // navigate with the prefix PlayerSource.kindFor() recognizes as live.
                onWatchChannel = { channel ->
                    goToPlayer("${com.arkiv.player.playback.PlayerSource.LIVE_PREFIX}${channel.code}")
                },
                onBack = { navController.popBackStack() },
            )
        }
        composable("caracol") {
            // What arrives is the episodeId to navigate with: a title's that got saved the same
            // way as from search, or a live channel's that travels via `DituLive`.
            TvCaracolScreen(onPlay = { goToPlayer(it) })
        }
        composable("detail/{itemId}") { entry ->
            val itemId = Uri.decode(entry.arguments?.getString("itemId").orEmpty())
            TvDetailScreen(
                groupKey = itemId,
                onPlayEpisode = { goToPlayer(it) },
            )
        }
        composable("settings") {
            TvSettingsScreen()
        }
        composable("player/{episodeId}") { entry ->
            val episodeId = Uri.decode(entry.arguments?.getString("episodeId").orEmpty())
            // The publisher uses this to know whether something's really playing here. Without
            // this signal it went by NowPlaying.episodeId, which never gets cleared, so the TV
            // kept announcing the last paused chapter and the phone's bar never went away.
            androidx.compose.runtime.DisposableEffect(Unit) {
                com.arkiv.player.playback.NowPlaying.playerOpen = true
                // Not cleared in onDispose: it's only meaningful while playerOpen is true, so
                // clearing it here buys nothing.
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
        composable(
            "row_browse/{rowId}?title={title}",
            arguments = listOf(
                navArgument("rowId") { type = NavType.StringType },
                navArgument("title") { type = NavType.StringType; defaultValue = "" },
            ),
        ) { entry ->
            val rowId = entry.arguments?.getString("rowId").orEmpty()
            val title = entry.arguments?.getString("title").orEmpty()
            TvRowBrowseScreen(
                rowId = rowId,
                title = title,
                onOpenSearchRoute = { route -> navController.navigate(route) },
                onBack = { navController.popBackStack() },
                graph = graph,
            )
        }
    }
}

/** Unwraps the Context until finding the Activity (to inject key events). */
private fun Context.findActivity(): Activity? {
    var ctx: Context? = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}
