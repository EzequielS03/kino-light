package com.arkiv.player.remote

import java.util.Random
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `RemoteController` resuelve al arrancar cuál es el TV de la cuenta, y reintenta hasta encontrarlo.
 * En un aparato SIN TV pareado (un emulador, un celu al que le desvincularon la TV) ese bucle no
 * termina nunca: se queda pidiendo `devices?filter=kind='tv'` mientras viva el proceso.
 *
 * Con un intervalo fijo eso costaba una petición cada 5s las 24 horas. Medido en los logs reales de
 * PocketBase: de 03:00 a 11:00, sin nadie usando nada, 694 peticiones POR HORA sostenidas — una cada
 * 5,18s, que es exactamente ese `delay`. Sobre un Celeron N3050 que ya está en swap.
 *
 * El reintento tiene que espaciarse: rápido al principio (la sesión puede tardar en estar lista) y
 * cada vez más lento si sigue sin haber TV.
 */
class ResolucionDeTvBackoffTest {

    private fun intentosEn(ms: Long, semilla: Long, jitterMinimo: Boolean): Int {
        // Con `jitterMinimo` se fuerza el PEOR caso (todas las esperas en su mínimo): es el que
        // decide cuántas peticiones se hacen de verdad.
        val random = if (jitterMinimo) object : Random(semilla) {
            override fun nextDouble(): Double = 0.0
        } else Random(semilla)
        val backoff = RemoteController.backoffDeResolucionDeTv(random)
        var transcurrido = 0L
        var intentos = 0
        while (transcurrido < ms) {
            transcurrido += backoff.nextDelayMs(intentos)
            intentos++
        }
        return intentos
    }

    @Test
    fun ochoHorasSinTvNoPasanDeUnPuñadoDePeticiones() {
        val ochoHoras = 8 * 60 * 60 * 1000L
        val intentos = intentosEn(ochoHoras, semilla = 1, jitterMinimo = true)
        assertTrue(
            "un aparato sin TV hizo $intentos peticiones en 8h (antes eran ~5.700)",
            intentos < 150,
        )
    }

    @Test
    fun elPrimerReintentoSigueSiendoRapido() {
        // No sirve de nada ahorrar peticiones si la app tarda minutos en ver un TV que sí está. El
        // primer reintento cubre sobre todo el caso "la sesión todavía no estaba lista al arrancar":
        // tiene que volver a intentar en segundos.
        val backoff = RemoteController.backoffDeResolucionDeTv(Random(7))
        val primero = backoff.nextDelayMs(0)
        assertTrue("el primer reintento tardó ${primero}ms", primero <= 5_000)
    }
}
