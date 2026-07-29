package com.arkiv.player.data.catalog

import com.arkiv.player.data.catalog.providers.ContentType
import com.arkiv.player.data.catalog.providers.SearchContext
import java.text.Normalizer

/** Entrada para resolver las fuentes de UN episodio concreto de anime. */
data class AnimeQueryInput(
    val titles: List<String>,
    val episode: Int,
    /** Nº absoluto de serie (cuando difiere del de la entrada AniList). Null si no se pudo calcular. */
    val absoluteEpisode: Int? = null,
    /** Temporada TVDB (para armar SxxEyy). Null → se asume 1. */
    val tvdbSeason: Int? = null,
)

/**
 * Especificación de búsqueda de un episodio: las queries a lanzar y el predicado que decide si un
 * release corresponde (aceptando numeración absoluta o relativa) + normalización del nº de episodio.
 */
class SourceQuerySpec internal constructor(
    val queries: List<String>,
    private val episode: Int,
    private val absoluteEpisode: Int?,
    private val season: Int,
    private val seasonKnown: Boolean,
    private val anchors: List<List<String>>,
    private val searchTitles: List<String>,
) {
    // Números que identifican ESTE episodio en cualquier release (el de la entrada y el absoluto).
    private val epNumbers: Set<Int> = setOfNotNull(episode, absoluteEpisode)

    /** Números que identifican el episodio (relativo y absoluto) para filtrar torrents del backend. */
    val episodeNumbers: Set<Int> get() = epNumbers
    /** Temporada a exigir en el backend (0 = no exigir, cuando no hay mapeo TVDB). */
    val seasonForMirror: Int get() = if (seasonKnown) season else 0
    /** Título principal para el fallback por texto del backend. */
    val primaryTitle: String? get() = searchTitles.firstOrNull()

    /** ¿El release corresponde al título pedido Y a este episodio (abs o relativo)? */
    fun matches(releaseName: String): Boolean {
        // El ancla se valida sobre el nombre SIN los tags de grupo [...] (el título va FUERA de los
        // corchetes). Sin esto, "[Naruto-Kun.Hu] Jujutsu Kaisen" casaba "naruto" con el grupo de
        // fansub y colaba otro anime. (Detectado probando en device.)
        val words = AnimeText.significantWords(AnimeText.stripBracketTags(releaseName)).toSet()
        if (anchors.none { a -> words.containsAll(a) }) return false
        // Pack de RANGO que contiene el episodio ("[1-220] Complete"): para animes viejos que solo
        // existen en packs, es la única forma de encontrar el capítulo (el file-picker elige el archivo
        // del episodio dentro del pack al reproducir).
        if (AnimeText.rangeContainsAny(releaseName, epNumbers)) return true
        // Si el release trae SxxEyy explícito Y conocemos la temporada, DEBE coincidir (evita aceptar
        // el ep 5 de otra temporada del mismo anime, ej. S01E05 cuando pedimos la temporada 4 ep 5).
        // Si NO conocemos la temporada (sin mapeo TVDB), no la exigimos: el nº de episodio del SxxEyy
        // cae al match por número (soporta numeración TVDB continua tipo "One Piece S21E1085").
        val pairs = AnimeText.seasonEpisodePairs(releaseName)
        if (pairs.isNotEmpty() && seasonKnown) {
            if (pairs.any { (s, e) -> s == season && e == episode }) return true
            // Esquema de fansub "S01E<absoluto>" (comunísimo: SubsPlease/Erai publican "One Piece
            // S01E1085" aunque TVDB lo ubique en la temporada 21). Ahí la temporada del release no
            // significa nada. Sólo se acepta si el número casa con el ABSOLUTO y éste difiere del
            // relativo: así no colamos "S01E05" cuando pedimos la temporada 4 episodio 5.
            val abs = absoluteEpisode
            if (abs != null && abs != episode && pairs.any { (_, e) -> e == abs }) return true
            return false
        }
        // Sin SxxEyy (o temporada desconocida): numeración suelta contra los nº conocidos del episodio.
        val found = AnimeText.episodeNumbersIn(releaseName)
        return found.any { it in epNumbers }
    }

    /** Nº de episodio de la entrada AniList al que pertenece el release (o null si es pack). */
    fun canonicalEpisode(releaseName: String): Int? {
        val found = AnimeText.episodeNumbersIn(releaseName)
        if (found.isEmpty()) return null
        // Si aparece el absoluto, mapearlo al nº de la entrada; si aparece el relativo, tal cual.
        if (absoluteEpisode != null && absoluteEpisode in found) return episode
        return found.firstOrNull { it == episode } ?: found.min()
    }

    /** Contexto para el fallback on-device (proveedores declarativos), con numeración absoluta. */
    fun toSearchContext(): SearchContext =
        SearchContext(
            titles = searchTitles,
            type = ContentType.ANIME,
            episode = episode,
            episodeAbs = absoluteEpisode ?: episode,
        )
}

