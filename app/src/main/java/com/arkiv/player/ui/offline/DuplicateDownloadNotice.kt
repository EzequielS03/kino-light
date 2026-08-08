package com.arkiv.player.ui.offline

import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import com.arkiv.player.data.local.DuplicateDownloadPolicy
import com.arkiv.player.data.local.EnqueueOutcome

/**
 * Aviso de "eso ya lo tenés bajado" para los botones de guardar en el dispositivo.
 *
 * La cola saltea sola las descargas cuyo contenido ya está en disco bajo otro ítem (ver
 * [com.arkiv.player.data.local.LocalDownloadManager.enqueue]); sin este aviso el botón parecería no
 * hacer nada. Mismo molde que [rememberPostNotificationsRequest]: devuelve una lambda para invocar
 * después de encolar.
 *
 * Se le pasan TODOS los resultados de la acción juntos (un pack manda los N capítulos de una) para
 * que salga un solo Toast y no uno por capítulo.
 */
@Composable
fun rememberDuplicateDownloadNotice(): (List<EnqueueOutcome>) -> Unit {
    val context = LocalContext.current
    return { outcomes ->
        val skipped = outcomes.count { it == EnqueueOutcome.ALREADY_DOWNLOADED }
        DuplicateDownloadPolicy.skippedNotice(skipped)?.let {
            Toast.makeText(context, it, Toast.LENGTH_LONG).show()
        }
    }
}
