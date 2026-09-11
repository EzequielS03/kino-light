package com.arkiv.player.data.local

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import kotlin.coroutines.coroutineContext

/** Aritmética de la reanudación, aparte para poder testearla sin red ni disco. */
object RangeMath {
    /** null cuando no hay nada previo: pedir `bytes=0-` innecesariamente confunde a algunos hosts. */
    fun rangeHeaderFor(existingBytes: Long): String? =
        if (existingBytes > 0) "bytes=$existingBytes-" else null

    /** El `Content-Length` de una respuesta parcial es lo que FALTA, no el total del archivo. */
    fun totalBytesOf(contentLength: Long, startByte: Long): Long =
        if (contentLength <= 0) 0 else contentLength + startByte
}

/**
 * Descarga un archivo por HTTP con soporte de reanudación.
 *
 * Escribe siempre a `<target>.part` y renombra al final: así nunca existe un archivo destino a
 * medias que `LocalLibrary` pueda tomar por bueno y mandarle a VLC.
 *
 * Un parcial SOLO se reanuda si es del mismo origen (ver [LocalFilePaths.originOf]); si no coincide
 * —o si no tiene marca, que es el caso de los parciales que dejaron versiones anteriores— se tira y
 * se empieza de cero. Perder una vez lo bajado es infinitamente mejor que pegar la cola de un
 * archivo al prefijo de otro y marcarlo "Listo".
 */
class HttpRangeDownloader(private val client: OkHttpClient) {

    suspend fun download(
        url: String,
        target: File,
        headers: Map<String, String> = emptyMap(),
        /**
         * Identidad del origen para decidir si el `.part` se puede reanudar. Por defecto la propia
         * URL. Se pasa distinta cuando la URL NO es estable entre intentos aunque el contenido sí lo
         * sea: hoy es el caso de Magis, cuya URL trae un token que cambia en cada resolución (ver
         * `MagisDownloadStrategy`, que pasa el `episodeId` como clave); con la URL como clave un
         * simple reintento descartaría un parcial perfectamente válido de varios GB.
         */
        resumeKey: String = url,
        onProgress: (bytesDone: Long, totalBytes: Long) -> Unit,
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            target.parentFile?.mkdirs()
            val part = LocalFilePaths.partOf(target)
            val origin = LocalFilePaths.originOf(target)

            // Solo se reanuda un parcial del MISMO origen. Sin marca (parcial de una versión vieja)
            // cuenta como origen desconocido: no se puede afirmar que sea el mismo archivo.
            val sameOrigin = part.exists() &&
                runCatching { origin.readText() }.getOrNull() == resumeKey
            if (part.exists() && !sameOrigin) {
                part.delete()
                origin.delete()
            }
            val startByte = if (sameOrigin) part.length() else 0L

            val builder = Request.Builder().url(url)
            headers.forEach { (k, v) -> builder.header(k, v) }
            RangeMath.rangeHeaderFor(startByte)?.let { builder.header("Range", it) }

            client.newCall(builder.build()).execute().use { resp ->
                if (!resp.isSuccessful) throw HttpStatusException(resp.code)
                val body = resp.body ?: throw IOException("respuesta sin cuerpo")

                // Si se pidió Range y el server respondió 200 (no lo soporta), lo que llega es el
                // archivo ENTERO: hay que descartar el parcial o quedaría duplicado el prefijo.
                val appending = startByte > 0 && resp.code == 206
                if (startByte > 0 && !appending) part.delete()
                val effectiveStart = if (appending) startByte else 0L
                val total = RangeMath.totalBytesOf(body.contentLength(), effectiveStart)

                // La marca se (re)escribe ANTES del primer byte: si el proceso muere a mitad, el
                // parcial que quede ya está etiquetado y el próximo intento sabe de dónde vino.
                runCatching { origin.writeText(resumeKey) }

                var written = effectiveStart
                java.io.FileOutputStream(part, appending).use { out ->
                    val buf = ByteArray(64 * 1024)
                    body.byteStream().use { input ->
                        while (true) {
                            // El bucle de escritura no suspende, así que sin este chequeo una
                            // cancelación (el usuario tocó "Quitar", o WorkManager paró el worker)
                            // no se notaba hasta terminar el archivo entero: se seguían gastando
                            // datos móviles en algo ya cancelado. `ensureActive` lanza
                            // CancellationException y los `use` cierran stream y respuesta.
                            coroutineContext.ensureActive()
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            written += n
                            onProgress(written, total)
                        }
                    }
                    out.flush()
                }

                // Verificación: si el server declaró un tamaño y no llegó completo, es un corte.
                if (total > 0 && written < total) {
                    throw IncompleteDownloadException(written, total)
                }
                if (target.exists()) target.delete()
                if (!part.renameTo(target)) throw IOException("no se pudo renombrar el parcial")
                // Ya no hay parcial que identificar.
                runCatching { origin.delete() }
                target
            }
        }.onFailure {
            // `runCatching` también atrapa CancellationException, y tragársela convertiría un
            // "el usuario canceló" en un "falló la descarga" (fila en `failed` con un mensaje
            // absurdo) y le mentiría a WorkManager sobre por qué terminó el worker.
            if (it is kotlinx.coroutines.CancellationException) throw it
        }
    }
}
