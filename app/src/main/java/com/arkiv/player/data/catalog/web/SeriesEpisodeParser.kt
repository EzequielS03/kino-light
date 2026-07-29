package com.arkiv.player.data.catalog.web

/** Un episodio listado en la página de una serie web (para el "pack de serie"). */
data class WebEpisode(
    val season: Int,
    val episode: Int,
    val title: String,
    val pageUrl: String,
)

/**
 * PROTOTIPO — enumera TODOS los episodios de la página de una serie web, para poder ofrecer la
 * serie como "paquete" (agregar todos los capítulos de una, como con un torrent-pack).
 *
 * Diseñado contra serieskao, cuyas series exponen anchors con un patrón limpio:
 *   <a href=".../temporada/{S}/capitulo/{E}">{E} Título del episodio</a>
 *
 * El enfoque (regex sobre los anchors) es genérico; para otras webs bastaría parametrizar el patrón
 * en `WebSourceDefinition` (mismo espíritu que `parser.rowRegex` de la búsqueda).
 */
object SeriesEpisodeParser {

    private val TAGS = Regex("<[^>]+>")
    private val WS = Regex("\\s+")
    private val LEADING_NUM = Regex("""^\s*\d+[\s.·:\-]*""") // "5 ", "5. ", "5 · " → fuera

    /**
     * Enumera los episodios de la página de una serie, según las reglas [rules] del sitio.
     * Los grupos del regex se mapean por nombre vía [WebEpisodeRules.fields]
     * (reconoce "pageUrl", "season", "episode", "title"). Devuelve únicos y ordenados por (T, E).
     */
    fun parse(baseUrl: String, html: String, rules: WebEpisodeRules): List<WebEpisode> {
        val base = baseUrl.trimEnd('/')
        val rx = runCatching {
            Regex(rules.regex, setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
        }.getOrNull() ?: return emptyList()
        val seen = HashSet<String>()
        val out = ArrayList<WebEpisode>()
        for (m in rx.findAll(html)) {
            val g = m.groupValues // g[0] = match completo; g[1..] = grupos
            fun byName(name: String): String {
                val idx = rules.fields.indexOf(name)
                return if (idx >= 0 && idx + 1 < g.size) g[idx + 1] else ""
            }
            val season = byName("season").trim().toIntOrNull() ?: continue
            val episode = byName("episode").trim().toIntOrNull() ?: continue
            val href = byName("pageUrl").trim()
            if (href.isBlank()) continue
            val url = when {
                href.startsWith("http") -> href
                href.startsWith("/") -> base + href
                else -> "$base/$href"
            }
            if (!seen.add(url)) continue // dedupe (el mismo capítulo puede aparecer 2x en la página)
            val rawTitle = WS.replace(TAGS.replace(byName("title"), " "), " ").trim()
            val title = LEADING_NUM.replace(rawTitle, "").trim()
            out.add(WebEpisode(season = season, episode = episode, title = title, pageUrl = url))
        }
        return out.sortedWith(compareBy({ it.season }, { it.episode }))
    }
}
