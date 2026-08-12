package com.arkiv.player.playback

import com.arkiv.player.playback.MediaReusePolicy.Decision
import org.junit.Assert.assertEquals
import org.junit.Test

class MediaReusePolicyTest {

    private val EP = "torrent:94c65c7ff4b0c5abdfce7659fcfd2b20b93805d9::0"

    /**
     * El bug de la pantalla negra en la TV: volver a abrir el MISMO episodio de torrent después de
     * que la sesión anterior se cerró. El MediaItem viejo sigue en el controller con ese episodeId,
     * pero su URL apunta al puerto efímero del servidor que `startStream()` ya mató, y el torrent
     * nuevo está sirviendo en otro puerto. Comparar solo el mediaId daba "es el mismo, reusá" y
     * VLC se quedaba con la URL muerta: nunca abría nada.
     */
    @Test fun mismo_episodio_con_url_nueva_recarga() {
        val d = MediaReusePolicy.decide(
            episodeId = EP,
            cargado = listOf(LoadedMedia(EP, "http://127.0.0.1:41111/video")),
            actualMediaId = EP,
            fresco = listOf(LoadedMedia(EP, "http://127.0.0.1:46793/video")),
            isWeb = false,
            pedido = EP,
        )
        assertEquals(Decision.RECARGAR, d)
    }

    /** El servidor sigue vivo en el mismo puerto (volver atrás y entrar sin cortar el stream):
     * ahí sí conviene reenganchar y aprovechar lo que ya está buffereado. */
    @Test fun mismo_episodio_con_misma_url_reusa() {
        val url = "http://127.0.0.1:46793/video"
        val d = MediaReusePolicy.decide(
            episodeId = EP,
            cargado = listOf(LoadedMedia(EP, url)),
            actualMediaId = EP,
            fresco = listOf(LoadedMedia(EP, url)),
            isWeb = false,
            pedido = EP,
        )
        assertEquals(Decision.REUSAR_ACTUAL, d)
    }

    /** Navegación dentro de una serie de archive ya cargada: mismas URLs, otro capítulo → saltar
     * dentro de la playlist sin recargar (es lo que hace que cambiar de episodio sea instantáneo). */
    @Test fun otro_episodio_de_la_misma_playlist_salta() {
        val a = LoadedMedia("archive:serie::0", "https://archive.org/download/serie/e0.mp4")
        val b = LoadedMedia("archive:serie::1", "https://archive.org/download/serie/e1.mp4")
        val d = MediaReusePolicy.decide(
            episodeId = b.mediaId,
            cargado = listOf(a, b),
            actualMediaId = a.mediaId,
            fresco = listOf(a, b),
            isWeb = false,
            pedido = b.mediaId,
        )
        assertEquals(Decision.SALTAR_EN_PLAYLIST, d)
    }

    /** Misma playlist por ids pero el episodio destino cambió de URL (pack de torrent re-servido en
     * otro puerto): saltar reusaría la URL muerta igual que el bug de arriba. */
    @Test fun otro_episodio_de_la_misma_playlist_con_url_nueva_recarga() {
        val a = LoadedMedia("torrent:abc::0", "http://127.0.0.1:41111/video")
        val b = LoadedMedia("torrent:abc::1", "http://127.0.0.1:41111/video?f=1")
        val d = MediaReusePolicy.decide(
            episodeId = b.mediaId,
            cargado = listOf(a, b),
            actualMediaId = a.mediaId,
            fresco = listOf(
                a.copy(uri = "http://127.0.0.1:46793/video"),
                b.copy(uri = "http://127.0.0.1:46793/video?f=1"),
            ),
            isWeb = false,
            pedido = b.mediaId,
        )
        assertEquals(Decision.RECARGAR, d)
    }

    /** WEB: el token del host expira aunque la URL se vea igual → recargar siempre, sin excepción. */
    @Test fun web_siempre_recarga() {
        val url = "https://cdn.example/stream.m3u8?token=abc"
        val d = MediaReusePolicy.decide(
            episodeId = "web:peli::0",
            cargado = listOf(LoadedMedia("web:peli::0", url)),
            actualMediaId = "web:peli::0",
            fresco = listOf(LoadedMedia("web:peli::0", url)),
            isWeb = true,
            pedido = "web:peli::0",
        )
        assertEquals(Decision.RECARGAR, d)
    }

