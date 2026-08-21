package com.arkiv.player.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.arkiv.player.data.local.AccionDeDescarga
import com.arkiv.player.data.local.ConfirmacionDeDescarga
import com.arkiv.player.data.local.EstadoDeDescarga
import com.arkiv.player.data.local.EtiquetaDeDescarga
import com.arkiv.player.ui.theme.ArkivRed
import com.arkiv.player.ui.theme.ArkivTextPrimary
import com.arkiv.player.ui.theme.ArkivTextSecondary
import com.arkiv.player.ui.theme.NucDownloadedGreen

/**
 * Estado de la descarga de una fila + qué hacer con él, para pasárselo a una fila de una sola vez.
 *
 * Existe porque en el buscador de fuentes cada fila necesita las mismas cuatro cosas y armarlas en
 * cada callsite (tres tipos de fuente × dos pantallas) era pura repetición.
 */
@Immutable
data class DescargaDeFila(
    val estado: EstadoDeDescarga,
    val onDownload: () -> Unit,
    val onRetry: () -> Unit,
    val onPedirAccion: (AccionDeDescarga) -> Unit,
)

/** Ver [ControlDeDescarga]. */
@Composable
fun ControlDeDescarga(descarga: DescargaDeFila, enabled: Boolean = true) = ControlDeDescarga(
    estado = descarga.estado,
    onDownload = descarga.onDownload,
    onRetry = descarga.onRetry,
    onPedirAccion = descarga.onPedirAccion,
    enabled = enabled,
)

/**
 * El control de descarga de una fila, sea de la biblioteca o del buscador de fuentes: un solo slot
 * de 48dp que dice en qué va la descarga Y deja hacer lo que corresponda a ese estado.
 *
 * Vive acá y no en cada pantalla porque las dos tienen que comportarse igual: que el buscador
 * ofreciera solo "bajar" —sin cola, sin progreso, sin cancelar— era la mitad de la función en la
 * mitad de la app.
 */
@Composable
fun ControlDeDescarga(
    estado: EstadoDeDescarga,
    onDownload: () -> Unit,
    onRetry: () -> Unit,
    /** Cancelar / sacar de la cola / borrar. Quien reciba esto tiene que CONFIRMARLO antes de hacerlo. */
    onPedirAccion: (AccionDeDescarga) -> Unit,
    enabled: Boolean = true,
) {
    when (estado) {
        // Papelera VERDE, no un tilde: el verde sigue diciendo "ya lo tienes" y el ícono dice qué se
        // puede hacer con eso. Un tilde ocupaba el slot sin ofrecer nada.
        EstadoDeDescarga.Lista -> IconButton(
            onClick = { onPedirAccion(AccionDeDescarga.BORRAR) },
            enabled = enabled,
        ) {
            Icon(
                Icons.Default.Delete,
                contentDescription = "Descargado. Tocar para borrarlo del dispositivo",
                tint = NucDownloadedGreen,
            )
        }
        // Anillo de progreso con una X ADENTRO: la X es el control, el anillo es el estado. Con solo
        // el porcentaje, nada en pantalla decía que ese número fuera un botón.
        is EstadoDeDescarga.Bajando -> IconButton(
            onClick = { onPedirAccion(AccionDeDescarga.CANCELAR) },
            enabled = enabled,
        ) {
            AnilloConEquis(estado.fraccion, ArkivRed, "Bajando. Tocar para cancelar la descarga")
        }
        EstadoDeDescarga.EnCola -> IconButton(
            onClick = { onPedirAccion(AccionDeDescarga.SACAR_DE_LA_COLA) },
            enabled = enabled,
        ) {
            AnilloConEquis(null, ArkivTextSecondary, "En cola. Tocar para sacarla de la cola")
        }
        // Un fallo tiene que verse Y poder deshacerse acá mismo. Antes volvía a mostrar el botón de
        // bajar, idéntico a no haberlo intentado nunca: se tocaba de nuevo, fallaba por lo mismo, y
        // nada en pantalla lo decía.
        is EstadoDeDescarga.Fallida -> IconButton(onClick = onRetry, enabled = enabled) {
            Icon(
                Icons.Default.Refresh,
                contentDescription = estado.motivo?.let { "Falló: $it. Tocar para reintentar" }
                    ?: "Falló la descarga. Tocar para reintentar",
                tint = ArkivRed,
            )
        }
        // No es un fallo ni está bajando: espera que el usuario acepte el tamaño en Descargas, que es
        // donde vive esa confirmación.
        EstadoDeDescarga.PideConfirmacion -> Box(
            modifier = Modifier.size(48.dp),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Default.Warning,
                contentDescription = "Pesa mucho: confírmala en Descargas",
                tint = ArkivRed,
                modifier = Modifier.size(18.dp),
            )
        }
        EstadoDeDescarga.SinDescargar -> IconButton(onClick = onDownload, enabled = enabled) {
            Icon(
                Icons.Default.Download,
                contentDescription = "Guardar en el dispositivo",
                tint = ArkivTextSecondary,
            )
        }
    }
}

