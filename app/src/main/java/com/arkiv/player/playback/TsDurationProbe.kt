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

    /** Por encima de esto el parseo se fue a la basura: mejor sin duración que con una inventada. */
    private const val MAX_CREIBLE_MS = 24L * 60 * 60 * 1000

    /**
     * Duración en ms entre el primer PCR de [cabeza] y el último de [cola] del MISMO pid, o 0 si no
     * se puede determinar. Exigir el mismo pid evita mezclar relojes de programas distintos, que
     * darían un número disparatado.
     *
     * El parseo de paquetes vive en [MpegTs] desde que [TsSegmenter] necesitó exactamente lo mismo:
     * dos copias acabarían respondiendo distinto sobre una vuelta del contador o sobre dónde
     * empieza un paquete, y eso se vería como una barra que no cuadra con el playlist.
     */
    fun durationMs(cabeza: ByteArray, cola: ByteArray): Long {
        val primero = MpegTs.pcrs(cabeza).firstOrNull() ?: return 0L
        val ultimo = MpegTs.pcrs(cola).lastOrNull { it.pid == primero.pid } ?: return 0L
        val ms = MpegTs.deltaTicks(primero.base90k, ultimo.base90k) * 1000 / MpegTs.PCR_HZ
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
