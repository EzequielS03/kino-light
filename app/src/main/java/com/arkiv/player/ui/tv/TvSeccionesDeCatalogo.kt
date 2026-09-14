package com.arkiv.player.ui.tv

import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import com.arkiv.player.data.gateway.ItemDeCatalogo
import com.arkiv.player.data.gateway.SeccionDeCatalogo
import com.arkiv.player.ui.rememberGraph
import com.arkiv.player.ui.theme.ArkivBlack
import com.arkiv.player.ui.theme.ArkivRed
import com.arkiv.player.ui.theme.ArkivSurface
import com.arkiv.player.ui.theme.ArkivTextSecondary
import kotlinx.coroutines.delay

/**
 * Navegación del catálogo de Magis: **la misma forma que el inicio** — cada sección es una fila
 * horizontal con su nombre encima.
 *
 * Antes eran dos columnas (la lista de secciones a la izquierda, una grilla a la derecha). Se
 * cambió porque obligaba a aprender un segundo modo de moverse dentro de la misma app: en el inicio
 * bajás entre filas y te movés de a una tarjeta, y acá había que saltar entre columnas para ver qué
 * traía cada sección. Con filas, elegir sección y ver su contenido son el MISMO gesto.
 *
 * Reusa las piezas del inicio a propósito y no copias suyas ([TvRowLabel], [TvLandscapeCard] y —lo
 * que más importa— [PivotoDeTv] y [TraerConScrollMinimo]): el comportamiento del foco en Fire TV es
 * justo lo que costó afinar ahí, y dos implementaciones se despegarían a la primera corrección.
 *
 * Elegir un ítem REPRODUCE, por dos caminos distintos según de dónde salga: uno normal, que lo
 * guarda en la biblioteca como cualquier cosa que se reproduce (y así tiene "seguir viendo"), y uno
 * efímero para el contenido de adultos, que no escribe una sola fila — ver
 * [com.arkiv.player.playback.MagisEphemeral]. The separation isn't cosmetic: on 2026-08-14 two 18+
 * channels leaked into the main screen, and deleting them from the device wasn't enough, because
 * at the time that table synced and they'd already traveled to the cloud (cloud sync was removed
 * entirely in this branch's pruning, so that specific risk is gone -- but an adult-content row
 * would still show up in "Continue watching" and in the library of this SAME device, which is
 * reason enough to keep not writing it).
 *
 * Las series todavía no reproducen: hay que pedirle los capítulos al portal (`MagisCatalog.detail`)
 * y elegir uno. Se listan con su marca y no aceptan el clic.
 *
 * Las raíces (Películas, Series, Infantil, Anime y —si el aparato tiene el código— 18+) son tabs
 * arriba: en un televisor el ancho es el recurso escaso, y una fila horizontal es el gesto natural
 * del control para "cambiar de sección grande".
 */
