package com.arkiv.player.ui.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arkiv.player.ui.theme.ArkivRed
import kotlinx.coroutines.delay

/**
 * Qué dato curioso toca mostrar según cuánto lleva la reproducción.
 *
 * Los datos se piden TODOS DE UNA al arrancar (ver `/v1/trivia` en el gateway) y acá solo se
 * rota entre ellos. Por eso esto es aritmética pura y no una petición: cambiar de dato no puede
 * costar los 3 a 20 segundos que tarda el modelo, ni fallar a mitad de una película.
 *
 * Vive aparte del Composable a propósito: este proyecto no tiene tests de interfaz, así que una
 * regla escrita adentro del `PlayerScreen` no se podría probar de ninguna forma (mismo criterio
 * que `DpadDelDrawer`).
 */
object TriviaDelPlayer {

    /**
     * El dato que sigue al [actual], o -1 si no hay ninguno.
     *
     * Rota en círculo: después del último vuelve el primero, así pulsar arriba siempre muestra
     * algo. Antes el índice salía de la POSICIÓN del video (uno nuevo cada diez minutos), pero
     * desde que avanza a pulsación las dos cosas no pueden gobernar el mismo número — el tiempo
     * lo movería por debajo mientras el usuario lo mueve a mano.
     *
     * Un [actual] de -1 significa "todavía no se mostró ninguno", y cae en el primero.
     */
    fun siguienteIndice(actual: Int, cantidad: Int): Int {
        if (cantidad <= 0) return -1
        return (actual + 1).mod(cantidad)
    }

    /** Sin datos no se dibuja el botón: es el fallo bueno, nadie ve un error ni una espera. */
    fun hayBoton(textos: List<String>): Boolean = textos.isNotEmpty()

    /**
     * Si hay que pedirle trivia de serie o de película.
     *
     * **Equivocarse acá no da "sin datos", da datos de OTRA OBRA**: un id de TMDB solo significa
     * algo dentro de su catálogo. Medido en producción -- se pidió `movie:82452` para Avatar, y en
     * TMDB `tv:82452` es "Avatar: La leyenda de Aang" mientras que `movie:82452` es "Savage Water",
     * una película de rafting de 1979. Eso fue lo que se le mostró a quien estaba viendo Avatar.
     *
     * Por eso se miran todas las señales, de la más confiable a la más débil:
     *  1. `tipoDelItem`, que el gateway escribió verificando contra TMDB.
     *  2. `categoryOverride`, que es lo que la app ya usa para decidir si algo es serie
     *     (ver `LibraryRow.isMovie`) y puede venir corregido a mano por la persona.
     *  3. Que ESTE capítulo traiga número.
     */
    fun tipoDe(tipoDelItem: String?, categoryOverride: String?, episodio: Int?): String = when {
        tipoDelItem == "tv" || tipoDelItem == "movie" -> tipoDelItem
        categoryOverride == "series" -> "tv"
        categoryOverride == "movie" -> "movie"
        episodio != null -> "tv"
        else -> "movie"
    }
}

/** Cuánto queda el cartel rojo tras llegar los datos. */
private const val CARTEL_VISIBLE_MS = 5000L

/** Cuánto queda el panel abierto sin que lo toquen. */
private const val PANEL_VISIBLE_MS = 10_000L

/**
 * El estado del dato curioso: cuál se está mostrando, si el panel está desplegado y si el cartel
 * de aviso sigue en pantalla.
 *
 * Acompaña a [TriviaDelPlayer] —que decide QUÉ dato sigue— con lo poco que hay que recordar entre
 * frames.
 */
@Stable
internal class EstadoDeTrivia {
    /** El panel con el texto está desplegado. */
    var panelAbierto by mutableStateOf(false)
        private set

    /**
     * Cartel rojo de "Dato curioso" pegado arriba. Es un overlay PROPIO: los controles arrancan
     * ocultos y se auto-ocultan, así que un aviso colgado de la barra no lo vería nadie.
     *
     * Sale UNA vez, cuando llegan los datos, y se va solo. No vuelve: el dato ya no rota con el
     * tiempo, así que no hay nada nuevo que anunciar después.
     */
    var cartelVisible by mutableStateOf(false)
        private set

    /** El dato que se está mostrando, o -1 si todavía no se abrió ninguno. */
    var indiceVisible by mutableIntStateOf(-1)
        private set

    /** Sube con cada apertura o avance, para reiniciar la cuenta del auto-cierre. */
    var tickDelPanel by mutableIntStateOf(0)
        private set

    private var yaSeAnuncio = false

