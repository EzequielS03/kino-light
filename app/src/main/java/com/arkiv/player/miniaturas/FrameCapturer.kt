package com.arkiv.player.miniaturas

import android.graphics.Bitmap
import android.view.TextureView
import com.arkiv.player.data.db.EpisodeFrameDao
import com.arkiv.player.data.db.EpisodeFrameEntity
import java.io.ByteArrayOutputStream

/**
 * Captura el frame que se está viendo y lo deja guardado.
 *
 * Best-effort de punta a punta: si algo falla —no hay TextureView, el decodificador devuelve
 * negro, el disco está lleno— no pasa nada y se conserva el frame anterior. Esto es una mejora
 * visual, jamás un motivo para molestar al que está viendo algo.
 */
class FrameCapturer(
    private val almacen: AlmacenDeFrames,
    private val dao: EpisodeFrameDao,
    private val ahora: () -> Long = { System.currentTimeMillis() },
) {
    suspend fun capturar(episodeId: String, positionMs: Long, textureView: TextureView?): Boolean {
        if (!GuardasDeFrame.posicionSirve(positionMs)) return false
        val vista = textureView ?: return false
        val bitmap = runCatching { vista.getBitmap(ANCHO, ALTO) }.getOrNull() ?: return false
        // Todo lo que sigue —leer los píxeles, comprimir, escribir a disco, escribir en la DB—
        // queda adentro del mismo runCatching: un disco lleno o una excepción del Room no puede
        // tumbar la reproducción, así que se traduce en "no se guardó" y listo.
        try {
            return runCatching {
                val pixeles = IntArray(bitmap.width * bitmap.height)
                bitmap.getPixels(pixeles, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                if (!GuardasDeFrame.noEsCasiNegro(pixeles)) return@runCatching false
                val salida = ByteArrayOutputStream()
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, CALIDAD, salida)) return@runCatching false
                almacen.guardar(episodeId, salida.toByteArray())
                dao.upsert(
                    EpisodeFrameEntity(
                        episodeId = episodeId,
                        positionMs = positionMs,
                        capturedAt = ahora(),
                        updatedAt = ahora(),
                    ),
                )
                true
            }.getOrDefault(false)
        } finally {
            bitmap.recycle()
        }
    }

    private companion object {
        const val ANCHO = 960
        const val ALTO = 540
        const val CALIDAD = 80
    }
}
