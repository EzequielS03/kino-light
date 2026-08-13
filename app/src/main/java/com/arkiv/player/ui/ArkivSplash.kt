package com.arkiv.player.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp
import com.arkiv.player.ui.theme.ArkivBlack
import com.arkiv.player.ui.theme.ArkivRed
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

/**
 * Duración de la intro, en ms.
 *
 * Público porque `MainActivity` calza contra esto cuándo empieza a componer la app (ver
 * `INTRO_HEAD_START_MS`). Cuando eran dos números sueltos se desfasaron: el head start seguía en
 * 600 ms, afinado para la intro vieja de 750 ms, así que el root se componía ENCIMA del tramo más
 * pesado de esta —el haz y el revelado de la palabra— y la animación se atragantaba justo ahí.
 */
const val DURACION_DE_LA_INTRO_MS = 880

private const val TOTAL_MS = DURACION_DE_LA_INTRO_MS

/** Duración del fundido de salida, en ms. */
private const val EXIT_MS = 260

/** Sub-progreso [0..1] de un tramo de la línea de tiempo global. */
private fun seg(t: Float, from: Float, to: Float): Float =
    ((t - from) / (to - from)).coerceIn(0f, 1f)

private fun easeOut(t: Float): Float = 1f - (1f - t) * (1f - t) * (1f - t)

/** Ease con un pelo de rebote al final, para que el asta "aterrice" en vez de frenar en seco. */
private fun easeBack(t: Float): Float {
    val c = 1.7f
    val u = t - 1f
    return 1f + (c + 1f) * u * u * u + c * u * u
}

/**
 * El monograma de Kino, en una caja de 100x100 con la Y hacia abajo.
 *
 * Son los MISMOS números que `docs/marca/kino_logo.py`, que genera el ícono del lanzador y los PNG
 * del TV. Si se tocan acá y no allá (o al revés), la K de la intro deja de ser la del ícono.
 */
private object GeometriaK {
    private const val ASTA_X0 = 12f
    private const val ASTA_X1 = 29f
    private const val ARRIBA = 8f
    private const val ABAJO = 92f
    private const val DERECHA = 86f
    private const val GROSOR = 17.5f

    /** Donde nacen las aspas: metido dentro del asta, para que suelden sin costura. */
    const val JUNTA_X = ASTA_X1 - 6f
    const val JUNTA_Y = 50f

    val asta: List<Offset> = listOf(
        Offset(ASTA_X0, ARRIBA), Offset(ASTA_X1, ARRIBA),
        Offset(ASTA_X1, ABAJO), Offset(ASTA_X0, ABAJO),
    )
    val aspaArriba: List<Offset> = aspa(ARRIBA - 2f)
    val aspaAbajo: List<Offset> = aspa(ABAJO + 2f)

    /** Un aspa desde la junta hasta el borde derecho, cortada recta. */
    private fun aspa(hastaY: Float): List<Offset> {
        val bx = DERECHA + 14f                     // se pasa de largo y después se corta
        val dx = bx - JUNTA_X
        val dy = hastaY - JUNTA_Y
        val n = hypot(dx, dy)
        val nx = -dy / n * (GROSOR / 2f)
        val ny = dx / n * (GROSOR / 2f)
        return cortarEn(
            listOf(
                Offset(JUNTA_X + nx, JUNTA_Y + ny), Offset(bx + nx, hastaY + ny),
                Offset(bx - nx, hastaY - ny), Offset(JUNTA_X - nx, JUNTA_Y - ny),
            ),
            DERECHA,
        )
    }

    /** Sutherland-Hodgman contra un solo plano vertical: deja el corte recto. */
    private fun cortarEn(poly: List<Offset>, xMax: Float): List<Offset> {
        val out = mutableListOf<Offset>()
        for (i in poly.indices) {
            val c = poly[i]
            val p = poly[(i - 1 + poly.size) % poly.size]
            val cDentro = c.x <= xMax
            val pDentro = p.x <= xMax
            if (cDentro != pDentro) {
                val t = (xMax - p.x) / (c.x - p.x)
                out += Offset(xMax, p.y + t * (c.y - p.y))
            }
            if (cDentro) out += c
        }
        return out
    }
}

