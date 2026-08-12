package com.arkiv.player.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * La línea de datos del capítulo que el héroe del home muestra debajo del título.
 *
 * La regla que gobierna todo esto: cada tramo se OMITE si no se sabe, nunca se inventa. El héroe es
 * lo primero que se lee en la pantalla, así que un dato inventado ahí es peor que un dato ausente.
 */
class EtiquetaDeCapituloHeroeTest {

    private fun linea(
        esPelicula: Boolean = false,
        season: Int? = 1,
        episode: Int? = 5,
        orderIndex: Int = 4,
        nombre: String? = "La conspiración",
        positionMs: Long = 3 * 60_000L,
        durationMs: Long = 15 * 60_000L,
    ) = EtiquetaDeCapitulo.lineaDeHeroe(
        esPelicula = esPelicula,
        season = season,
        episode = episode,
        orderIndex = orderIndex,
        nombre = nombre,
        positionMs = positionMs,
        durationMs = durationMs,
    )

    @Test
    fun `con todo resuelto trae numero, nombre y lo que falta`() {
        assertEquals("T1 · E5  ·  La conspiración  ·  te faltan 12 min", linea())
    }

    @Test
    fun `sin nombre de TMDB quedan el numero y el tiempo`() {
        assertEquals("T1 · E5  ·  te faltan 12 min", linea(nombre = null))
    }

    /** Un nombre en blanco es lo mismo que no tenerlo: no deja un separador colgando. */
    @Test
    fun `un nombre en blanco se trata como ausente`() {
        assertEquals("T1 · E5  ·  te faltan 12 min", linea(nombre = "   "))
    }

    /** Magis con la sonda de duración pendiente: `durationMs` llega en 0. */
    @Test
    fun `sin duracion conocida no se inventa el tiempo`() {
        assertEquals("T1 · E5  ·  La conspiración", linea(durationMs = 0L))
    }

    @Test
    fun `sin temporada pero con capitulo no se inventa la temporada`() {
        assertEquals("E5  ·  La conspiración  ·  te faltan 12 min", linea(season = null))
    }

    /** En packs de torrent el `orderIndex` codifica temporada*1000 + capítulo. */
    @Test
    fun `sin numeracion cae al orderIndex de pack de torrent`() {
        assertEquals(
            "T2 · E3  ·  La conspiración  ·  te faltan 12 min",
            linea(season = null, episode = null, orderIndex = 2003),
        )
    }

    /** En archive.org el `orderIndex` es un correlativo que arranca en 0. */
    @Test
    fun `sin numeracion cae al orderIndex correlativo de archive`() {
        assertEquals(
            "E1  ·  La conspiración  ·  te faltan 12 min",
            linea(season = null, episode = null, orderIndex = 0),
        )
    }

    /** Una película no tiene temporada ni capítulo: un "E1" ahí sería ruido. */
    @Test
    fun `una pelicula solo dice cuanto falta`() {
        assertEquals("te faltan 12 min", linea(esPelicula = true))
    }

    @Test
    fun `una pelicula sin duracion conocida no dice nada`() {
        assertEquals("", linea(esPelicula = true, durationMs = 0L))
    }

    /** Lo que se muestra es lo que QUEDA, no lo transcurrido. */
    @Test
    fun `recien empezado casi todo el capitulo esta por delante`() {
        assertEquals(
            "T1 · E5  ·  La conspiración  ·  te faltan 24 min",
            linea(positionMs = 60_000L, durationMs = 25 * 60_000L),
        )
    }

    /** A menos de un minuto del final no queda nada útil que decir del tiempo. */
    @Test
    fun `casi terminado ya no muestra el tiempo`() {
        assertEquals(
            "T1 · E5  ·  La conspiración",
            linea(positionMs = 25 * 60_000L - 30_000L, durationMs = 25 * 60_000L),
        )
    }

    // --- Bordes de la aritmética del tiempo restante -----------------------------------------------

    /** El tiempo mostrado es lo que queda completo, sin redondear hacia arriba. */
    @Test
    fun `trunca minutos incompletos hacia abajo`() {
        assertEquals(
            "T1 · E5  ·  La conspiración  ·  te faltan 1 min",
            linea(positionMs = 0L, durationMs = 90_000L),  // restante = 90_000 ms = 1.5 min → "1 min"
        )
    }

    /** El borde del umbral mínimo: exactamente 60_000 ms se muestra. */
    @Test
    fun `en el umbral exacto de 60 segundos si muestra el tiempo`() {
        assertEquals(
            "T1 · E5  ·  La conspiración  ·  te faltan 1 min",
            linea(positionMs = 14 * 60_000L, durationMs = 15 * 60_000L),  // restante = 60_000 ms exacto
        )
    }

    /** Justo debajo del umbral: 59_999 ms no se muestra. */
    @Test
    fun `debajo del umbral por un milisegundo ya no muestra el tiempo`() {
        assertEquals(
            "T1 · E5  ·  La conspiración",
            linea(positionMs = 14 * 60_000L + 1, durationMs = 15 * 60_000L),  // restante = 59_999 ms
        )
    }

    /** Restante negativo (duración se re-mide y baja): se omite sin mostrar negativos. */
    @Test
    fun `restante negativo se omite sin inventar tiempo`() {
        assertEquals(
            "T1 · E5  ·  La conspiración",
            linea(positionMs = 20 * 60_000L, durationMs = 15 * 60_000L),  // restante = -300_000 ms
        )
    }

    // --- Por encima de la hora reusa formatRuntime, no minutos crudos ------------------------------

    /** Una película recién empezada: por encima de los 60 min pasa a horas, como `formatRuntime`. */
    @Test
    fun `mas de una hora restante se muestra en horas y minutos`() {
        assertEquals(
            "T1 · E5  ·  La conspiración  ·  te faltan 1 h 26 min",
            linea(positionMs = 0L, durationMs = 86 * 60_000L),
        )
    }

    /** El borde de exactamente 60 min: sin minutos sueltos, `formatRuntime` no los agrega. */
    @Test
    fun `exactamente 60 minutos restantes se muestra como 1 h sin minutos`() {
        assertEquals(
            "T1 · E5  ·  La conspiración  ·  te faltan 1 h",
            linea(positionMs = 0L, durationMs = 60 * 60_000L),
        )
    }

    // --- El núcleo compartido con el detalle -------------------------------------------------

    @Test
    fun `el numero con valores sueltos sigue la misma regla que el del modelo`() {
        assertEquals("T1 · E5", EtiquetaDeCapitulo.numero(season = 1, episode = 5, orderIndex = 4))
        assertEquals("E5", EtiquetaDeCapitulo.numero(season = null, episode = 5, orderIndex = 4))
        assertEquals("T2 · E3", EtiquetaDeCapitulo.numero(season = null, episode = null, orderIndex = 2003))
        assertEquals("E1", EtiquetaDeCapitulo.numero(season = null, episode = null, orderIndex = 0))
    }
}
