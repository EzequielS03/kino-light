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
import com.arkiv.player.playback.MagisEfimero
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

    // Task 10: ofrecer vincular Magis apenas se entra, ANTES que nada más. `MainActivity` recompone
    // acá en cuanto hay sesión de persona -así que TvPantallaDeEntrada ya dejó de existir, y este es
    // el primer lugar donde una pantalla "parecida al login" todavía puede aparecer-. Sirve para las
    // DOS rutas de entrada (login en la propia TV y pareo desde el celular) porque las dos terminan
    // acá. Ver el KDoc de `debeOfrecerVincularMagis` para la condición exacta.
    val accountState by graph.accountManager.state.collectAsStateWithLifecycle()
    val ofertaDescartada by graph.settings.magisOfertaDescartada.collectAsStateWithLifecycle()

    // El estado en memoria de AccountManager arranca con `magisLinked = false` a secas en un
    // arranque en frío con sesión ya guardada -no hay chequeo de red hasta que algo lo pide, ver el
    // KDoc de AccountManager, `_state` inicial-. Sin este refresco la oferta de abajo se dispararía
    // en CADA arranque incluso para quien YA tiene Magis vinculado, justo lo que el brief pide evitar
    // ("no molestar"). `magisConfirmado` frena la decisión hasta tener una respuesta real; mientras
    // tanto se sigue de largo al contenido normal -nunca al revés: un pedido de red que tarda no
    // puede dejar a nadie mirando una pantalla en blanco antes de llegar al home-.
    var magisConfirmado by remember {
        mutableStateOf(
            when (val s = accountState) {
                is com.arkiv.player.pocketbase.AccountState.Conectado -> s.magisLinked
                com.arkiv.player.pocketbase.AccountState.Anonimo -> true
            },
        )
    }
    LaunchedEffect(Unit) {
        if (!magisConfirmado) {
            graph.accountManager.refrescarMagis()
            magisConfirmado = true
        }
    }

    if (magisConfirmado && debeOfrecerVincularMagis(accountState, ofertaDescartada)) {
        TvOfertaVincularMagis(
            account = graph.accountManager,
            accountEmail = (accountState as com.arkiv.player.pocketbase.AccountState.Conectado).email,
            // Se guarda la decisión (Task 10, ver SettingsStore.magisOfertaDescartada): "Ahora no" no
            // vuelve a preguntar en cada arranque. El camino sigue vivo en Ajustes
            // (TvVincularMagisSection), a propósito -esto es un atajo, no la única puerta-.
            onAhoraNo = { graph.settings.setMagisOfertaDescartada(true) },
        )
        return
    }

    // Reproductor unificado: archive/Magis van todos a la misma ruta; PlayerScreen resuelve
    // la fuente a partir del episodeId (ver PlayerSource.kindFor).
    fun goToPlayer(id: String) {
        navController.navigate("player/${Uri.encode(id)}") { launchSingleTop = true }
    }

    // Mantener la pantalla encendida mientras la app de TV esté abierta (no meter el wallpaper).
    val view = LocalView.current
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
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
                onPlayLive = { code ->
                    goToPlayer("${com.arkiv.player.playback.PlayerSource.LIVE_PREFIX}$code")
                },
                onOpenSettings = { navController.navigate("settings") },
                onOpenSearch = { navController.navigate("search") },
                onOpenLibrary = { navController.navigate("library") },
                onOpenLive = { navController.navigate("live") },
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
            // Las secciones de adultos solo si ESTE aparato tiene el código puesto (Ajustes).
            // El gateway responde 409 sin el parámetro, así que el default es el seguro incluso
            // si esta pantalla se abriera por otro camino.
            val desbloqueado = graph.settings.adultosDesbloqueado.value
            val alcance = rememberCoroutineScope()
            TvSeccionesDeCatalogo(
                incluirAdultos = desbloqueado,
                onReproducir = { item ->
                    alcance.launch {
                        if (item.adulto) {
                            // NO pasa por la biblioteca. `addMagisSource` escribiría una fila que se
                            // sincroniza y termina en el celular y en la otra TV, que es exactamente
                            // la fuga del 2026-08-14. El ref viaja por afuera; ver [MagisEfimero].
                            val id = MagisEfimero.idPara(item.id)
                            MagisEfimero.dejar(
                                MagisEfimero.Pendiente(id, item.ref, item.titulo, adulto = true),
                            )
                            goToPlayer(id)
                        } else {
                            // Camino de siempre: guardarlo es lo que le da "seguir viendo" y tarjeta
                            // en la biblioteca, igual que si hubiera entrado por el buscador.
                            val epId = graph.repository.addMagisSource(
                                ref = item.ref,
                                contentId = item.id,
                                title = item.titulo,
                                posterUrl = item.poster.orEmpty(),
                            )
                            if (epId != null) goToPlayer(epId)
                        }
                    }
                },
                onVolver = { navController.popBackStack() },
            )
        }
        composable("categorias_home") {
            TvCategoriasScreen(
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
                // Tarea 14: el reproductor en modo vivo ya existe (bandera `enVivo` en
                // PlayerViewModel/PlayerScreen). `TvLiveGuideScreen.verCanal()` ya dejó en
                // LiveZappingSource la lista con la que se entró -- acá solo hace falta navegar
                // con el prefijo que PlayerSource.kindFor() reconoce como vivo.
                onVerCanal = { canal ->
                    goToPlayer("${com.arkiv.player.playback.PlayerSource.LIVE_PREFIX}${canal.code}")
                },
                onVolver = { navController.popBackStack() },
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
            TvSettingsScreen()
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

/** Desenvuelve el Context hasta encontrar la Activity (para inyectar eventos de tecla). */
private fun Context.findActivity(): Activity? {
    var ctx: Context? = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}
