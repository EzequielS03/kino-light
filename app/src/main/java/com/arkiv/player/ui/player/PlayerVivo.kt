package com.arkiv.player.ui.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.arkiv.player.data.gateway.LiveApi
import com.arkiv.player.data.gateway.LiveChannel
import com.arkiv.player.data.gateway.LiveProgram
import com.arkiv.player.ui.live.AccionDelDrawer
import com.arkiv.player.ui.live.DpadDelDrawer
import com.arkiv.player.ui.live.FocoDelDrawer
import com.arkiv.player.ui.live.enCurso
import com.arkiv.player.ui.theme.ArkivRed
import com.arkiv.player.ui.theme.ArkivSurface
import com.arkiv.player.ui.theme.ArkivTextSecondary
import com.arkiv.player.ui.tv.TvCajonDeCanales
import kotlinx.coroutines.delay

/** Cuánto queda en pantalla la ficha del canal tras abrir, zapear o tocar. */
private const val FICHA_VISIBLE_MS = 3000L

/**
 * El modo vivo del reproductor: su overlay propio y el cajón de canales del TV.
 *
 * Vivo NO reusa `controlsVisible`/`interactionTick` de VOD — esos gobiernan la barra de progreso y
 * la fila de transporte, que en un directo no existen. Tiene su propio par (visible + tick), y por
 * eso todo el bloque se puede separar: nada de VOD lo lee.
 *
 * Lo que NO se movió acá es el listener de teclas del video, que es de la pantalla: mezcla zapping
 * con las teclas de VOD y con el foco del overlay, así que sacarlo pedía partir también ese nudo.
 * Desde ahí se llaman [EstadoDeVivo.abrirCajon], [EstadoDeVivo.mostrarInfo] y [EstadoDeVivo.alternarInfo].
 */
@Stable
internal class EstadoDeVivo {
    /**
     * La ficha del canal está en pantalla. Arranca visible: el primer canal se anuncia solo, sin
     * que el usuario tenga que tocar nada.
     */
    var infoVisible by mutableStateOf(true)
        private set

    /** Sube con cada anuncio; relanza la cuenta de los 3 s, así que zapear seguido la sostiene. */
    var infoTick by mutableIntStateOf(0)
        private set

    /** Programa en curso del canal actual, o null mientras no haya EPG. */
    var ahora by mutableStateOf<LiveProgram?>(null)
        private set

    /** El que sigue, con el mismo criterio. */
    var despues by mutableStateOf<LiveProgram?>(null)
        private set

    /** El cajón de canales está abierto (solo TV). */
    var cajonAbierto by mutableStateOf(false)
        private set

    /** Qué columna del cajón tiene el foco. Las reglas de las flechas viven en [DpadDelDrawer]. */
    var focoCajon by mutableStateOf(FocoDelDrawer.CANALES)
        private set

    /** Equivalente de `bump()` de VOD: anuncia el canal y reinicia la cuenta de los 3 s. */
    fun mostrarInfo() {
        infoVisible = true
        infoTick++
    }

    fun ocultarInfo() {
        infoVisible = false
    }

    /** Un toque sobre el video: si la ficha está puesta la saca, y si no, la trae. */
    fun alternarInfo() {
        if (infoVisible) ocultarInfo() else mostrarInfo()
    }

    fun abrirCajon() {
        focoCajon = FocoDelDrawer.CANALES
        cajonAbierto = true
        // La ficha taparía el pie del cajón, y además el cajón ya dice en qué canal estás.
        infoVisible = false
    }

    fun cerrarCajon() {
        cajonAbierto = false
    }

    fun moverFocoDelCajon(foco: FocoDelDrawer) {
        focoCajon = foco
    }

    /**
     * "Ahora"/"A continuación" del canal: pedido best-effort directo al gateway. Es puramente
     * informativo para este overlay, no algo que el ViewModel necesite para poder reproducir, así
     * que no se lo carga con otra dependencia (LiveApi) por esto solo.
     */
    suspend fun cargarEpg(canal: LiveChannel?, liveApi: LiveApi) {
        if (canal == null) return
        ahora = null
        despues = null
        val epg = runCatching { liveApi.epg(listOf(canal.code)) }.getOrNull() ?: return
        val progs = epg.first[canal.code] ?: return
        val instante = System.currentTimeMillis() / 1000
        val actual = enCurso(progs, instante)
        ahora = actual
        despues = progs.firstOrNull { it.inicio >= (actual?.fin ?: instante) }
    }
}

@Composable
internal fun rememberEstadoDeVivo(): EstadoDeVivo = remember { EstadoDeVivo() }

/**
 * Franja superior del modo vivo: el distintivo "EN VIVO" y, en el teléfono, el botón de salir.
 *
 * Es PERSISTENTE, no se desvanece con la ficha del canal — es la identidad de la pantalla, no
 * información transitoria. Vive afuera de la ficha a propósito, igual que el cartel de
 * Chromecast/NUC de VOD. Incluye el botón atrás porque con el bloque de controles de VOD oculto
 * (`visible = !enVivo`) esta es la ÚNICA forma en pantalla de salir del reproductor en el teléfono.
 *
 * [botonesDeCast] es un hueco: la pantalla mete ahí los mismos DLNA/Chromecast que usa VOD, que
 * dependen de estado que no es de vivo.
 */