@OptIn(ExperimentalTvMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun TvSeccionesDeCatalogo(
    /** Si este aparato tiene el código puesto: agrega la raíz 18+ al final de la lista. */
    incluirAdultos: Boolean = false,
    /** Reproducir una película. Las series y lo que no trae `ref` no llegan acá. */
    onReproducir: (ItemDeCatalogo) -> Unit,
    onVolver: () -> Unit,
) {
    val graph = rememberGraph()
    // La de adultos va ÚLTIMA y solo si el aparato está desbloqueado: no puede quedar en el camino
    // de quien está navegando el catálogo normal.
    val raices = remember(incluirAdultos) {
        buildList {
            add("peliculas" to "Películas")
            add("series" to "Series")
            add("infantil" to "Infantil")
            add("anime" to "Anime")
            if (incluirAdultos) add("adultos" to "18+")
        }
    }
    var raizIdx by remember { mutableStateOf(0) }
    val raiz = raices[raizIdx].first
    var secciones by remember { mutableStateOf<List<SeccionDeCatalogo>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var cargando by remember { mutableStateOf(true) }

    BackHandler(onBack = onVolver)

    LaunchedEffect(raiz, incluirAdultos) {
        secciones = emptyList()
        cargando = true
        runCatching { graph.catalogoDeVivo.tree(raiz, incluirAdultos) }
            .onSuccess { secciones = it; error = null }
            .onFailure { error = it.message ?: "No se pudo cargar" }
        cargando = false
    }

    // ENGANCHE AL BORDE DE FILA, portado TAL CUAL del inicio (ver el KDoc de su `rowsListState`).
    //
    // Que sea idéntico depende de una condición estructural: **un ítem de la lista = una fila
    // enfocable**. El inicio la cumple, y con dos ranuras visibles redondear a la frontera más
    // cercana no puede fallar — la fila enfocada queda siempre en una de las dos, caiga para donde
    // caiga.
    //
    // Acá se intentó con la SECCIÓN como ítem (dos filas adentro) y no funciona: la zona mostraba
    // una sola ranura, así que el redondeo podía echar de pantalla justo la fila enfocada —quedaba
    // el hero hablando de una sección y las filas mostrando otra, sin ninguna tarjeta marcada— y
    // anclarlo a la sección enfocada tapaba eso pero peleaba con el `bringIntoView` del foco cuando
    // estabas en la segunda fila. Por eso la lista se aplana a filas: la condición se vuelve a
    // cumplir y esta lógica se copia sin tocarle nada.
    val estadoDeLasFilas = rememberLazyListState()
    LaunchedEffect(estadoDeLasFilas) {
        snapshotFlow { estadoDeLasFilas.isScrollInProgress }.collect { enMovimiento ->
            if (enMovimiento) return@collect
            val corrimiento = estadoDeLasFilas.firstVisibleItemScrollOffset
            if (corrimiento == 0) return@collect
            val alto = estadoDeLasFilas.layoutInfo.visibleItemsInfo.firstOrNull()?.size ?: return@collect
            val destino = estadoDeLasFilas.firstVisibleItemIndex + if (corrimiento > alto / 2) 1 else 0
            runCatching { estadoDeLasFilas.animateScrollToItem(destino) }
        }
    }

    val focoPrimerTab = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        repeat(20) {
            if (runCatching { focoPrimerTab.requestFocus() }.isSuccess) return@LaunchedEffect
            delay(50)
        }
    }

    // MISMA ESTRUCTURA QUE EL INICIO, y por el mismo motivo: la zona de filas mide un número EXACTO
    // de unidades y el hero se queda con el resto (`weight(1f)`). Sin eso, el LazyColumn se estira
    // hasta el borde de la pantalla y la última fila entra por la mitad — media carátula cortada
    // abajo, que es justo lo que se veía.
    //
    // Lo que cambia es la UNIDAD. En el inicio es una fila (etiqueta + tarjeta); acá una sección son
    // DOS filas de tarjetas bajo una etiqueta, así que la unidad es la sección entera y se muestra
    // una por pantallazo. Quedan dos filas de carátulas visibles, igual que en el inicio, y ninguna
    // sección se ve por la mitad.
    val cardHeight = 92.dp
    val rowGap = 14.dp
    val gapEntreFilas = 8.dp
    val rowsTopPad = 6.dp
    // +12: la fila lleva 6 dp de aire arriba y abajo para que el zoom del foco no se recorte.
    val altoDeFila = cardHeight + 20.dp
    // La unidad es UNA FILA, igual que en el inicio — no una sección. Se probó con la sección entera
    // como unidad y quedaba peor: moverse entre las dos filas de una misma sección deja el scroll a
    // mitad de unidad, y entonces se corta arriba Y abajo. Con la fila como unidad, cada movimiento
    // del foco cae siempre en un borde.
    //
    // EL ITEM DEL LazyColumn ES UNA SECCION ENTERA, asi que el interior de la zona tiene que medir
    // eso EXACTO -- no dos veces una fila. La cuenta tiene que salir de como esta armado el item de
    // verdad (dos filas, el hueco entre ellas y el cierre de seccion); cuando no coincidia por 6 dp,
    // esos 6 dp eran el borde de la fila de abajo apareciendo cortado.
    //
    // El inicio hace lo mismo: `region = rowUnit * 2 + rowsTopPad` con `padding(top = rowsTopPad)`,
    // de forma que el interior queda clavado en un multiplo entero de su unidad.
    // Unidad = una fila (tarjeta + su aire + el hueco que la separa de la siguiente), y la zona mide
    // DOS. Igual que el inicio: `region = unidad * 2 + rowsTopPad` con `padding(top = rowsTopPad)`,
    // de forma que el interior queda clavado en dos unidades enteras.
    val altoDeUnidad = altoDeFila + gapEntreFilas
    val altoDeLaZona = altoDeUnidad * 2 + rowsTopPad

    // El ítem que tiene el foco AHORA. Es lo que hace legible una grilla de pósters a tres metros:
    // el título completo de la tarjeta chica no entra, y sin esto no hay forma de saber en qué
    // estás parado sin entrar. Mismo rol que el `featured` del inicio.
    var enfocado by remember { mutableStateOf<ItemDeCatalogo?>(null) }
    // De que seccion es lo enfocado. Va en el hero y no encima de cada fila: ahi partia en dos la
    // pareja de filas de una misma seccion, que es justo lo que hay que leer junto.
    var seccionEnfocada by remember { mutableStateOf("") }
    // Aviso transitorio para lo que no se puede reproducir. Se borra solo: es informacion de un
    // momento, y dejarla fija en pantalla confundiria con el titulo que si esta enfocado.
    var aviso by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(aviso) {
        if (aviso == null) return@LaunchedEffect
        delay(3500)
        aviso = null
    }
    // Al cambiar de raíz, lo que había enfocado ya no está en pantalla: dejarlo pintado mostraría
    // el nombre de una película de otra pestaña.
    LaunchedEffect(raiz) { enfocado = null; seccionEnfocada = "" }

    Box(Modifier.fillMaxSize().background(ArkivBlack)) {
        FondoDelHero(imageUrl = enfocado?.poster)
        Column(Modifier.fillMaxSize().padding(top = 24.dp)) {
        val conItems = secciones.filter { it.items.isNotEmpty() }

        // --- ZONA FIJA (no scrollea): cabecera + hero. Se queda con el espacio sobrante ---
        Column(Modifier.fillMaxWidth().weight(1f)) {
        // Título y tabs en LA MISMA línea, los tabs a la derecha. Antes iban en tres renglones
        // apilados (título, ayuda, tabs) y eso se comía el alto donde ahora va el hero. El renglón
        // de ayuda ("volvé con Atrás") salió del todo: Atrás es el gesto que ya funciona en todas
        // las pantallas de la app, y explicarlo solo acá ocupaba espacio para no decir nada nuevo.
        //
        // Los tabs se pintan SIEMPRE, aunque la raíz elegida esté cargando o falle: si el estado de
        // carga los tapara, no habría forma de volver a elegir otra con el control.
        Row(
            Modifier.fillMaxWidth().padding(start = 48.dp, end = 48.dp, bottom = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Categorías",
                style = MaterialTheme.typography.headlineSmall,
                color = Color.White,
            )
            Spacer(Modifier.weight(1f))
            LazyRow(
                // Aire para el zoom y el borde del foco: sin esto el tab enfocado se recorta
                // contra los limites de su propia fila.
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(raices.size) { i ->
                    TvTab(
                        label = raices[i].second,
                        selected = i == raizIdx,
                        onClick = { raizIdx = i },
                        modifier = if (i == 0) Modifier.focusRequester(focoPrimerTab) else Modifier,
                    )
                }
            }
        }

        if (conItems.isEmpty()) {
            Mensaje(if (cargando) "Cargando…" else (error ?: "Sin secciones"))
        } else {
            TextoDelHero(enfocado, seccionEnfocada, aviso)
        }
        }

        if (conItems.isEmpty()) return@Column

        // La lista se aplana a FILAS, no a secciones: un ítem del LazyColumn tiene que ser una fila
        // enfocable para que el enganche del inicio valga tal cual (ver su comentario más arriba).
        // Las dos filas de una sección quedan como dos ítems consecutivos, y como la zona mide
        // exactamente dos, alineado se ve la sección entera. El nombre ya no va acá —vive en el
        // hero—, así que todos los ítems miden lo mismo, que es la otra mitad de la condición.
        val filas = remember(conItems) {
            conItems.flatMap { s ->
                filasDe(s.items).mapIndexed { i, f -> FilaDelCatalogo("${s.id}:$i", s.nombre, f) }
            }
        }

        // --- FILAS (única zona que scrollea; alto fijo = exactamente DOS filas, como el inicio) ---
        // El pivote VERTICAL es el de scroll mínimo, no el del 30 %: con filas de este alto, el 30 %
        // del contenedor cae a mitad de fila y deja media tarjeta cortada arriba. Es el mismo
        // problema (y la misma solución) que ya se resolvió en el inicio.
        CompositionLocalProvider(LocalBringIntoViewSpec provides TraerConScrollMinimo) {
            LazyColumn(
                state = estadoDeLasFilas,
                modifier = Modifier.fillMaxWidth().height(altoDeLaZona).padding(top = rowsTopPad),
            ) {
                items(filas, key = { it.clave }) { fila ->
                    Column {
                        FilaDeItems(
                            items = fila.items,
                            cardHeight = cardHeight,
                            onReproducir = onReproducir,
                            onEnfocar = { enfocado = it; seccionEnfocada = fila.seccion },
                            onAviso = { aviso = it },
                        )
                        Spacer(Modifier.height(gapEntreFilas))
                    }
                }
            }
        }
        }
    }
}

/**
 * El nombre de la sección, sobre su fila.
 *
 * Propio y no [TvRowLabel] (el del inicio) a propósito: acá el nombre de la sección es lo que
 * ORIENTA —son 30 y pico de secciones por raíz, con nombres largos del portal—, mientras que en el
 * inicio las filas son cuatro o cinco fijas y conocidas. Va más grande y con más aire contra la
 * fila; cambiar el compartido para lograrlo habría movido también el inicio, que no lo pidió.
 */
/** Fondo inmersivo del ítem enfocado, con los mismos degradados que el inicio. */
@Composable
private fun FondoDelHero(imageUrl: String?) {
    Crossfade(targetState = imageUrl, animationSpec = tween(450), label = "fondoCatalogo") { url ->
        Box(Modifier.fillMaxSize()) {
            if (!url.isNullOrBlank()) {
                AsyncImage(
                    model = url,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxWidth(0.62f).fillMaxHeight().align(Alignment.TopEnd),
                )
            }
            // Negro a la izquierda para que el texto se lea sobre cualquier póster.
            //
            // Las paradas NO son las del inicio (repartidas parejo). Acá la imagen arranca en el
            // 38 % del ancho, y con el reparto parejo el degradado ya venía al 85 % de opacidad
            // justo ahí: quedaba una costura vertical marcada donde empieza el póster. El negro se
            // mantiene sólido hasta pasado ese borde y recién después abre. En el inicio no se ve
            // porque sus backdrops están escalados y a la deriva, que difumina el canto.
            Box(
                Modifier.fillMaxSize().background(
                    Brush.horizontalGradient(
                        0f to ArkivBlack,
                        0.42f to ArkivBlack,
                        0.78f to ArkivBlack.copy(alpha = 0.15f),
                        1f to Color.Transparent,
                    ),
                ),
            )
            // Y negro abajo, para fundir con las filas.
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(listOf(Color.Transparent, ArkivBlack.copy(alpha = 0.85f), ArkivBlack)),
                ),
            )
        }
    }
}

