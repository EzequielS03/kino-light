package com.arkiv.player.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Saltarse el redirector de archive.org y hablarle directo al nodo que tiene el archivo.
 * Ver [NodoDeArchive] para el porqué.
 */
class NodoDeArchiveTest {

    private val ID = "get-backers-05-pelea-a-muerte"

    /** Forma real de /metadata/, recortada (medida el 2026-08-10 contra el ítem roto). */
    private val METADATA = """
        {"server":"dn601200.us.archive.org",
         "dir":"/0/items/$ID",
         "d1":"dn601200.us.archive.org",
         "workable_servers":["dn601200.us.archive.org"],
         "servers_unavailable":true,
         "alternate_locations":{
           "servers":[{"server":"dn721608.ca.archive.org","dir":"/0/items/$ID"}],
           "workable":[{"server":"dn721608.ca.archive.org","dir":"/0/items/$ID"}]}}
    """.trimIndent()

    // ─── cuándo vale la pena esquivar el redirector ────────────────────────

    @Test fun un_5xx_del_redirector_merece_probar_el_nodo() {
        // Medido: download.php devolvía 500/503 mientras el nodo servía 206 sin quejarse.
        listOf(500, 502, 503).forEach {
            assertTrue("$it debería mandarnos al nodo", NodoDeArchive.valeIntentarNodo(it))
        }
    }

    @Test fun un_timeout_tambien_merece_probar_el_nodo() {
        // El redirector se colgaba sin contestar y el nodo respondía en 0,73 s.
        assertTrue(NodoDeArchive.valeIntentarNodo(PoliticaOrigenCodigos.SIN_RESPUESTA))
    }

    @Test fun un_404_no_se_busca_en_el_nodo() {
        // Si el archivo no está, no está en ningún lado: el nodo daría el mismo 404 más lento.
        assertFalse(NodoDeArchive.valeIntentarNodo(404))
    }

    @Test fun un_exito_no_necesita_nodo() {
        assertFalse(NodoDeArchive.valeIntentarNodo(200))
        assertFalse(NodoDeArchive.valeIntentarNodo(206))
    }

    @Test fun un_403_tampoco_porque_es_de_permisos() {
        assertFalse(NodoDeArchive.valeIntentarNodo(403))
    }

    // ─── desarmar la URL canónica ──────────────────────────────────────────

    @Test fun saca_identifier_y_ruta_de_una_url_de_descarga() {
        val partes = NodoDeArchive.partesDeUrlDeDescarga(
            "https://archive.org/download/$ID/Get%20Backers%2019.mp4",
        )
        assertEquals(ID to "Get%20Backers%2019.mp4", partes)
    }

    @Test fun conserva_las_carpetas_de_adentro_del_item() {
        val partes = NodoDeArchive.partesDeUrlDeDescarga(
            "https://archive.org/download/eva/Evangelion%20Spanish%20dubs/Cap02.mkv",
        )
        assertEquals("eva" to "Evangelion%20Spanish%20dubs/Cap02.mkv", partes)
    }

    @Test fun deja_la_ruta_tal_cual_vino_codificada() {
        // Decodificar acá y recodificar después es la receta para romper los nombres con '@', '%'
        // o espacios. La ruta viaja como está y se pega al nodo sin tocarla.
        val partes = NodoDeArchive.partesDeUrlDeDescarga(
            "https://archive.org/download/eva/TPO_02_%40Trapo2019.mkv",
        )
        assertEquals("TPO_02_%40Trapo2019.mkv", partes?.second)
    }

    @Test fun una_url_que_no_es_de_descarga_no_sirve() {
        assertNull(NodoDeArchive.partesDeUrlDeDescarga("https://archive.org/metadata/$ID"))
        assertNull(NodoDeArchive.partesDeUrlDeDescarga("https://example.com/download/x/y.mp4"))
    }

    @Test fun una_descarga_sin_archivo_no_sirve() {
        assertNull(NodoDeArchive.partesDeUrlDeDescarga("https://archive.org/download/$ID"))
        assertNull(NodoDeArchive.partesDeUrlDeDescarga("https://archive.org/download/$ID/"))
    }

    // ─── armar las URLs del nodo ───────────────────────────────────────────

    @Test fun el_nodo_primario_va_primero() {
        val urls = NodoDeArchive.urlsDesdeMetadata(METADATA, "Get%20Backers%2019.mp4")
        assertEquals(
            "https://dn601200.us.archive.org/0/items/$ID/Get%20Backers%2019.mp4",
            urls.first(),
        )
    }

    @Test fun los_alternos_quedan_de_respaldo() {
        val urls = NodoDeArchive.urlsDesdeMetadata(METADATA, "x.mp4")
        assertTrue(urls.any { it.startsWith("https://dn721608.ca.archive.org/0/items/") })
    }

    @Test fun no_repite_el_mismo_nodo_dos_veces() {
        // El primario aparece también en workable_servers: pedirlo dos veces es gastar un intento.
        val urls = NodoDeArchive.urlsDesdeMetadata(METADATA, "x.mp4")
        assertEquals(urls.size, urls.distinct().size)
    }

    @Test fun sin_metadata_utilizable_no_inventa_nada() {
        assertTrue(NodoDeArchive.urlsDesdeMetadata("", "x.mp4").isEmpty())
        assertTrue(NodoDeArchive.urlsDesdeMetadata("no soy json", "x.mp4").isEmpty())
        assertTrue(NodoDeArchive.urlsDesdeMetadata("""{"error":"item metadata may be invalid"}""", "x.mp4").isEmpty())
    }

    @Test fun se_arregla_con_solo_los_alternos() {
        // Si el primario no vino, los alternos siguen sirviendo.
        val soloAlternos = """
            {"alternate_locations":{"workable":[{"server":"dn9.us.archive.org","dir":"/7/items/abc"}]}}
        """.trimIndent()
        assertEquals(
            listOf("https://dn9.us.archive.org/7/items/abc/v.mp4"),
            NodoDeArchive.urlsDesdeMetadata(soloAlternos, "v.mp4"),
        )
    }
}

/** Espejo local del código de "no contestó" para no acoplar el test al paquete de playback. */
private object PoliticaOrigenCodigos {
    const val SIN_RESPUESTA = -1
}
