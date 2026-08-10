package com.arkiv.player.playback

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * Duración de un MPEG-TS, deducida de sus PCR.
 *
 * El TS es un formato de emisión: no lleva la duración en ninguna cabecera. La única forma de
 * saber cuánto dura es restar el reloj del programa (PCR) del final menos el del principio.
 * libVLC hace exactamente eso… pero SOLO cuando el acceso es de lectura rápida (un archivo local):
 * sobre HTTP nunca sondea el final, así que `mediaPlayer.length` se queda en 0. Con duración 0 la
 * barra de progreso se llena de golpe, la derecha marca 00:00, no se puede adelantar (buscar por
 * TIEMPO se ignora sin duración) y no se guarda dónde ibas.
 *
 * Como el origen sí acepta Range, la sacamos nosotros: 256 KB del principio + 256 KB del final.
 * Medido contra la película real de magis, da 89 ms de diferencia con ffprobe (10 143,84 s vs
 * 10 143,93 s), que para pintar la barra y buscar sobra.
 */
object TsDurationProbe {

    private const val TAG = "ArkivTsDur"

    /** Tamaño de un paquete TS. Invariante del formato. */
    private const val PACKET = 188

    /** Cuánto se baja de cada punta para buscar PCR. 256 KB ≈ 1400 paquetes: de sobra (el PCR se
     *  repite como mínimo cada 100 ms por norma). */
    const val PROBE_BYTES = 256 * 1024

    /** El PCR es un contador de 33 bits a 90 kHz: da la vuelta cada ~26,5 h. */
    private const val PCR_WRAP = 1L shl 33

    /** Por encima de esto el parseo se fue a la basura: mejor sin duración que con una inventada. */
    private const val MAX_CREIBLE_MS = 24L * 60 * 60 * 1000

    private data class Pcr(val pid: Int, val base90k: Long)

    /**
     * Duración en ms entre el primer PCR de [cabeza] y el último de [cola] del MISMO pid, o 0 si no
     * se puede determinar. Exigir el mismo pid evita mezclar relojes de programas distintos, que
     * darían un número disparatado.
     */
    fun durationMs(cabeza: ByteArray, cola: ByteArray): Long {
        val primero = pcrs(cabeza).firstOrNull() ?: return 0L
        val ultimo = pcrs(cola).lastOrNull { it.pid == primero.pid } ?: return 0L
        var delta = ultimo.base90k - primero.base90k
        if (delta < 0) delta += PCR_WRAP          // el contador dio la vuelta a mitad del archivo
        val ms = delta * 1000 / 90_000
        return if (ms in 1..MAX_CREIBLE_MS) ms else 0L
    }

    /**
     * Baja las dos puntas del stream y calcula la duración. Devuelve 0 si algo falla: es una mejora
     * de la barra, nunca un motivo para no reproducir.
     *
     * [headers] son los del origen (magis sirve detrás de `Content-Auth` y `Content-License`).
     */
    suspend fun probeRemote(url: String, headers: Map<String, String>): Long = withContext(Dispatchers.IO) {
        val t0 = System.currentTimeMillis()
        // UNA PUNTA A LA VEZ. El CDN de magis atiende de a una conexión por archivo: pedir cabeza y
        // cola en paralelo se pisa solo y devuelve 504 en las dos (visto en device), y la película
        // queda sin duración = barra llena. La cola va por rango-sufijo (`bytes=-N`) para no tener
        // que preguntar antes el tamaño.
        val cola = fetchRange(url, headers, "bytes=-$PROBE_BYTES")
            ?: return@withContext 0L
        val cabeza = fetchRange(url, headers, "bytes=0-${PROBE_BYTES - 1}")
            ?: return@withContext 0L
        val ms = durationMs(cabeza, cola)
        android.util.Log.w(
            TAG,
            "sonda PCR: cabeza=${cabeza.size}B cola=${cola.size}B → duracion=${ms}ms " +
                "(${System.currentTimeMillis() - t0}ms)",
        )
        ms
    }

