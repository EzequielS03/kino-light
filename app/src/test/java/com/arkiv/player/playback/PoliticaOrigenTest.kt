package com.arkiv.player.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cuánto esperar a archive.org y cuándo volver a intentar. Ver [PoliticaOrigen] para el porqué.
 */
class PoliticaOrigenTest {

    // ─── el timeout de lectura crece con cada intento ──────────────────────
    // Medido el 2026-08-10 contra el ítem de Evangelion: 72,3 s hasta el primer byte (206 correcto,
    // solo lento). Con los 20 s fijos de antes, TODOS los intentos morían por timeout.

    @Test fun el_primer_intento_no_espera_eternamente() {
        // El caso común (origen sano) contesta en segundos: si el primer intento ya pagara los 90 s
        // del peor caso, un origen realmente muerto tendría al usuario mirando la nada 4 minutos.
        assertEquals(20_000, PoliticaOrigen.respuestaMs(0))
    }

    @Test fun cada_reintento_le_da_mas_aire_al_origen() {
        assertTrue(PoliticaOrigen.respuestaMs(1) > PoliticaOrigen.respuestaMs(0))
        assertTrue(PoliticaOrigen.respuestaMs(2) > PoliticaOrigen.respuestaMs(1))
    }

    @Test fun el_ultimo_intento_cubre_los_72s_que_se_midieron() {
        assertTrue(
            "el último intento tiene que aguantar el peor caso medido (72,3 s)",
            PoliticaOrigen.respuestaMs(PoliticaOrigen.INTENTOS - 1) > 72_300,
        )
    }

    @Test fun un_intento_de_mas_no_se_pasa_del_tope() {
        // Nadie debería pedir el intento 9, pero si pasa no puede devolver media hora.
        assertEquals(PoliticaOrigen.respuestaMs(PoliticaOrigen.INTENTOS - 1), PoliticaOrigen.respuestaMs(9))
    }

    @Test fun conectar_es_corto_porque_el_apreton_de_manos_no_es_lo_lento() {
        // Medido: conexión 4,28 s contra primer byte 72,3 s. Lo que tarda es el nodo sirviendo.
        assertTrue(PoliticaOrigen.CONECTAR_MS <= 20_000)
    }

    // ─── la espera entre intentos crece ────────────────────────────────────
    // Antes eran 400 ms fijos: contra un origen saturado, tres intentos en 1,2 s son tres golpes
    // seguidos al mismo nodo que ya está diciendo que no da abasto.

    @Test fun la_espera_entre_intentos_crece() {
        assertTrue(PoliticaOrigen.esperaMs(1) > PoliticaOrigen.esperaMs(0))
        assertTrue(PoliticaOrigen.esperaMs(2) > PoliticaOrigen.esperaMs(1))
    }

    @Test fun la_primera_espera_sigue_siendo_corta() {
        // Un no esporádico se recupera enseguida; no hay que castigar el caso bueno.
        assertEquals(400L, PoliticaOrigen.esperaMs(0))
    }

    // ─── qué vale la pena reintentar ───────────────────────────────────────
    // Esta es la distinción que no existía: el código trataba "me rechazó" y "no contestó a tiempo"
    // como lo mismo.

    @Test fun un_404_no_se_reintenta_nunca() {
        // El archivo no está: reintentar es perder 3 timeouts para llegar al mismo 404. Además es
        // la señal de que archive renombró y hay que revalidar la metadata.
        assertFalse(PoliticaOrigen.valeReintentar(404))
    }

    @Test fun un_503_se_reintenta() {
        assertTrue(PoliticaOrigen.valeReintentar(503))
    }

    @Test fun un_timeout_se_reintenta() {
        // -1 es el código que pone el proxy cuando la conexión murió sin respuesta.
        assertTrue(PoliticaOrigen.valeReintentar(-1))
    }

    @Test fun los_otros_errores_de_servidor_se_reintentan() {
        listOf(429, 500, 502, 504).forEach {
            assertTrue("$it debería reintentarse", PoliticaOrigen.valeReintentar(it))
        }
    }

    @Test fun un_exito_no_se_reintenta() {
        assertFalse(PoliticaOrigen.valeReintentar(200))
        assertFalse(PoliticaOrigen.valeReintentar(206))
    }

    @Test fun un_410_tampoco_se_reintenta() {
        // Igual que el 404: el recurso no vuelve por insistir.
        assertFalse(PoliticaOrigen.valeReintentar(410))
    }

    // ─── el presupuesto total no puede explotar ────────────────────────────

    @Test fun el_peor_caso_completo_no_pasa_de_tres_minutos() {
        // Un origen muerto tiene que rendirse en un tiempo que un humano tolere mirando la pantalla.
        val total = (0 until PoliticaOrigen.INTENTOS).sumOf {
            PoliticaOrigen.respuestaMs(it).toLong() + PoliticaOrigen.esperaMs(it)
        }
        assertTrue("presupuesto total = ${total}ms", total <= 180_000)
    }