/**
 * Anillo de progreso con una X encima: estado y control de cancelar en el mismo slot.
 * [fraccion] null = indeterminado (en cola, o bajando sin tamaño total conocido).
 */
@Composable
private fun AnilloConEquis(fraccion: Float?, color: Color, descripcion: String) {
    Box(contentAlignment = Alignment.Center) {
        if (fraccion == null) {
            CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 2.dp, color = color)
        } else {
            CircularProgressIndicator(
                progress = { fraccion },
                modifier = Modifier.size(28.dp),
                strokeWidth = 2.dp,
                color = color,
                trackColor = Color(0x33FFFFFF),
            )
        }
        Icon(
            Icons.Default.Close,
            contentDescription = descripcion,
            tint = color,
            modifier = Modifier.size(14.dp),
        )
    }
}

/**
 * Barra de descarga a lo ancho, para colgar del borde inferior de una fila.
 *
 * Indeterminada mientras no se pueda decir cuánto falta (en cola, o bajando sin tamaño total
 * conocido) y determinada cuando sí: una barra clavada en 0% durante minutos parece trabada. Gris
 * para lo que espera turno, rojo para lo que baja ahora, verde lleno para lo que ya está en el
 * aparato y rojo lleno para lo que falló — los mismos colores que la pantalla de Descargas.
 */
@Composable
fun BarraDeDescarga(estado: EstadoDeDescarga) {
    val forma = Modifier.fillMaxWidth().height(3.dp)
    val fondo = Color(0x33FFFFFF)
    when (estado) {
        EstadoDeDescarga.SinDescargar -> Unit
        EstadoDeDescarga.EnCola ->
            LinearProgressIndicator(color = ArkivTextSecondary, trackColor = fondo, modifier = forma)
        is EstadoDeDescarga.Bajando -> {
            val fraccion = estado.fraccion
            if (fraccion == null) {
                LinearProgressIndicator(color = ArkivRed, trackColor = fondo, modifier = forma)
            } else {
                LinearProgressIndicator(
                    progress = { fraccion },
                    color = ArkivRed,
                    trackColor = fondo,
                    modifier = forma,
                )
            }
        }
        EstadoDeDescarga.Lista ->
            LinearProgressIndicator(progress = { 1f }, color = NucDownloadedGreen, trackColor = fondo, modifier = forma)
        is EstadoDeDescarga.Fallida ->
            LinearProgressIndicator(progress = { 1f }, color = ArkivRed, trackColor = fondo, modifier = forma)
        EstadoDeDescarga.PideConfirmacion ->
            LinearProgressIndicator(progress = { 1f }, color = ArkivRed.copy(alpha = 0.45f), trackColor = fondo, modifier = forma)
    }
}

/**
 * En qué va la descarga, EN PALABRAS ("Bajando 42%", "En cola", "Descargado", o el motivo real del
 * fallo). No muestra nada si no hay descarga.
 *
 * El anillo y la barra dicen lo mismo en colores y formas, pero eso solo se entiende sabiendo de
 * antemano qué significan; esto se lee sin traducir nada.
 */
@Composable
fun LineaDeEstadoDeDescarga(
    estado: EstadoDeDescarga,
    style: TextStyle = MaterialTheme.typography.labelSmall,
    modifier: Modifier = Modifier,
) {
    val etiqueta = EtiquetaDeDescarga.para(estado) ?: return
    Text(
        etiqueta,
        style = style,
        color = when (estado) {
            is EstadoDeDescarga.Fallida, EstadoDeDescarga.PideConfirmacion -> ArkivRed
            EstadoDeDescarga.Lista -> NucDownloadedGreen
            else -> ArkivTextPrimary
        },
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

/** El diálogo que confirma una [AccionDeDescarga]. `accion` null = no hay nada que confirmar. */
@Composable
fun DialogoDeDescarga(
    accion: AccionDeDescarga?,
    nombreDelCapitulo: String?,
    onConfirmar: () -> Unit,
    onCerrar: () -> Unit,
) {
    if (accion == null) return
    val texto = ConfirmacionDeDescarga.texto(accion, nombreDelCapitulo)
    AlertDialog(
        onDismissRequest = onCerrar,
        title = { Text(texto.titulo) },
        text = { Text(texto.cuerpo) },
        confirmButton = { TextButton(onClick = onConfirmar) { Text(texto.confirmar) } },
        dismissButton = { TextButton(onClick = onCerrar) { Text(texto.descartar) } },
    )
}
