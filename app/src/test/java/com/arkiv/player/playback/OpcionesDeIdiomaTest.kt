package com.arkiv.player.playback

import com.arkiv.player.data.subtitles.PlaybackPrefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Decirle a libVLC qué idioma de audio querés ANTES de abrir, en vez de corregirlo después.
 *
 * Portado de la app original de magis, que le pasa a su ijkplayer `setOption(4, "audio_language",
 * <idioma>)` al construirlo (`yc/C6276a.java:37`), así que ffmpeg elige la pista correcta en el
 * momento de abrir el archivo. Nosotros hacíamos lo contrario: dejar que libVLC eligiera la primera
 * y corregirla después de `Playing` con reintentos (`applyPreferredAudio`), una carrera que el KDoc
 * de VlcPlayer describe como "hay que ganarle" — y que se pierde sola cuando las pistas pueblan
 * tarde.
 *
 * OJO con lo que esto NO resuelve: `--audio-language` compara contra el código ISO de la pista, y
 * ahí Latino y Castellano son los dos `spa`. La distinción entre variantes del español sale del
 * NOMBRE de la pista y la sigue haciendo [TrackSelector]. Esto lleva la selección al idioma
 * correcto de entrada; el pase fino queda como estaba.
 */
class OpcionesDeIdiomaTest {

    private fun prefs(vararg audio: TrackLang) = PlaybackPrefs(audioLangs = audio.toList())

    @Test fun `las tres variantes del espanol colapsan a un solo codigo`() {
        assertEquals(
            "spa,es",
            OpcionesDeIdioma.codigosDe(listOf(TrackLang.LATINO, TrackLang.CASTELLANO, TrackLang.SPANISH)),
        )
    }

    @Test fun `respeta el orden del usuario`() {
        assertEquals(
            "jpn,ja,spa,es",
            OpcionesDeIdioma.codigosDe(listOf(TrackLang.JAPANESE, TrackLang.LATINO)),
        )
        assertEquals(
            "spa,es,jpn,ja",
            OpcionesDeIdioma.codigosDe(listOf(TrackLang.LATINO, TrackLang.JAPANESE)),
        )
    }

    @Test fun `cada bucket aporta su codigo de tres letras y el de dos`() {
        assertEquals("eng,en", OpcionesDeIdioma.codigosDe(listOf(TrackLang.ENGLISH)))
    }

    /** DUAL y UNKNOWN no son idiomas: no hay código ISO que pedirle a VLC. */
    @Test fun `dual y desconocido no aportan codigo`() {
        assertNull(OpcionesDeIdioma.codigosDe(listOf(TrackLang.DUAL, TrackLang.UNKNOWN)))
        assertNull(OpcionesDeIdioma.codigosDe(emptyList()))
    }

    @Test fun `dual entre idiomas reales simplemente se saltea`() {
        assertEquals(
            "spa,es,eng,en",
            OpcionesDeIdioma.codigosDe(listOf(TrackLang.LATINO, TrackLang.DUAL, TrackLang.ENGLISH)),
        )
    }

    // ---- La opción tal como se le pasa al Media ----

    @Test fun `la opcion de audio va con el prefijo que espera libVLC`() {
        assertEquals(
            ":audio-language=spa,es",
            OpcionesDeIdioma.opcionDeAudio(prefs(TrackLang.LATINO, TrackLang.SPANISH)),
        )
    }

    /** Sin ningún idioma pedible no se manda opción: que VLC haga lo suyo. */
    @Test fun `sin idiomas reales no se manda opcion`() {
        assertNull(OpcionesDeIdioma.opcionDeAudio(prefs(TrackLang.DUAL)))
        assertNull(OpcionesDeIdioma.opcionDeAudio(prefs()))
    }

    /** Las preferencias de fábrica son las de una audiencia hispana: tienen que producir español. */
    @Test fun `las preferencias por defecto piden espanol`() {
        assertEquals(":audio-language=spa,es", OpcionesDeIdioma.opcionDeAudio(PlaybackPrefs()))
    }
}