/** Construye la spec (queries + matcher) a partir de la entrada. Puro, sin red. */
object AnimeEpisodeResolver {
    fun spec(input: AnimeQueryInput): SourceQuerySpec {
        val titles = input.titles
            .map { it.trim() }
            .filter { it.isNotBlank() && !AnimeText.isCjkOnly(it) }
            .flatMap { listOf(it, AnimeText.stripAccents(it)) }
            .distinct()
        val season = (input.tvdbSeason ?: 1).coerceAtLeast(0)
        val ss = season.toString().padStart(2, '0')
        val ee = input.episode.toString().padStart(2, '0')
        val numbers = listOfNotNull(input.absoluteEpisode, input.episode).distinct()

        val queries = buildList {
            titles.take(4).forEach { t ->
                add(t)                                   // título pelado (trackers ES devuelven su catálogo)
                add("$t S${ss}E$ee")                     // relativo SxxEyy
                numbers.forEach { n ->
                    add("$t ${n.toString().padStart(2, '0')}")   // "One Piece 05"
                    if (n >= 100) add("$t $n")                   // "One Piece 1085"
                    add("$t - $n")                               // "One Piece - 1085"
                }
            }
        }.map { it.trim() }.filter { it.isNotBlank() }.distinct()

        val anchors = titles.map { AnimeText.significantWords(it).take(2) }.filter { it.isNotEmpty() }.distinct()
        return SourceQuerySpec(
            queries, input.episode, input.absoluteEpisode, season,
            seasonKnown = input.tvdbSeason != null, anchors, searchTitles = titles,
        )
    }
}

/** Utilidades de texto/numeración compartidas por el resolver (puras). */
internal object AnimeText {
    private val STOPWORDS = setOf("the", "and", "los", "las", "del", "for", "una", "que", "season", "final", "part")