    /** Todos los PCR del bloque, en orden de aparición. */
    private fun pcrs(buf: ByteArray): List<Pcr> {
        val inicio = alineacion(buf)
        if (inicio < 0) return emptyList()
        val out = ArrayList<Pcr>()
        var i = inicio
        while (i + PACKET <= buf.size) {
            leerPcr(buf, i)?.let { out.add(it) }
            i += PACKET
        }
        return out
    }

    /**
     * Offset del primer paquete completo. Un Range cae en cualquier byte, así que la cola llega
     * cortada a mitad de paquete: se busca el 0x47 que se repite cada 188 bytes (uno suelto puede
     * ser un byte cualquiera del payload; tres seguidos, no).
     */
    private fun alineacion(buf: ByteArray): Int {
        if (buf.size < PACKET) return -1
        // El desfase, por definición, cae dentro del primer paquete.
        val limite = minOf(buf.size - PACKET, PACKET - 1)
        for (off in 0..limite) {
            var k = 0
            var ok = true
            // Se confirman hasta 3 paquetes seguidos (los que quepan): con uno solo bastaría un
            // 0x47 cualquiera del payload para engañarnos.
            while (k < 3 && off + PACKET * (k + 1) <= buf.size) {
                if (buf[off + PACKET * k] != 0x47.toByte()) { ok = false; break }
                k++
            }
            if (ok && k > 0) return off
        }
        return -1
    }

    /** PCR del paquete que empieza en [i], si lo trae. */
    private fun leerPcr(buf: ByteArray, i: Int): Pcr? {
        if (buf[i] != 0x47.toByte()) return null
        val afc = (buf[i + 3].toInt() shr 4) and 0x3
        if (afc != 2 && afc != 3) return null                 // sin campo de adaptación → sin PCR
        val afLen = buf[i + 4].toInt() and 0xFF
        if (afLen < 7) return null                            // no cabe un PCR (6 bytes + flags)
        if (buf[i + 5].toInt() and 0x10 == 0) return null     // flag de PCR apagado
        val b = { k: Int -> buf[i + k].toLong() and 0xFF }
        val base = (b(6) shl 25) or (b(7) shl 17) or (b(8) shl 9) or (b(9) shl 1) or (b(10) shr 7)
        val pid = ((buf[i + 1].toInt() and 0x1F) shl 8) or (buf[i + 2].toInt() and 0xFF)
        return Pcr(pid, base)
    }

    /** Un tramo, con un reintento: el CDN devuelve 504 esporádicos aunque se pida de a uno. */
    private fun fetchRange(url: String, headers: Map<String, String>, range: String): ByteArray? =
        intentarTramo(url, headers, range) ?: run {
            Thread.sleep(600)
            intentarTramo(url, headers, range)
        }

    private fun intentarTramo(url: String, headers: Map<String, String>, range: String): ByteArray? =
        runCatching {
            val conn = abrir(url, headers, range)
            // Sin 206 el servidor ignoró el Range y estaría mandando el archivo ENTERO (cientos de
            // MB por una sonda). Se corta antes de leer nada.
            if (conn.responseCode != HttpURLConnection.HTTP_PARTIAL) {
                android.util.Log.w(TAG, "tramo $range: el origen no respetó el Range (${conn.responseCode})")
                conn.disconnect()
                return null
            }
            val bytes = conn.inputStream.use { it.readBytes() }
            conn.disconnect()
            bytes
        }.getOrElse {
            android.util.Log.w(TAG, "falló el tramo $range: ${it.message}")
            null
        }

    private fun abrir(url: String, headers: Map<String, String>, range: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "Arkiv/0.1 (personal)")
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
            setRequestProperty("Range", range)
            connectTimeout = 10_000
            readTimeout = 15_000
        }
}