@Composable
internal fun BoxScope.FranjaEnVivo(
    isTv: Boolean,
    onBack: () -> Unit,
    botonesDeCast: @Composable () -> Unit,
) {
    Row(
        modifier = Modifier.align(Alignment.TopStart).fillMaxWidth().systemBarsPadding().padding(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (!isTv) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Atrás", tint = Color.White)
            }
        }
        Box(
            modifier = Modifier
                .padding(start = if (isTv) 6.dp else 2.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(ArkivRed)
                .padding(horizontal = 10.dp, vertical = 4.dp),
        ) {
            Text(
                "EN VIVO",
                color = Color.White,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
            )
        }
        Spacer(Modifier.weight(1f))
        if (!isTv) botonesDeCast()
    }
}

/**
 * Ficha del canal (número o logo, nombre, Ahora/A continuación). TRANSITORIA: se muestra 3 s tras
 * abrir, zapear o tocar, y se va sola.
 *
 * Se trae adentro sus dos efectos —cargar el EPG del canal y la cuenta de los 3 s— porque solo se
 * compone en modo vivo y no le sirven a nadie más.
 */
@Composable
internal fun BoxScope.FichaDelCanal(
    estado: EstadoDeVivo,
    canal: LiveChannel?,
    liveApi: LiveApi,
) {
    LaunchedEffect(canal?.code) { estado.cargarEpg(canal, liveApi) }
    LaunchedEffect(estado.infoTick) {
        delay(FICHA_VISIBLE_MS)
        estado.ocultarInfo()
    }

    AnimatedVisibility(
        visible = estado.infoVisible,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Brush.verticalGradient(0f to Color(0x00000000), 1f to Color(0xD9000000)))
                .systemBarsPadding()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier.size(52.dp).clip(RoundedCornerShape(8.dp)).background(ArkivSurface),
                contentAlignment = Alignment.Center,
            ) {
                val logo = canal?.logo
                if (logo != null) {
                    AsyncImage(
                        model = logo,
                        contentDescription = canal.nombre,
                        modifier = Modifier.fillMaxSize().padding(6.dp),
                    )
                } else {
                    Text(
                        (canal?.numero ?: 0).toString(),
                        color = Color.White.copy(alpha = 0.7f),
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            }
            Column(modifier = Modifier.padding(start = 12.dp).weight(1f)) {
                Text(
                    canal?.nombre.orEmpty(),
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                // Nada si todavía no hay EPG para este canal -- mismo criterio que la grilla/guía
                // (LiveScreen/TvLiveGuideScreen): sin hueco fijo ni "cargando".
                estado.ahora?.let { p -> LineaDePrograma("Ahora: ${p.titulo}", Color.White.copy(alpha = 0.85f)) }
                estado.despues?.let { p -> LineaDePrograma("A continuación: ${p.titulo}", ArkivTextSecondary) }
            }
        }
    }
}

@Composable
private fun LineaDePrograma(texto: String, color: Color) {
    Text(
        texto,
        color = color,
        style = MaterialTheme.typography.bodySmall,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/**
 * Cajón de canales del vivo (solo TV). Va ÚLTIMO dentro del Box de la pantalla para quedar por
 * encima del resto de overlays -- y a la izquierda, dejando el video visible a su derecha: es un
 * cajón, no otra pantalla.
 */
@Composable
internal fun BoxScope.CajonDeCanalesDelVivo(
    estado: EstadoDeVivo,
    canalActual: String?,
    onElegirCanal: (List<LiveChannel>, LiveChannel) -> Unit,
) {
    Box(
        Modifier
            .align(Alignment.CenterStart)
            .fillMaxHeight()
            // PREVIEW y no onKeyEvent: el preview baja desde el contenedor ANTES de que la fila con
            // el foco se quede la tecla, que es la única forma de que "derecha" cierre el cajón en
            // vez de que la lista se la coma.
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (DpadDelDrawer.accion(e.key.nativeKeyCode, abierto = true, foco = estado.focoCajon)) {
                    AccionDelDrawer.CERRAR -> { estado.cerrarCajon(); true }
                    AccionDelDrawer.A_CANALES -> { estado.moverFocoDelCajon(FocoDelDrawer.CANALES); true }
                    AccionDelDrawer.A_CATEGORIAS -> { estado.moverFocoDelCajon(FocoDelDrawer.CATEGORIAS); true }
                    // De la lista: que la resuelva el foco de Compose. `false` la deja seguir;
                    // consumirla acá dejaría la lista inmóvil.
                    else -> false
                }
            },
    ) {
        TvCajonDeCanales(
            foco = estado.focoCajon,
            onFoco = { estado.moverFocoDelCajon(it) },
            canalActual = canalActual,
            onElegirCanal = { lista, canal ->
                onElegirCanal(lista, canal)
                estado.cerrarCajon()
                estado.mostrarInfo()
            },
        )
    }
}
