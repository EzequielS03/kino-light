package com.arkiv.player.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class BarSourceTest {

    private fun foto(episodeId: String = "ep-tv", positionMs: Long = 1_000) = TvNowPlaying(
        episodeId = episodeId, itemId = "it", kind = "ARCHIVE", title = "t", subtitle = "s",
        posterUrl = "", positionMs = positionMs, durationMs = 60_000,
        state = TvPlaybackState.PLAYING, hasNext = false, hasPrev = false, at = "",
    )

    @Test
    fun `sin nada activo no hay barra`() {
        assertNull(
            BarSource.pick(
                castActivo = false, cast = null, castDesdeMs = 0,
                tv = null, tvDesdeMs = 0, ahoraMs = 100,
            ),
        )
    }

    @Test
    fun `solo el TV manda cuando no hay cast`() {
        val r = BarSource.pick(
            castActivo = false, cast = null, castDesdeMs = 0,
            tv = TvSnapshot(foto(), receivedAtMs = 50), tvDesdeMs = 10, ahoraMs = 100,
        )!!
        assertEquals(BarFuente.TV, r.fuente)
        assertEquals("ep-tv", r.nowPlaying.episodeId)
        assertEquals(50, r.receivedAtMs)
    }

    @Test
    fun `el TV se extrapola`() {
        val r = BarSource.pick(
            castActivo = false, cast = null, castDesdeMs = 0,
            tv = TvSnapshot(foto(), receivedAtMs = 50), tvDesdeMs = 10, ahoraMs = 100,
        )!!
        assertTrue(r.extrapolar)
    }

    @Test
    fun `el cast NO se extrapola porque el player esta en el celu`() {
        val r = BarSource.pick(
            castActivo = true, cast = foto(episodeId = "ep-cast"), castDesdeMs = 10,
            tv = null, tvDesdeMs = 0, ahoraMs = 100,
        )!!
        assertFalse(r.extrapolar)
    }

    @Test
    fun `el cast lleva el instante actual porque se lee al momento`() {
        val r = BarSource.pick(
            castActivo = true, cast = foto(episodeId = "ep-cast"), castDesdeMs = 10,
            tv = null, tvDesdeMs = 0, ahoraMs = 777,
        )!!
        assertEquals(777, r.receivedAtMs)
    }

    @Test
    fun `sesion de cast activa pero sin medio cargado cae al TV`() {
        val r = BarSource.pick(
            castActivo = true, cast = null, castDesdeMs = 0,
            tv = TvSnapshot(foto(), receivedAtMs = 50), tvDesdeMs = 10, ahoraMs = 100,
        )!!
        assertEquals(BarFuente.TV, r.fuente)
    }

    @Test
    fun `sesion de cast activa sin medio y sin TV no da barra`() {
        assertNull(
            BarSource.pick(
                castActivo = true, cast = null, castDesdeMs = 0,
                tv = null, tvDesdeMs = 0, ahoraMs = 100,
            ),
        )
    }

    @Test
    fun `sin sesion de cast un pending viejo no manda y cae al TV`() {
        val r = BarSource.pick(
            castActivo = false, cast = foto(episodeId = "ep-cast-viejo"), castDesdeMs = 900,
            tv = TvSnapshot(foto(episodeId = "ep-tv"), receivedAtMs = 50), tvDesdeMs = 10,
            ahoraMs = 100,
        )!!
        assertEquals(BarFuente.TV, r.fuente)
        assertEquals("ep-tv", r.nowPlaying.episodeId)
    }

    @Test
    fun `sin sesion de cast un pending viejo no da barra si el TV no reproduce`() {
        assertNull(
            BarSource.pick(
                castActivo = false, cast = foto(episodeId = "ep-cast-viejo"), castDesdeMs = 900,
                tv = null, tvDesdeMs = 0, ahoraMs = 100,
            ),
        )
    }

    // --- Gana la que arrancó último: una fuente saca a la otra de la barra ---

    @Test
    fun `castear despues tapa lo que muestra el Fire TV`() {
        val r = BarSource.pick(
            castActivo = true, cast = foto(episodeId = "ep-cast"), castDesdeMs = 200,
            tv = TvSnapshot(foto(episodeId = "ep-tv"), receivedAtMs = 50), tvDesdeMs = 100,
            ahoraMs = 300,
        )!!
        assertEquals(BarFuente.CAST, r.fuente)
        assertEquals("ep-cast", r.nowPlaying.episodeId)
    }

    @Test
    fun `poner algo en el TV despues recupera la barra aunque el casteo siga vivo`() {
        val r = BarSource.pick(
            castActivo = true, cast = foto(episodeId = "ep-cast"), castDesdeMs = 100,
            tv = TvSnapshot(foto(episodeId = "ep-tv"), receivedAtMs = 50), tvDesdeMs = 200,
            ahoraMs = 300,
        )!!
        assertEquals(BarFuente.TV, r.fuente)
        assertEquals("ep-tv", r.nowPlaying.episodeId)
    }

    @Test
    fun `el TV que gana sigue trayendo su instante y su extrapolacion`() {
        val r = BarSource.pick(
            castActivo = true, cast = foto(episodeId = "ep-cast"), castDesdeMs = 100,
            tv = TvSnapshot(foto(episodeId = "ep-tv"), receivedAtMs = 50), tvDesdeMs = 200,
            ahoraMs = 300,
        )!!
        assertTrue(r.extrapolar)
        assertEquals(50, r.receivedAtMs)
    }

    @Test
    fun `el cast que gana sigue sin extrapolarse y con el instante actual`() {
        val r = BarSource.pick(
            castActivo = true, cast = foto(episodeId = "ep-cast"), castDesdeMs = 200,
            tv = TvSnapshot(foto(episodeId = "ep-tv"), receivedAtMs = 50), tvDesdeMs = 100,
            ahoraMs = 300,
        )!!
        assertFalse(r.extrapolar)
        assertEquals(300, r.receivedAtMs)
    }

    @Test
    fun `con las dos vivas y marcas empatadas manda el Chromecast`() {
        val r = BarSource.pick(
            castActivo = true, cast = foto(episodeId = "ep-cast"), castDesdeMs = 150,
            tv = TvSnapshot(foto(episodeId = "ep-tv"), receivedAtMs = 50), tvDesdeMs = 150,
            ahoraMs = 300,
        )!!
        assertEquals(BarFuente.CAST, r.fuente)
    }
}

