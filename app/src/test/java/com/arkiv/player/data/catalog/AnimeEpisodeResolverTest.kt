package com.arkiv.player.data.catalog

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AnimeEpisodeResolverTest {

    // AoT Final Season: usuario pide el ep 5 de la entrada; su absoluto es 64 (offset 59).
    private val aot = AnimeQueryInput(
        titles = listOf("Shingeki no Kyojin: The Final Season", "Attack on Titan Final Season"),
        episode = 5, absoluteEpisode = 64, tvdbSeason = 4,
    )

    @Test
    fun `genera queries con numero absoluto y con SxxEyy`() {
        val q = AnimeEpisodeResolver.spec(aot).queries
        assertTrue(q.any { it.contains("Shingeki no Kyojin") && it.contains("64") })
        assertTrue(q.any { it.contains("S04E05") })
    }

    @Test
    fun `matches acepta el release absoluto y el relativo del episodio correcto`() {
        val s = AnimeEpisodeResolver.spec(aot)
        assertTrue(s.matches("[SubsPlease] Shingeki no Kyojin (The Final Season) - 64 (1080p).mkv"))
        assertTrue(s.matches("[AnimeRG] Shingeki no Kyojin (The Final Season) 05 [1080p Dual Audio]"))
        assertTrue(s.matches("Attack on Titan S04E05 1080p Dual"))
    }

    @Test
    fun `matches rechaza otro episodio y otro anime`() {
        val s = AnimeEpisodeResolver.spec(aot)
        assertFalse(s.matches("[SubsPlease] Shingeki no Kyojin (The Final Season) - 63 (1080p).mkv"))
        assertFalse(s.matches("[SubsPlease] Frieren - 05 (1080p).mkv"))
    }

    // Naruto (2002) ep 2, sin absoluto (serie de una sola temporada larga).
    private val naruto = AnimeQueryInput(titles = listOf("Naruto"), episode = 2, absoluteEpisode = null, tvdbSeason = 1)

    @Test fun `no casa el titulo con el nombre del grupo de fansub entre corchetes`() {
        val s = AnimeEpisodeResolver.spec(naruto)
        // Casos REALES del device: el grupo "[Naruto-Kun.Hu]" hacía colar otros animes al buscar Naruto.
        assertFalse(s.matches("[Naruto-Kun.Hu] Jujutsu Kaisen S3 - 02 [1080p].mkv"))
        assertFalse(s.matches("[Naruto-Kun.Hu] Black Clover 002 [1080p].mkv"))
    }

    @Test fun `acepta un pack de rango que contiene el episodio`() {
        val s = AnimeEpisodeResolver.spec(naruto)
        // Caso REAL del device: el pack que SÍ tiene el episodio se descartaba.
        assertTrue(s.matches("Naruto (2002) [1-220] Complete"))
        assertTrue(s.matches("Naruto 001-220 Batch [1080p]"))
    }

    @Test fun `no confunde una resolucion con un rango de episodios`() {
        // Ep 800: caería DENTRO de [720,1080] si la resolución se tomara como rango. El filtro lo evita.
        val s = AnimeEpisodeResolver.spec(
            AnimeQueryInput(titles = listOf("Naruto"), episode = 800, absoluteEpisode = null, tvdbSeason = 1),
        )
        assertFalse(s.matches("Naruto Special [720-1080] x265.mkv"))
    }

    // One Piece: el usuario pide el ep 5 de la entrada de AniList (Egghead), absoluto 1085,
    // y TVDB lo ubica en la temporada 21. Los fansubs publican "S01E1085" (esquema absoluto).
    private val onePiece = AnimeQueryInput(
        titles = listOf("One Piece"),
        episode = 5, absoluteEpisode = 1085, tvdbSeason = 21,
    )

    @Test
    fun `matches acepta el esquema de fansub SxxE-absoluto aunque la temporada no case`() {
        val s = AnimeEpisodeResolver.spec(onePiece)
        // El nº es el ABSOLUTO (1085): la temporada del release (01) no significa nada.
        assertTrue(s.matches("[SubsPlease] One Piece S01E1085 (1080p) [ABCD1234].mkv"))
        // Y el esquema TVDB continuo también.
        assertTrue(s.matches("One Piece S21E1085 1080p"))
    }

    @Test
    fun `matches sigue rechazando el relativo en otra temporada`() {
        val s = AnimeEpisodeResolver.spec(onePiece)
        // "S01E05" es el episodio 5 de la temporada 1, NO el 1085 que pedimos.
        assertFalse(s.matches("One Piece S01E05 1080p"))
    }

    @Test
    fun `matches rechaza el mismo episodio de OTRA temporada via SxxEyy`() {
        val s = AnimeEpisodeResolver.spec(aot) // temporada 4, episodio 5
        assertFalse(s.matches("Attack on Titan S01E05 1080p Dual"))
        assertTrue(s.matches("Attack on Titan S04E05 1080p Dual"))
    }

    @Test
    fun `sin temporada conocida un SxxEyy con numeracion continua igual matchea`() {
        // tvdbSeason = null (sin mapeo): no exigir temporada; el nº del SxxEyy vale como episodio.
        val op = AnimeQueryInput(titles = listOf("One Piece"), episode = 1085, absoluteEpisode = null)
        val s = AnimeEpisodeResolver.spec(op)
        assertTrue(s.matches("One Piece S21E1085 1080p"))
        assertFalse(s.matches("One Piece S21E1084 1080p"))
    }

    @Test
    fun `matches no toma resolucion ni codec como numero de episodio`() {
        val s = AnimeEpisodeResolver.spec(aot)
        assertFalse(s.matches("Shingeki no Kyojin (The Final Season) 1080p x264"))
    }

    @Test
    fun `long-runner sin offset matchea por numero absoluto de 4 digitos`() {
        val op = AnimeQueryInput(titles = listOf("One Piece"), episode = 1085, absoluteEpisode = null)
        val s = AnimeEpisodeResolver.spec(op)
        assertTrue(s.matches("[SubsPlease] One Piece - 1085 (1080p).mkv"))
        assertFalse(s.matches("[SubsPlease] One Piece - 1084 (1080p).mkv"))
    }

    @Test
    fun `canonicalEpisode normaliza absoluto y relativo al mismo numero de la entrada`() {
        val s = AnimeEpisodeResolver.spec(aot)
        // Ambos releases son el ep 5 de la entrada: uno viene como 64 (abs), otro como 05.
        assertEquals(5, s.canonicalEpisode("Shingeki no Kyojin (The Final Season) - 64"))
        assertEquals(5, s.canonicalEpisode("Shingeki no Kyojin (The Final Season) 05"))
    }

    @Test
    fun `primaryEpisodeNumber agrupa por el primer patron fuerte, null en packs`() {
        assertEquals(64, AnimeText.primaryEpisodeNumber("Shingeki no Kyojin (The Final Season) - 64"))
        assertNull(AnimeText.primaryEpisodeNumber("One Piece Complete Series [BD 1080p]"))
    }

    @Test
    fun `tvdbSeason=0 (especiales) arma la query en S00 y no fuerza S01`() {
        val special = AnimeQueryInput(titles = listOf("Frieren"), episode = 5, tvdbSeason = 0)
        val s = AnimeEpisodeResolver.spec(special)
        assertTrue(s.queries.any { it.contains("S00E05") })
        assertTrue(s.matches("Frieren S00E05 1080p"))
        assertFalse(s.matches("Frieren S01E05 1080p"))
    }

    // helper local (evita depender de import estático en cada assert)
    private fun assertEquals(expected: Int, actual: Int?) =
        org.junit.Assert.assertEquals(expected as Int?, actual)
}