    /** Llegaron los datos: se anuncia una sola vez por carga. [cantidad] 0 no anuncia nada. */
    fun anunciarLlegada(cantidad: Int) {
        if (cantidad <= 0 || yaSeAnuncio) return
        yaSeAnuncio = true
        cartelVisible = true
    }

    /** Otro episodio: vuelve a estar todo por mostrar. */
    fun reiniciar() {
        yaSeAnuncio = false
        cartelVisible = false
        panelAbierto = false
        indiceVisible = -1
        tickDelPanel = 0
    }

    /**
     * Abre el panel, o avanza al siguiente dato si ya estaba abierto. Es lo que hace la flecha
     * arriba, el botón "i" y tocar el cartel: siempre "muéstrame el que sigue".
     */
    fun mostrarSiguiente(cantidad: Int) {
        val siguiente = TriviaDelPlayer.siguienteIndice(indiceVisible, cantidad)
        if (siguiente < 0) return
        indiceVisible = siguiente
        panelAbierto = true
        cartelVisible = false
        tickDelPanel++
    }

    fun cerrarPanel() {
        panelAbierto = false
    }

    internal fun ocultarCartel() {
        cartelVisible = false
    }
}

@Composable
internal fun rememberEstadoDeTrivia(): EstadoDeTrivia = remember { EstadoDeTrivia() }

/**
 * Los dos temporizadores: el cartel se va a los 5 s de aparecer, y el panel a los 10 s de la
 * última pulsación (cada avance reinicia la cuenta, así que leer varios seguidos no lo cierra).
 *
 * [cantidad] dispara el anuncio: cambia de 0 a N cuando el gateway responde.
 */
@Composable
internal fun EfectosDeTrivia(estado: EstadoDeTrivia, cantidad: Int, episodeId: String) {
    LaunchedEffect(episodeId) { estado.reiniciar() }
    LaunchedEffect(cantidad) { estado.anunciarLlegada(cantidad) }
    LaunchedEffect(estado.cartelVisible) {
        if (!estado.cartelVisible) return@LaunchedEffect
        delay(CARTEL_VISIBLE_MS)
        estado.ocultarCartel()
    }
    LaunchedEffect(estado.tickDelPanel, estado.panelAbierto) {
        if (!estado.panelAbierto) return@LaunchedEffect
        delay(PANEL_VISIBLE_MS)
        estado.cerrarPanel()
    }
}

/**
 * Cartel "Dato curioso": centrado y pegado al borde superior, fondo rojo y letra blanca. Anuncia
 * que hay datos para leer y se va solo.
 *
 * En el teléfono es tocable —es el acceso rápido mientras está en pantalla—; en TV no, ahí se abre
 * con la flecha arriba. [onTocar] es null cuando no debe responder al toque.
 */
@Composable
internal fun BoxScope.CartelDeTrivia(estado: EstadoDeTrivia, onTocar: (() -> Unit)? = null) {
    AnimatedVisibility(
        visible = estado.cartelVisible && !estado.panelAbierto,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = Modifier.align(Alignment.TopCenter),
    ) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(bottomStart = 10.dp, bottomEnd = 10.dp))
                .background(ArkivRed)
                .then(if (onTocar != null) Modifier.clickable { onTocar() } else Modifier)
                .padding(horizontal = 18.dp, vertical = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "Dato curioso",
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

/**
 * Panel del dato, desplegado desde el borde superior. Muestra [textos] en la posición que diga
 * [EstadoDeTrivia.indiceVisible], con el contador para saber cuántos quedan.
 *
 * Baja desde arriba en vez de ser un diálogo centrado para no tapar el video: el dato se lee
 * mientras la película sigue.
 */
@Composable
internal fun BoxScope.PanelDeTrivia(estado: EstadoDeTrivia, textos: List<String>) {
    val texto = textos.getOrNull(estado.indiceVisible)
    AnimatedVisibility(
        visible = estado.panelAbierto && texto != null,
        enter = slideInVertically { -it } + fadeIn(),
        exit = slideOutVertically { -it } + fadeOut(),
        modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.92f))
                .systemBarsPadding()
                .padding(horizontal = 32.dp, vertical = 20.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(ArkivRed)
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                ) {
                    Text("Dato curioso", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.weight(1f))
                if (textos.size > 1) {
                    Text(
                        "${estado.indiceVisible + 1} de ${textos.size}",
                        color = Color.White.copy(alpha = 0.55f),
                        fontSize = 13.sp,
                    )
                }
            }
            Text(
                texto.orEmpty(),
                color = Color.White,
                fontSize = 19.sp,
                lineHeight = 26.sp,
                modifier = Modifier.padding(top = 12.dp),
            )
        }
    }
}