class SellarMarcaTest {

    @Test
    fun `misma identidad no re-sella la marca`() {
        val previa = MarcaFuente("ep-A#1", desdeMs = 100)
        val resultado = sellarMarca(previa, identidad = "ep-A#1", ahoraMs = 999)
        assertEquals(100L, resultado.desdeMs)
    }

    @Test
    fun `identidad null congela la marca en vez de borrarla (hipo de WiFi)`() {
        val previa = MarcaFuente("ep-A#1", desdeMs = 100)
        val resultado = sellarMarca(previa, identidad = null, ahoraMs = 999)
        assertEquals(previa, resultado)
    }

    @Test
    fun `identidad distinta re-sella con ahoraMs`() {
        val previa = MarcaFuente("ep-A#1", desdeMs = 100)
        val resultado = sellarMarca(previa, identidad = "ep-B#1", ahoraMs = 999)
        assertEquals(MarcaFuente("ep-B#1", desdeMs = 999), resultado)
    }

    @Test
    fun `un hueco solo -null en el medio- no cuenta como reinicio`() {
        var marca = MarcaFuente("ep-A#1", desdeMs = 100)
        marca = sellarMarca(marca, identidad = null, ahoraMs = 200) // hipo de WiFi
        marca = sellarMarca(marca, identidad = "ep-A#1", ahoraMs = 300) // vuelve lo mismo
        assertEquals(MarcaFuente("ep-A#1", desdeMs = 100), marca)
    }

    @Test
    fun `mismo episodio con apertura nueva SI re-sella`() {
        // Fix 2: reiniciar el MISMO capítulo en el Fire TV es un arranque nuevo, no una continuación.
        var marca = MarcaFuente("ep-A#1", desdeMs = 100)
        marca = sellarMarca(marca, identidad = "ep-A#2", ahoraMs = 500)
        assertEquals(MarcaFuente("ep-A#2", desdeMs = 500), marca)
    }

    @Test
    fun `desde la marca vacia la primera identidad se sella`() {
        val resultado = sellarMarca(MarcaFuente(), identidad = "ep-A#1", ahoraMs = 42)
        assertEquals(MarcaFuente("ep-A#1", desdeMs = 42), resultado)
    }
}