    /** Arranque en frío: no hay nada en el controller. */
    @Test fun nada_cargado_recarga() {
        val d = MediaReusePolicy.decide(
            episodeId = EP,
            cargado = emptyList(),
            actualMediaId = null,
            fresco = listOf(LoadedMedia(EP, "http://127.0.0.1:46793/video")),
            isWeb = false,
            pedido = EP,
        )
        assertEquals(Decision.RECARGAR, d)
    }

    /** Contenido distinto al que está cargado. */
    @Test fun episodio_que_no_esta_cargado_recarga() {
        val d = MediaReusePolicy.decide(
            episodeId = EP,
            cargado = listOf(LoadedMedia("archive:otra::0", "https://archive.org/x.mp4")),
            actualMediaId = "archive:otra::0",
            fresco = listOf(LoadedMedia(EP, "http://127.0.0.1:46793/video")),
            isWeb = false,
            pedido = EP,
        )
        assertEquals(Decision.RECARGAR, d)
    }

    /**
     * El bug del carrusel de capítulos (Fire TV, 12-ago-2026, Dragon Ball E130 → E131): elegir el
     * capítulo siguiente volvía a reproducir el que estaba sonando, desde el principio.
     *
     * Al navegar, la pantalla se recompone DE CERO (`loaded` vuelve a false) pero el ViewModel
     * sobrevive —misma entrada del back stack— con la playlist del capítulo ANTERIOR todavía
     * publicada. Esa playlist vieja llegaba 4 ms después de entrar, mucho antes de que resolviera
     * la nueva (~4 s en magis), y como sus ids coincidían con lo cargado daba SALTAR_EN_PLAYLIST:
     * el `indexOfFirst` del capítulo pedido daba -1, `coerceAtLeast(0)` lo mandaba al índice 0 y
     * sonaba otra vez el anterior. Encima dejaba `loaded=true`, así que cuando por fin llegaba la
     * playlist buena el guard de "ya está cargado" la descartaba y no había forma de salir de ahí
     * salvo volver a elegir el capítulo (segunda vez: el ViewModel ya tenía la playlist correcta).
     *
     * Por eso la decisión mira [PlaylistData.pedido]: lo que llegó todavía no es lo mío → esperar.
     */
    @Test fun playlist_del_capitulo_anterior_espera() {
        val anterior = LoadedMedia("magis:DB::e130", "https://cdn.example/e130.ts")
        val d = MediaReusePolicy.decide(
            episodeId = "magis:DB::e131",
            cargado = listOf(anterior),
            actualMediaId = anterior.mediaId,
            fresco = listOf(anterior),
            isWeb = false,
            pedido = anterior.mediaId,
        )
        assertEquals(Decision.ESPERAR, d)
    }

    /**
     * Lo mismo en web, y va ANTES que la regla de "web siempre recarga": ahí la playlist vieja no
     * dejaba el player pegado (la buena entraba después), pero igual arrancaba unos segundos del
     * capítulo anterior antes de corregirse.
     */
    @Test fun playlist_del_capitulo_anterior_espera_tambien_en_web() {
        val anterior = LoadedMedia("web:serie::e1", "https://cdn.example/e1.m3u8?token=abc")
        val d = MediaReusePolicy.decide(
            episodeId = "web:serie::e2",
            cargado = listOf(anterior),
            actualMediaId = anterior.mediaId,
            fresco = listOf(anterior),
            isWeb = true,
            pedido = anterior.mediaId,
        )
        assertEquals(Decision.ESPERAR, d)
    }

    /**
     * La playlist SÍ es la de este capítulo pero no lo contiene: pasa en archive cuando el episodio
     * pedido no tiene variante reproducible (`buildData` lo descarta) y la sección se arma sin él.
     * No es el caso de arriba —no hay nada mejor que esperar— así que se carga lo que vino, que es
     * el comportamiento de siempre. Distinguirlo es justo para lo que existe `pedido`: mirar sólo
     * "¿está mi episodio en la lista?" dejaría la pantalla esperando para siempre.
     */
    @Test fun playlist_propia_que_no_contiene_al_episodio_recarga() {
        val otro = LoadedMedia("archive:serie::0", "https://archive.org/download/serie/e0.mp4")
        val d = MediaReusePolicy.decide(
            episodeId = "archive:serie::7",
            cargado = emptyList(),
            actualMediaId = null,
            fresco = listOf(otro),
            isWeb = false,
            pedido = "archive:serie::7",
        )
        assertEquals(Decision.RECARGAR, d)
    }
}