/**
 * Nombre y metadata del ítem enfocado.
 *
 * Es lo que hace legible una fila de pósters a tres metros: el título de la tarjeta chica se corta
 * a dos líneas, y sin esto no hay forma de saber en qué estás parado sin entrar.
 *
 * La metadata es la que el portal DA hoy en el listado: tipo y duración. No hay sinopsis — su
 * `assetList` no la trae — así que no se inventa un renglón vacío: si no hay nada que decir, no se
 * pinta nada. El año existe del lado del portal pero todavía no viaja en el árbol.
 *
 * Alto fijo aunque no haya nada enfocado: si apareciera y desapareciera, las filas de abajo
 * saltarían cada vez que el foco entra o sale de una tarjeta.
 */
@Composable
private fun TextoDelHero(item: ItemDeCatalogo?, seccion: String, aviso: String?) {
    Column(Modifier.fillMaxWidth(0.55f).height(96.dp).padding(start = 48.dp, bottom = 12.dp)) {
        // El aviso PISA al ítem enfocado mientras dura: es la respuesta a algo que la persona acaba
        // de hacer, así que tiene que estar donde ya está mirando. Ocupa el mismo bloque de alto
        // fijo, así que nada de abajo se mueve cuando aparece o se va.
        if (aviso != null) {
            Text(
                aviso,
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            return@Column
        }
        if (item == null) return@Column
        if (seccion.isNotBlank()) {
            Text(
                seccion,
                style = MaterialTheme.typography.labelLarge,
                color = ArkivRed,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(bottom = 2.dp),
            )
        }
        Text(
            item.titulo,
            style = MaterialTheme.typography.headlineMedium,
            color = Color.White,
            fontWeight = FontWeight.Bold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        val meta = metadataDe(item)
        if (meta.isNotBlank()) {
            Text(
                meta,
                style = MaterialTheme.typography.titleSmall,
                color = ArkivTextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

/**
 * Cómo se reparte una sección en filas: dos mitades contiguas si hay de sobra, una sola si no.
 *
 * El umbral existe para no partir por partir: con pocos títulos, dos filas cortas se leen como dos
 * secciones distintas mal etiquetadas. Está puesto en el doble de lo que entra a lo ancho de un
 * televisor (unas 6-7 tarjetas), así que solo se parte cuando de verdad hay más de lo que se ve.
 */
private fun filasDe(items: List<ItemDeCatalogo>): List<List<ItemDeCatalogo>> =
    if (items.size < 2) listOf(items)
    else items.chunked((items.size + 1) / 2)

/** "Película · 1 h 52 min". Lo que no se sabe no se pinta: nada de "0 min" ni de separadores sueltos. */
private fun metadataDe(item: ItemDeCatalogo): String = buildList {
    add(if (item.esSerie) "Serie" else "Película")
    if (item.duracionS > 0) {
        val h = item.duracionS / 3600
        val m = (item.duracionS % 3600) / 60
        add(if (h > 0) "$h h $m min" else "$m min")
    }
    if (!item.reproducible) add("No disponible")
}.joinToString(" · ")

/**
 * Una sección como fila horizontal, con el mismo pivote que las del inicio: la fila corre por
 * debajo de una tarjeta que se queda quieta, en vez de arrastrarla contra el borde.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FilaDeItems(
    items: List<ItemDeCatalogo>,
    cardHeight: androidx.compose.ui.unit.Dp,
    onReproducir: (ItemDeCatalogo) -> Unit,
    onEnfocar: (ItemDeCatalogo) -> Unit,
    onAviso: (String) -> Unit,
) {
    CompositionLocalProvider(LocalBringIntoViewSpec provides PivotoDeTv) {
        LazyRow(
            // El `vertical` NO es estético: la tarjeta enfocada escala a 1.08 (TvLandscapeCard) y
            // una fila del alto justo la recorta contra sus propios límites — se come el borde
            // blanco del foco, que es la única señal de dónde estás parado. El sobrante que pide la
            // escala es ~4 % de 92 dp por lado; 6 dp lo cubre con margen.
            contentPadding = PaddingValues(horizontal = 48.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            items(items, key = { it.id }) { item ->
                // Lo que no se puede reproducir se sigue mostrando —el catálogo se ve completo— y lo
                // dice en la tarjeta. Las series necesitan que se elija capítulo, que todavía no está
                // acá; sin `ref` no hay nada que resolver (el gateway sirve el catálogo sin refs
                // cuando no tiene llave de firma, a propósito, para no mandar uno roto).
                val marca = when {
                    item.esSerie -> "Serie"
                    !item.reproducible -> "No disponible"
                    else -> null
                }
                TvLandscapeCard(
                    title = item.titulo,
                    imageUrl = item.poster,
                    cardHeight = cardHeight,
                    badge = marca,
                    onFocus = { onEnfocar(item) },
                    // Lo que no se puede reproducir AVISA en vez de quedarse mudo. La marca en la
                    // tarjeta no alcanzaba: en un televisor, un boton que acepta el clic y no hace
                    // nada se lee como que la app se colgo -- y eso fue exactamente lo que paso.
                    onClick = {
                        if (marca == null) onReproducir(item)
                        else if (item.esSerie) onAviso("Las series todavía no se reproducen desde acá. Buscala por nombre.")
                        else onAviso("Este título no está disponible para reproducir.")
                    },
                )
            }
        }
    }
}

@Composable
private fun Mensaje(texto: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(texto, style = MaterialTheme.typography.bodyMedium, color = ArkivTextSecondary)
    }
}

/**
 * Una fila del catálogo ya aplanada: el ítem del `LazyColumn`.
 *
 * Lleva el nombre de su sección encima porque el hero lo necesita y, aplanada, la fila ya no sabe
 * de dónde salió. La [clave] incluye el índice de fila dentro de la sección: dos filas de la misma
 * sección comparten el id del portal y sin eso el `LazyColumn` vería claves repetidas.
 */
private data class FilaDelCatalogo(
    val clave: String,
    val seccion: String,
    val items: List<ItemDeCatalogo>,
)
