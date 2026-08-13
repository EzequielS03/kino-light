package com.arkiv.player.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
import com.arkiv.player.ui.theme.ArkivBlack
import com.arkiv.player.ui.theme.ArkivRed

/** Duración de la intro (asentado + barrido), en ms. */
private const val TOTAL_MS = 750

/** Duración del fundido de salida, en ms. */
private const val EXIT_MS = 320

/** Sub-progreso [0..1] de un tramo de la línea de tiempo global. */
private fun seg(p: Float, from: Float, to: Float): Float =
    ((p - from) / (to - from)).coerceIn(0f, 1f)

/**
 * Intro de arranque estilo Netflix: el wordmark KINO entra con un resplandor rojo y se asienta,
 * un destello de luz lo barre, y termina con un zoom + fundido que descubre la app.
 *
 * Se dibuja ENCIMA del contenido para tapar el arranque en frío, que antes se veía como una
 * pantalla negra muerta. La salida no arranca hasta que [canExit] es true: así el fundido
 * destapa una pantalla ya dibujada en vez de dejar otro hueco negro. [onFinished] avisa al
 * llamador para que la saque de la composición.
 */
@Composable
fun ArkivSplash(
    isTv: Boolean,
    canExit: Boolean,
    onFinished: () -> Unit,
) {
    val intro = remember { Animatable(0f) }
    val exitAnim = remember { Animatable(0f) }
    var introDone by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        intro.animateTo(1f, tween(durationMillis = TOTAL_MS, easing = LinearEasing))
        introDone = true
    }
    // La salida espera a que el contenido esté listo: si el splash se fuera apenas termina la
    // animación, quedaría un frame negro mientras el root todavía no dibujó nada.
    LaunchedEffect(introDone, canExit) {
        if (!introDone || !canExit) return@LaunchedEffect
        exitAnim.animateTo(1f, tween(durationMillis = EXIT_MS, easing = FastOutLinearInEasing))
        onFinished()
    }

    val p = intro.value
    // Tramos dentro de la intro: asentado (0–520ms) y barrido de luz (380–1000ms).
    // OJO: el tramo inicial NO arranca desde invisible — si lo hiciera, el primer frame de
    // Compose sería negro y se vería un salto feo justo al soltar el splash del sistema.
    val settle = FastOutSlowInEasing.transform(seg(p, 0f, 0.52f))
    val sweep = seg(p, 0.38f, 1f)
    val exit = exitAnim.value

    Box(
        modifier = Modifier.fillMaxSize().background(ArkivBlack),
        contentAlignment = Alignment.Center,
    ) {
        // Resplandor rojo detrás del wordmark: crece con el asentado y se apaga en la salida.
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = (0.35f + 0.65f * settle) * (1f - exit) * 0.45f }
                .background(
                    Brush.radialGradient(
                        listOf(ArkivRed.copy(alpha = 0.55f), Color.Transparent),
                        radius = if (isTv) 620f else 420f,
                    ),
                ),
        )

        Text(
            text = "KINO",
            color = ArkivRed,
            fontWeight = FontWeight.Black,
            fontSize = if (isTv) 92.sp else 56.sp,
            letterSpacing = if (isTv) 14.sp else 8.sp,
            modifier = Modifier
                .graphicsLayer {
                    // Entra ya visible (0.92) y se asienta a 1.0; al salir, zoom sutil + fundido
                    // (como el cierre del intro de Netflix).
                    val s = (0.92f + 0.08f * settle) + 0.15f * exit
                    scaleX = s
                    scaleY = s
                    alpha = (0.35f + 0.65f * settle) * (1f - exit)
                    // Necesario para que el BlendMode del destello recorte contra las letras
                    // y no contra todo el fondo de la pantalla.
                    compositingStrategy = CompositingStrategy.Offscreen
                }
                .drawWithContent {
                    drawContent()
                    // Destello que barre las letras. SrcATop (y no SrcIn) es clave: recorta la
                    // luz contra las letras pero DEJA el texto intacto donde el degradado es
                    // transparente — con SrcIn el resto del wordmark se borraba.
                    if (sweep > 0f && sweep < 1f) {
                        val cx = size.width * (sweep * 1.6f - 0.3f)
                        val half = size.width * 0.18f
                        drawRect(
                            brush = Brush.linearGradient(
                                0f to Color.Transparent,
                                0.5f to Color.White,
                                1f to Color.Transparent,
                                start = Offset(cx - half, 0f),
                                end = Offset(cx + half, 0f),
                            ),
                            blendMode = BlendMode.SrcAtop,
                        )
                    }
                },
        )
    }
}