    fun stripAccents(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")

    fun isCjkOnly(s: String): Boolean =
        s.isNotBlank() && s.none { it.code in 0x20..0x7F }

    fun significantWords(s: String): List<String> =
        stripAccents(s.lowercase()).split(Regex("[^a-z0-9]+"))
            .filter { it.length >= 3 && it !in STOPWORDS }

    /** Quita los tags entre corchetes ([Grupo], [1080p], [hash]…). El título del anime va SIEMPRE fuera
     *  de los corchetes; usarlo para el ancla evita casar el título con el nombre del grupo de fansub
     *  (ej. "[Naruto-Kun.Hu] Jujutsu Kaisen" casaba "naruto"). */
    fun stripBracketTags(s: String): String = s.replace(Regex("\\[[^\\]]*\\]"), " ")

    private val RANGE = Regex("""(?<!\d)(\d{1,4})\s*-\s*(\d{1,4})(?!\d)""")
    // Pares que son resoluciones, no rangos de episodios.
    private val RESOLUTION_PAIRS = setOf(480 to 720, 720 to 1080, 1080 to 2160, 1920 to 1080, 1280 to 720)

    /**
     * ¿El nombre declara un RANGO de episodios ("[1-220]", "001-500") que contiene alguno de [targets]?
     * Para animes/series viejos que solo existen en packs, es la única forma de encontrar el episodio
     * (luego el file-picker elige el archivo correcto dentro del pack). Descarta resoluciones y rangos
     * demasiado cortos para ser un batch.
     */
    fun rangeContainsAny(name: String, targets: Set<Int>): Boolean {
        if (targets.isEmpty()) return false
        for (m in RANGE.findAll(name)) {
            val lo = m.groupValues[1].toInt()
            val hi = m.groupValues[2].toInt()
            if (lo >= hi || hi > 9999 || hi - lo < 2) continue      // no es un batch plausible
            if ((lo to hi) in RESOLUTION_PAIRS) continue            // 720-1080… es resolución
            if (targets.any { it in lo..hi }) return true
        }
        return false
    }

    // Patrones "fuertes" de nº de episodio (orden = prioridad). Para agrupar (primaryEpisodeNumber).
    private val STRONG_PATTERNS = listOf(
        Regex("""\(e(\d{3,4})\)"""),                 // "(E1157)" absoluto
        Regex("""\bs\d{1,2}e(\d{1,4})\b"""),         // S04E05 / S21E1085 (relativo o continuo)
        Regex("""\s-\s(\d{1,4})(?=\s|v\d|\[|\(|$)"""),// " - 1158 " / " - 05 ["
        Regex("""\bep?(?:isode)?\s?(\d{1,4})\b"""),  // EP1158 / Episode 5 / E05
    )
    // Nº "suelto" de 2-4 dígitos: SOLO para match (se cruza contra los nº conocidos), no para agrupar
    // (evita tomar una resolución/año como episodio). Los lookarounds ya descartan "1080p"/"x264".
    private val LOOSE_NUMBER = Regex("""(?<![a-z0-9])(\d{2,4})(?![a-z0-9])""")

    // SxxEyy explícito → (temporada, episodio). Permite exigir que la temporada coincida.
    private val SEASON_EPISODE = Regex("""\bs(\d{1,2})e(\d{1,4})\b""")

    /** Pares (temporada, episodio) de los marcadores SxxEyy del nombre. */
    fun seasonEpisodePairs(name: String): List<Pair<Int, Int>> {
        val n = name.lowercase().replace('.', ' ').replace('_', ' ')
        return SEASON_EPISODE.findAll(n).mapNotNull { m ->
            val s = m.groupValues[1].toIntOrNull() ?: return@mapNotNull null
            val e = m.groupValues[2].toIntOrNull() ?: return@mapNotNull null
            s to e
        }.toList()
    }

    /** Todos los nº de episodio plausibles en el nombre (para matchear abs y relativo). */
    fun episodeNumbersIn(name: String): Set<Int> {
        val n = name.lowercase().replace('.', ' ').replace('_', ' ')
        val out = linkedSetOf<Int>()
        (STRONG_PATTERNS + LOOSE_NUMBER).forEach { p ->
            p.findAll(n).forEach { m ->
                m.groupValues.getOrNull(1)?.toIntOrNull()?.takeIf { it in 1..9999 }?.let { out += it }
            }
        }
        return out
    }

    /** Nº de episodio "principal" (primer patrón fuerte), o null si es pack/batch. Para agrupar el browse. */
    fun primaryEpisodeNumber(name: String): Int? {
        val n = name.lowercase().replace('.', ' ').replace('_', ' ')
        for (p in STRONG_PATTERNS) {
            val v = p.find(n)?.groupValues?.getOrNull(1)?.toIntOrNull()
            if (v != null && v in 1..9999) return v
        }
        return null
    }
}
