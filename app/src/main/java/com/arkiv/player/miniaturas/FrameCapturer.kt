package com.arkiv.player.miniaturas

import android.graphics.Bitmap
import android.view.TextureView
import com.arkiv.player.data.db.EpisodeFrameDao
import com.arkiv.player.data.db.EpisodeFrameEntity
import com.arkiv.player.data.db.PlaybackDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/**
 * Captura el frame que se está viendo y lo deja guardado.
 *
 * Best-effort de punta a punta: si algo falla —no hay TextureView, el decodificador devuelve
 * negro, el disco está lleno— no pasa nada y se conserva el frame anterior. Esto es una mejora
 * visual, jamás un motivo para molestar al que está viendo algo.
 *
 * NO captura capítulos ya vistos, y la guarda vive ACÁ y no en los disparadores a propósito: son
 * tres (el sondeo cada 5 min, la pausa y el `onDispose` de salida, todos en `PlayerScreen`) y uno
 * solo que se olvide vuelve a abrir el agujero. Ver [publicar] para el porqué del momento exacto
 * en que se chequea.
 */
class FrameCapturer(
    private val almacen: AlmacenDeFrames,
    private val dao: EpisodeFrameDao,
    /**
     * Para saber si el capítulo ya quedó visto. Va sin default: es parte de lo que esta clase
     * promete ("el frame solo existe si el capítulo se empezó y no se terminó"), no un extra
     * opcional que se pueda olvidar en un call site nuevo.
     */
    private val playbackDao: PlaybackDao,
    private val ahora: () -> Long = { System.currentTimeMillis() },
) {
    /**
     * Se la llama desde el hilo principal a propósito: `getBitmap()` lee la capa de hardware del
     * TextureView y solo vale ahí. Lo caro —medio millón de píxeles, comprimir a JPEG, escribir a
     * disco y a Room— se va a [Dispatchers.IO]: quien dispara la captura ya está en el hilo de UI
     * (`viewModelScope` usa `Main.immediate`, así que sin un cambio de hilo explícito el cuerpo
     * corría INLINE en el hilo de composición hasta la primera suspensión de verdad), y eso en el
     * Fire TV son decenas de ms = frames de video perdidos en cada captura.
     */
    suspend fun capturar(episodeId: String, positionMs: Long, textureView: TextureView?): Boolean {
        if (!GuardasDeFrame.posicionSirve(positionMs)) return false
        val vista = textureView ?: return false
        val bitmap = runCatching { vista.getBitmap(ANCHO, ALTO) }.getOrNull() ?: return false
        // El try/finally envuelve al withContext, no va adentro: así el recycle() corre también si
        // la corrutina se cancela antes de que el bloque de IO llegue a ejecutarse (el ViewModel se
        // limpia mientras tanto, por ejemplo), que es el único camino por el que se fugaría el mapa
        // de bits.
        try {
            // Todo lo que sigue —leer los píxeles, comprimir, escribir a disco, escribir en la DB—
            // queda adentro del mismo runCatching: un disco lleno o una excepción del Room no puede
            // tumbar la reproducción, así que se traduce en "no se guardó" y listo.
            return withContext(Dispatchers.IO) {
                runCatching {
                    val pixeles = IntArray(bitmap.width * bitmap.height)
                    bitmap.getPixels(pixeles, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                    if (!GuardasDeFrame.noEsCasiNegro(pixeles)) return@runCatching false
                    val salida = ByteArrayOutputStream()
                    if (!bitmap.compress(Bitmap.CompressFormat.JPEG, CALIDAD, salida)) return@runCatching false
                    publicar(episodeId, positionMs, salida.toByteArray())
                }.getOrDefault(false)
            }
        } finally {
            bitmap.recycle()
        }
    }

    /**
     * Escribe el archivo y la fila, salvo que el capítulo YA esté visto.
     *
     * El chequeo va acá —después de comprimir, pegado a la escritura— y no al principio de
     * [capturar], porque el problema es de CARRERA, no de intención: al salir del reproductor, el
     * `onDispose` lanza `saveProgress` (que pasado el 60% marca visto y destruye el frame) e
     * inmediatamente la captura. Como comprimir el JPEG cuesta decenas de ms, la captura aterriza
     * ÚLTIMA: preguntar por `watched` al entrar daría "todavía no" y se escribiría igual, dejando el
     * capítulo terminado con un frame vivo que ya nadie va a borrar (no quedan ticks del reproductor
     * que vuelvan a llamar al destructor) — y desde la fase 2 eso se sube a PocketBase y se propaga
     * a los demás aparatos. Rompía además el invariante del que vive `EleccionDeMiniatura`: el frame
     * solo existe si el capítulo se empezó.
     *
     * Se lee `playback` y no la propia `episode_frame`: el tombstone del destructor podría no
     * haberse escrito todavía, mientras que el visto es el hecho que lo origina.
     *
     * `internal` y no privada para que el test pueda ejercer la guarda sin un TextureView (que no
     * existe fuera de un dispositivo). El único llamador de producción es [capturar].
     */
    internal suspend fun publicar(episodeId: String, positionMs: Long, jpeg: ByteArray): Boolean {
        if (playbackDao.get(episodeId)?.watched == true) return false
        almacen.guardar(episodeId, jpeg)
        dao.upsert(
            EpisodeFrameEntity(
                episodeId = episodeId,
                positionMs = positionMs,
                capturedAt = ahora(),
                updatedAt = ahora(),
            ),
        )
        return true
    }

    private companion object {
        const val ANCHO = 960
        const val ALTO = 540
        const val CALIDAD = 80
    }
}
