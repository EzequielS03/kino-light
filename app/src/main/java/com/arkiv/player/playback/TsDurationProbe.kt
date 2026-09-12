package com.arkiv.player.playback

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * Duration of an MPEG-TS, deduced from its PCR.
 *
 * TS is a broadcast format: it doesn't carry the duration in any header. The only way to know how
 * long it runs is subtracting the program clock reference (PCR) at the start from the one at the
 * end. libVLC used to do exactly that... but ONLY when the access was fast-read (a local file):
 * over HTTP it never probed the end, so `mediaPlayer.length` stayed at 0. With duration 0 the
 * progress bar fills up instantly, the right side shows 00:00, there's no seeking forward (seeking
 * by TIME is ignored without a duration) and where you were doesn't get saved.
 *
 * Since the origin does accept Range, we pull it ourselves: 256 KB from the start + 256 KB from
 * the end. Measured against magis's real movie, it's 89ms off from ffprobe (10,143.84s vs
 * 10,143.93s), which is more than enough for drawing the bar and seeking.
 */
object TsDurationProbe {

    private const val TAG = "ArkivTsDur"

    /** Tamaño de un paquete TS. Invariante del formato. */
    private const val PACKET = 188

    /** Cuánto se baja de cada punta para buscar PCR. 256 KB ≈ 1400 paquetes: de sobra (el PCR se
     *  repite como mínimo cada 100 ms por norma). */
    const val PROBE_BYTES = 256 * 1024

    /**
     * Cuánto puede tardar la sonda ENTERA antes de que se la dé por perdida y se reproduzca sin
     * duración. Lo aplica quien llama (el video no arranca hasta que esto termina).
     *
     * Eran 30 s, que es una barbaridad para algo que solo pinta una barra de progreso: la duración
     * es una mejora, nunca un motivo para dejar al usuario mirando un spinner. El número de ahora
     * sale de [TsDurationProbeTest]: tiene que entrar el caso MEDIDO —una conexión muerta por
     * tramo, recuperada en el segundo intento— y poco más.
     */
    const val PRESUPUESTO_MS = 13_000L

    /**
     * Intentos por tramo, y cuánto se le aguanta a cada uno.
     *
     * Contra este CDN **abandonar rápido y volver a intentar gana**: una conexión nueva vuelve a
     * jugar la lotería, mientras que esperar a la mala solo gasta el presupuesto. Antes era un
     * intento de 15 s + uno de repuesto, y perdía cuando el CDN se ponía denso (visto en device:
     * los dos tramos vencidos y la película sin duración).
     *
     * Los NÚMEROS ya no viven acá. Esta sonda le pega exactamente al mismo CDN que
     * [ArchiveCacheProxy], así que tener su propia calibración solo servía para que las dos se
     * fueran separando: el 2026-08-11 el proxy esperaba 20 s y la sonda 8 s a la misma conexión
     * muerta, y las dos de más (el reintento contestaba en ~1 s). Fuente única:
     * [PoliticaOrigen.Perfil.MAGIS].
     */
    // Perfil propio y no el de la reproducción: ver [PoliticaOrigen.Perfil.MAGIS_SONDA]. Acá lo que
    // se juega es un spinner, no que la película se corte, así que se abandona antes.
    private val PERFIL = PoliticaOrigen.Perfil.MAGIS_SONDA
    private val INTENTOS = PoliticaOrigen.intentos(PERFIL)

    fun timeoutLecturaMs(intento: Int): Int = PoliticaOrigen.respuestaMs(intento, PERFIL)

    /** Respiro entre intentos: corto a propósito, la gracia es volver a tirar los dados ya. */
    fun esperaEntreIntentosMs(intento: Int): Long = PoliticaOrigen.esperaMs(intento, PERFIL)

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
        // UNA PUNTA A LA VEZ, y la cola primero. Medido después: el CDN SÍ atiende dos conexiones
        // al mismo archivo (la teoría vieja de "una por archivo" era falsa), pero responde cada
        // rango cuando quiere —entre 0,2 s y 20 s— así que pedir las dos juntas no acorta nada y
        // duplica las chances de comerse una mala. La cola va por rango-sufijo (`bytes=-N`) para
        // no tener que preguntar antes el tamaño, y va primera porque es la que más falla: si no
        // hay cola no hay duración, y así no se gasta el presupuesto bajando una cabeza inútil.
        val cola = fetchRange(url, headers, "bytes=-$PROBE_BYTES")
            ?: return@withContext 0L
        val cabeza = fetchRange(url, headers, "bytes=0-${PROBE_BYTES - 1}")
            ?: return@withContext 0L
        val ms = durationMs(cabeza, cola)
        android.util.Log.w(
            TAG,
            "PCR probe: head=${cabeza.size}B tail=${cola.size}B → duration=${ms}ms " +
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

    /** Un tramo, reintentando: ver [INTENTOS] para por qué son varios y cortos. */
    private fun fetchRange(url: String, headers: Map<String, String>, range: String): ByteArray? {
        repeat(INTENTOS) { i ->
            intentarTramo(url, headers, range, i)?.let { return it }
            if (i < INTENTOS - 1) Thread.sleep(esperaEntreIntentosMs(i))
        }
        android.util.Log.w(TAG, "range $range: exhausted all $INTENTOS attempts")
        return null
    }

    private fun intentarTramo(
        url: String,
        headers: Map<String, String>,
        range: String,
        intento: Int,
    ): ByteArray? =
        runCatching {
            val conn = abrir(url, headers, range, intento)
            // Sin 206 el servidor ignoró el Range y estaría mandando el archivo ENTERO (cientos de
            // MB por una sonda). Se corta antes de leer nada.
            if (conn.responseCode != HttpURLConnection.HTTP_PARTIAL) {
                android.util.Log.w(TAG, "range $range: the origin ignored the Range (${conn.responseCode})")
                conn.disconnect()
                return null
            }
            val bytes = conn.inputStream.use { it.readBytes() }
            conn.disconnect()
            bytes
        }.getOrElse {
            android.util.Log.w(TAG, "range $range failed: ${it.message}")
            null
        }

    private fun abrir(
        url: String,
        headers: Map<String, String>,
        range: String,
        intento: Int,
    ): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "Arkiv/0.1 (personal)")
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
            setRequestProperty("Range", range)
            // Socket nuevo, sin reciclar del pool: ver PoliticaOrigen.Perfil.reusaSockets.
            if (!PERFIL.reusaSockets) setRequestProperty("Connection", "close")
            connectTimeout = PERFIL.conectarMs
            readTimeout = timeoutLecturaMs(intento)
        }
}
