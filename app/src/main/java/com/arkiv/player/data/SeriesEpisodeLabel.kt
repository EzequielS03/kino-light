package com.arkiv.player.data

/** Label de capítulo guardado: "Serie · S02E05 · Título" (o sin el último segmento si no hay título). */
object SeriesEpisodeLabel {
    fun format(showTitle: String, season: Int, episode: Int, episodeName: String): String {
        val se = "S%02dE%02d".format(season, episode)
        val head = "${showTitle.trim()} · $se"
        return if (episodeName.isBlank()) head else "$head · ${episodeName.trim()}"
    }
}