    // ─── el aguante NO puede ser el mismo para todos los orígenes ──────────
    // Medido el 2026-08-11 contra el CDN de magis (`yuwc.swzablvpm.com`), 20 tiros sobre el mismo
    // archivo: cabeza 0,11-0,13 s, cola 0,13-0,30 s, y con tres conexiones drenando a la vez la
    // cola seguía contestando en 0,44-0,82 s. Magis contesta en MENOS DE UN SEGUNDO o no contesta
    // nunca — el caso "lento pero llega" que justifica los 20 s de archive no existe acá.
    //
    // Lo que costaba en device: VLC pidió la cola del archivo, esa conexión salió muerta, y el
    // proxy se sentó a esperarla 20,1 s. El reintento contestó en 101 ms.

    @Test fun magis_no_espera_veinte_segundos_a_una_conexion_muerta() {
        assertTrue(
            "el CDN de magis sano contesta en <1 s: esperar más es tiempo muerto",
            PoliticaOrigen.respuestaMs(0, PoliticaOrigen.Perfil.MAGIS) <= 4_000,
        )
    }

    @Test fun magis_agota_sus_intentos_antes_de_que_el_rescate_de_video_se_dispare() {
        // ESTA es la invariante que faltaba. El rescate "sin imagen → paso a software" de VlcPlayer
        // salta a los 10 s (SIN_VIDEO_MS) y recarga el media ENTERO. Si el reintento del proxy llega
        // después, quien rescata la reproducción es la recarga —cara: 10 s de espera, todo de nuevo
        // y encima en decodificación por software— en vez del reintento, que cuesta 101 ms.
        val perfil = PoliticaOrigen.Perfil.MAGIS
        val total = (0 until PoliticaOrigen.intentos(perfil)).sumOf {
            PoliticaOrigen.respuestaMs(it, perfil).toLong() + PoliticaOrigen.esperaMs(it, perfil)
        }
        assertTrue("presupuesto de magis = ${total}ms, tiene que caber en los 10 s", total < 10_000)
    }

    // Esperar la RESPUESTA y aguantar un hueco leyendo el CUERPO son dos cosas distintas, y meterlas
    // en el mismo número es lo que hace peligroso bajarlo: `readTimeout` de HttpURLConnection rige
    // las dos. Un origen que no contesta en 2 s está muerto; un stream que se queda 2 s sin datos a
    // mitad de película es un bache de WiFi normal, y cortarlo ahí sería una regresión.

    @Test fun aguantar_un_hueco_del_cuerpo_es_mas_largo_que_esperar_la_respuesta() {
        PoliticaOrigen.Perfil.entries.forEach { perfil ->
            assertTrue(
                "$perfil: el cuerpo tiene que aguantar más que la respuesta",
                PoliticaOrigen.cuerpoMs(perfil) > PoliticaOrigen.respuestaMs(0, perfil),
            )
        }
    }

    @Test fun magis_aguanta_un_bache_de_wifi_a_mitad_de_pelicula() {
        assertTrue(
            "cortar el cuerpo a los pocos segundos rompería la reproducción, no la arreglaría",
            PoliticaOrigen.cuerpoMs(PoliticaOrigen.Perfil.MAGIS) >= 20_000,
        )
    }

    @Test fun archive_conserva_el_aguante_que_le_hizo_falta() {
        // No es una regresión aceptable: el caso de Evangelion (72,3 s hasta el primer byte) sigue
        // teniendo que entrar.
        val perfil = PoliticaOrigen.Perfil.ARCHIVE
        assertEquals(20_000, PoliticaOrigen.respuestaMs(0, perfil))
        assertTrue(PoliticaOrigen.respuestaMs(PoliticaOrigen.intentos(perfil) - 1, perfil) > 72_300)
    }

    @Test fun magis_no_reusa_sockets_del_pool() {
        // Hipótesis del cuelgue, y el motivo de que `curl` nunca lo reprodujera: curl abre socket
        // nuevo cada vez y en 26 tiros no colgó ni uno. En device colgaba siempre la conexión
        // abierta justo después de que `precalentar` abandonara un `bytes=0-` de 209 MB habiendo
        // leído 2 MB — o sea, con cuerpo sin drenar quedando en el pool de keep-alive.
        assertFalse(PoliticaOrigen.Perfil.MAGIS.reusaSockets)
    }

    @Test fun archive_sigue_reusando_sockets() {
        // Archive.org sí se porta bien con keep-alive y reusar le ahorra el apretón de manos.
        assertTrue(PoliticaOrigen.Perfil.ARCHIVE.reusaSockets)
    }
}
