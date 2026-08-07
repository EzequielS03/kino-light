package com.arkiv.player.data.local

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException

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
 */
class HttpRangeDownloader(private val client: OkHttpClient) {

    suspend fun download(
        url: String,
        target: File,
        headers: Map<String, String> = emptyMap(),
        onProgress: (bytesDone: Long, totalBytes: Long) -> Unit,
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            target.parentFile?.mkdirs()
            val part = LocalFilePaths.partOf(target)
            val startByte = if (part.exists()) part.length() else 0L

            val builder = Request.Builder().url(url)
            headers.forEach { (k, v) -> builder.header(k, v) }
            RangeMath.rangeHeaderFor(startByte)?.let { builder.header("Range", it) }

            client.newCall(builder.build()).execute().use { resp ->
                if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
                val body = resp.body ?: throw IOException("respuesta sin cuerpo")

                // Si se pidió Range y el server respondió 200 (no lo soporta), lo que llega es el
                // archivo ENTERO: hay que descartar el parcial o quedaría duplicado el prefijo.
                val appending = startByte > 0 && resp.code == 206
                if (startByte > 0 && !appending) part.delete()
                val effectiveStart = if (appending) startByte else 0L
                val total = RangeMath.totalBytesOf(body.contentLength(), effectiveStart)

                var written = effectiveStart
                java.io.FileOutputStream(part, appending).use { out ->
                    val buf = ByteArray(64 * 1024)
                    body.byteStream().use { input ->
                        while (true) {
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
                    throw IOException("descarga incompleta: $written de $total bytes")
                }
                if (target.exists()) target.delete()
                if (!part.renameTo(target)) throw IOException("no se pudo renombrar el parcial")
                target
            }
        }
    }
}
