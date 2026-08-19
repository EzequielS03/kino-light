package com.arkiv.player.ui.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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

    /** Cada cuánto se pasa al siguiente dato. */
    const val INTERVALO_MS = 10 * 60 * 1000L

    /**
     * El índice del dato que toca, o -1 si no hay ninguno.
     *
     * Al llegar al último SE QUEDA ahí en vez de volver a empezar: repetir haría que el aviso
     * mienta -- anunciaría "hay algo nuevo" para mostrar lo mismo de hace media hora.
     */
    fun indiceEn(transcurridoMs: Long, cantidad: Int): Int {
        if (cantidad <= 0) return -1
        val pasos = (transcurridoMs.coerceAtLeast(0L) / INTERVALO_MS).toInt()
        return pasos.coerceAtMost(cantidad - 1)
    }

    /** Sin datos no se dibuja el botón: es el fallo bueno, nadie ve un error ni una espera. */
    fun hayBoton(textos: List<String>): Boolean = textos.isNotEmpty()

    /**
     * Si hay que pedirle trivia de serie o de película.
     *
     * `tipoDelItem` lo escribe el gateway al canonizar, verificado contra TMDB: cuando está, manda.
     * Cuando no -- un ítem que todavía no se canonizó --, tener número de capítulo es la mejor
     * pista que queda.
     */
    fun tipoDe(tipoDelItem: String?, episodio: Int?): String = when {
        tipoDelItem == "tv" || tipoDelItem == "movie" -> tipoDelItem
        episodio != null -> "tv"
        else -> "movie"
    }
}

/** Cuánto queda en pantalla el aviso de dato nuevo. */
private const val AVISO_VISIBLE_MS = 5000L

/**
 * El estado del dato curioso en el reproductor: qué se anunció ya, si el aviso está puesto y si el
 * diálogo está abierto.
 *
 * Acompaña a [TriviaDelPlayer] —que decide QUÉ dato toca— con lo poco que hay que recordar entre
 * frames. Salió de `PlayerContent` por la misma razón que el resto de su estado: eran tres
 * variables que no lee nadie más de la pantalla.
 */
@Stable
internal class EstadoDeTrivia {
    /** El diálogo con el texto del dato está abierto. */
    var dialogoAbierto by mutableStateOf(false)
        private set

    /**
     * El aviso ("!") es un overlay PROPIO, como el de en vivo: los controles arrancan ocultos y se
     * auto-ocultan, así que un aviso colgado de la barra no lo vería nadie.
     */
    var avisoVisible by mutableStateOf(false)
        private set

    /** Último índice que ya se anunció, para no repetir el aviso del mismo dato. */
    private var indiceAnunciado by mutableIntStateOf(-1)

    /**
     * Anuncia [indice] si es un dato que todavía no se mostró. Idempotente a propósito: lo llama el
     * cuerpo del composable en cada recomposición, porque el índice se deriva de la posición y no
     * de un temporizador — adelantar o retroceder mueve el dato igual que mueve el video.
     */
    fun anunciarSiEsNuevo(indice: Int) {
        if (indice < 0 || indice == indiceAnunciado) return
        indiceAnunciado = indice
        avisoVisible = true
    }

    fun abrirDialogo() {
        dialogoAbierto = true
        avisoVisible = false
    }

    fun cerrarDialogo() {
        dialogoAbierto = false
    }

    internal fun ocultarAviso() {
        avisoVisible = false
    }

    /** Clave del efecto que borra el aviso: cambia con cada dato nuevo y con cada apagado. */
    internal val claveDelAviso: Pair<Int, Boolean> get() = indiceAnunciado to avisoVisible
}

@Composable
internal fun rememberEstadoDeTrivia(): EstadoDeTrivia = remember { EstadoDeTrivia() }

/** El aviso se va solo a los 5 s. Se relanza con cada dato nuevo, igual que el overlay de canal. */
@Composable
internal fun EfectoDelAvisoDeTrivia(estado: EstadoDeTrivia) {
    LaunchedEffect(estado.claveDelAviso) {
        if (!estado.avisoVisible) return@LaunchedEffect
        delay(AVISO_VISIBLE_MS)
        estado.ocultarAviso()
    }
}

/**
 * Aviso de dato curioso nuevo: chiquito, arriba a la derecha. Va suelto en el Box y no colgado del
 * botón porque los controles arrancan ocultos y se auto-ocultan: en la barra no lo vería nadie.
 *
 * [bajarParaNoTapar] lo corre hacia abajo cuando el chip de "reproduciendo desde la NUC" ocupa ese
 * mismo rincón.
 */
@Composable
internal fun BoxScope.AvisoDeTrivia(estado: EstadoDeTrivia, bajarParaNoTapar: Boolean) {
    AnimatedVisibility(
        visible = estado.avisoVisible && !estado.dialogoAbierto,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = Modifier
            .align(Alignment.TopEnd)
            .systemBarsPadding()
            .padding(top = if (bajarParaNoTapar) 108.dp else 64.dp, end = 12.dp),
    ) {
        Box(
            modifier = Modifier.size(22.dp).clip(CircleShape).background(ArkivRed.copy(alpha = 0.85f)),
            contentAlignment = Alignment.Center,
        ) {
            Text("!", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
internal fun DialogoDeTrivia(estado: EstadoDeTrivia, texto: String) {
    if (!estado.dialogoAbierto) return
    AlertDialog(
        onDismissRequest = { estado.cerrarDialogo() },
        title = { Text("Dato curioso") },
        text = { Text(texto) },
        confirmButton = { TextButton(onClick = { estado.cerrarDialogo() }) { Text("Cerrar") } },
    )
}
