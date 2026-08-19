package com.arkiv.player.ui.player

/**
 * Qué dato curioso toca mostrar según cuánto lleva la reproducción.
 *
 * Los datos se piden TODOS DE UNA al arrancar (ver `/v1/trivia` en el gateway) y acá solo se
 * rota entre ellos. Por eso esto es aritmética pura y no una petición: cambiar de dato no puede
 * costar los 3 a 20 segundos que tarda el modelo, ni fallar a mitad de una película.
 *
 * Vive aparte del Composable a propósito: este proyecto no tiene tests de interfaz, así que una
 * regla escrita adentro del `PlayerScreen` no se podría probar de ninguna forma (mismo criterio
 * que `DpadDelDrawer`).
 */
object TriviaDelPlayer {

    /** Cada cuánto se pasa al siguiente dato. */
    const val INTERVALO_MS = 10 * 60 * 1000L

    /**
     * El índice del dato que toca, o -1 si no hay ninguno.
     *
     * Al llegar al último SE QUEDA ahí en vez de volver a empezar: repetir haría que el aviso
     * mienta -- anunciaría "hay algo nuevo" para mostrar lo mismo de hace media hora.
     */
    fun indiceEn(transcurridoMs: Long, cantidad: Int): Int {
        if (cantidad <= 0) return -1
        val pasos = (transcurridoMs.coerceAtLeast(0L) / INTERVALO_MS).toInt()
        return pasos.coerceAtMost(cantidad - 1)
    }

    /** Sin datos no se dibuja el botón: es el fallo bueno, nadie ve un error ni una espera. */
    fun hayBoton(textos: List<String>): Boolean = textos.isNotEmpty()

    /**
     * Si hay que pedirle trivia de serie o de película.
     *
     * `tipoDelItem` lo escribe el gateway al canonizar, verificado contra TMDB: cuando está, manda.
     * Cuando no -- un ítem que todavía no se canonizó --, tener número de capítulo es la mejor
     * pista que queda.
     */
    fun tipoDe(tipoDelItem: String?, episodio: Int?): String = when {
        tipoDelItem == "tv" || tipoDelItem == "movie" -> tipoDelItem
        episodio != null -> "tv"
        else -> "movie"
    }
}