/**
 * Arma el `Path` de una pieza del monograma, ya escalado y puesto en su sitio.
 *
 * Se construye UNA vez por tamaño de pantalla, no por cuadro: en la primera versión cada cuadro
 * alocaba tres `Path` nuevos, y eso sumado a rehacer los degradados era lo que dejaba la intro en
 * ~10 fps (medido en emulador, 2026-08-13). Lo que se anima ahora son transformaciones sobre estos
 * mismos paths, que no alocan nada.
 */
private fun pathDe(puntos: List<Offset>, origen: Offset, escala: Float): Path {
    val path = Path()
    puntos.forEachIndexed { i, p ->
        val x = origen.x + p.x * escala
        val y = origen.y + p.y * escala
        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    path.close()
    return path
}

/**
 * Intro de arranque: la K se abre y proyecta el nombre.
 *
 * El asta cae, las dos aspas salen disparadas desde la junta —como un proyector que se abre—, de
 * ahí sale un haz de luz hacia la derecha y KINO se revela DENTRO del haz, de izquierda a derecha.
 * Termina con un zoom + fundido que descubre la app.
 *
 * ### Por qué el asta NO aparece desde invisible
 *
 * `installSplashScreen()` no retiene el splash del sistema: este suelta la K estática en cuanto hay
 * primer frame y Compose toma el control enseguida. Si la intro empezara desde negro, en el aparato
 * se vería "K brillante → negro → K armándose", que es justo el salto que documentaba la intro
 * anterior. Por eso el asta arranca a opacidad plena y lo único que se anima es su caída: en el
 * frame cero ya hay rojo en pantalla y el relevo no se nota. No "arreglar" esto poniéndole un
 * fundido de entrada.
 *
 * Se dibuja ENCIMA del contenido para tapar el arranque en frío. La salida no arranca hasta que
 * [canExit] es true: así el fundido destapa una pantalla ya dibujada en vez de dejar otro hueco
 * negro. [onFinished] avisa al llamador para que la saque de la composición.
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
    val medidor = rememberTextMeasurer()

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

    val estilo = TextStyle(
        color = Color.White,
        fontWeight = FontWeight.Black,
        fontSize = if (isTv) 84.sp else 52.sp,
        letterSpacing = if (isTv) 10.sp else 6.sp,
    )
    val salida = exitAnim.value

    // `drawWithCache` y no `Canvas`: el bloque de arriba corre UNA vez por tamaño de pantalla
    // (medir el texto, resolver el lockup, armar los paths y los dos degradados) y `onDrawBehind`
    // corre por cuadro sin alocar nada. La primera versión rehacía todo eso 60 veces por segundo y
    // la intro dibujaba a ~10 fps; los degradados a pantalla completa son lo más caro, y en el Fire
    // Stick —GPU floja, 1,7 GB— es exactamente lo que no hay que hacer por cuadro.
    Spacer(
        Modifier
            .fillMaxSize()
            .graphicsLayer {
                val s = 1f + 0.07f * exitAnim.value
                scaleX = s
                scaleY = s
                alpha = 1f - exitAnim.value
            }
            .drawWithCache {
                // El tamaño del lockup se deriva del TEXTO, nunca del alto de la pantalla.
                //
                // Atarlo a `size.height` es lo que rompió la primera versión: el teléfono es
                // VERTICAL, así que un monograma de 0,42 × alto salía gigante y empujaba la palabra
                // fuera del borde derecho. Con el texto de referencia, la misma cuenta sirve para el
                // celular y para el TV apaisado.
                var medida = medidor.measure(AnnotatedString("KINO"), estilo)
                var lado = medida.size.height * 1.30f
                var aire = medida.size.height * 0.42f
                var ancho = lado + aire + medida.size.width

                // Y si aun así no entra a lo ancho, se achica el conjunto entero midiendo de nuevo:
                // el texto es sp, no se puede escalar sin volver a medirlo.
                val disponible = size.width * 0.86f
                if (ancho > disponible) {
                    val f = disponible / ancho
                    medida = medidor.measure(
                        AnnotatedString("KINO"),
                        estilo.copy(fontSize = estilo.fontSize * f, letterSpacing = estilo.letterSpacing * f),
                    )
                    lado = medida.size.height * 1.30f
                    aire = medida.size.height * 0.42f
                    ancho = lado + aire + medida.size.width
                }

                val anchoTexto = medida.size.width.toFloat()
                val altoTexto = medida.size.height.toFloat()
                val escala = lado / 100f
                val origen = Offset((size.width - ancho) / 2f, (size.height - lado) / 2f)
                val junta = Offset(
                    origen.x + GeometriaK.JUNTA_X * escala,
                    origen.y + GeometriaK.JUNTA_Y * escala,
                )
                val textoX = origen.x + lado + aire
                val textoY = size.height / 2f - altoTexto / 2f
                val grosorRaya = (size.height * 0.022f).coerceAtLeast(2f)
                val yRaya = textoY + altoTexto + size.height * 0.035f

                // Los tres paths del monograma, ya en su sitio. Lo que se anima son transformaciones
                // sobre estos mismos objetos.
                val astaPath = pathDe(GeometriaK.asta, origen, escala)
                val aspaArribaPath = pathDe(GeometriaK.aspaArriba, origen, escala)
                val aspaAbajoPath = pathDe(GeometriaK.aspaAbajo, origen, escala)

                // El haz, armado a su tamaño FINAL. Al dibujarlo se escala desde la junta, y como
                // largo y apertura crecen juntos, un escalado uniforme es exactamente el cono
                // abriéndose: no hace falta rehacer el path.
                val largoHaz = size.width - junta.x
                val abreHaz = size.height * 0.30f
                val hazPath = Path().apply {
                    moveTo(junta.x, junta.y)
                    lineTo(junta.x + largoHaz, junta.y - abreHaz)
                    lineTo(junta.x + largoHaz, junta.y + abreHaz)
                    close()
                }
                val hazBrush = Brush.horizontalGradient(
                    colors = listOf(Color(0xFFFFEEEE), Color.Transparent),
                    startX = junta.x,
                    endX = junta.x + largoHaz,
                )
                // El resplandor, a radio fijo: antes crecía con la animación, lo que obligaba a
                // rehacer el shader en cada cuadro. Ahora solo se le mueve la opacidad, que es
                // gratis, y a ojo se ve igual.
                val radioBrillo = size.height * 0.85f
                val brilloBrush = Brush.radialGradient(
                    colors = listOf(ArkivRed.copy(alpha = 0.55f), Color.Transparent),
                    center = junta,
                    radius = radioBrillo,
                )

                onDrawBehind {
                    val t = intro.value * TOTAL_MS
                    val pAsta = easeBack(seg(t, 0f, 260f))
                    val pArriba = easeOut(seg(t, 160f, 400f))
                    val pAbajo = easeOut(seg(t, 220f, 460f))
                    val pHaz = seg(t, 380f, 700f)
                    val pPalabra = easeOut(seg(t, 460f, 800f))
                    val pBrillo = seg(t, 700f, 880f)

                    drawRect(ArkivBlack)

                    drawCircle(
                        brush = brilloBrush,
                        radius = radioBrillo,
                        center = junta,
                        alpha = 0.30f + 0.35f * sin(Math.PI.toFloat() * pBrillo),
                    )

                    if (pHaz > 0f) {
                        val abriendo = easeOut(min(pHaz / 0.55f, 1f))
                        val fade = if (pHaz < 0.55f) pHaz / 0.55f else 1f - (pHaz - 0.55f) / 0.45f * 0.72f
                        scale(abriendo, abriendo, pivot = junta) {
                            drawPath(hazPath, hazBrush, alpha = 0.30f * fade)
                        }
                    }

                    // El asta cae desde arriba, YA VISIBLE (ver el doc de arriba).
                    translate(top = -lado * 0.5f * (1f - pAsta)) {
                        drawPath(astaPath, ArkivRed)
                    }
                    // Las aspas salen disparadas desde la junta, una detrás de la otra.
                    if (pArriba > 0f) scale(pArriba, pArriba, pivot = junta) { drawPath(aspaArribaPath, ArkivRed) }
                    if (pAbajo > 0f) scale(pAbajo, pAbajo, pivot = junta) { drawPath(aspaAbajoPath, ArkivRed) }

                    // La palabra se revela DENTRO del haz, de izquierda a derecha.
                    if (pPalabra > 0f) {
                        clipRect(left = textoX, right = textoX + anchoTexto * pPalabra) {
                            drawText(medida, topLeft = Offset(textoX, textoY))
                        }
                        drawRect(
                            color = ArkivRed,
                            topLeft = Offset(textoX, yRaya),
                            size = Size(anchoTexto * pPalabra, grosorRaya),
                        )
                    }
                }
            },
    )
}
