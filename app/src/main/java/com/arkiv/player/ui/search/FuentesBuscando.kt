package com.arkiv.player.ui.search

/**
 * Qué fuentes siguen buscando en la búsqueda de fuentes en curso.
 *
 * Antes era un solo booleano (`loadingMagis`) que se prendía al empezar y se apagaba cuando
 * terminaba la búsqueda ENTERA. Como `FuenteCompuesta` emite un solo `Done` cuando terminaron todas
 * las fuentes, "Buscando en Magis…" seguía girando hasta que Caracol contestaba, aunque Magis ya
 * hubiera traído todo. Ahora cada fuente apaga lo suyo apenas manda su `SourceDone` o su
 * `SourceError` ([terminoLaFuente]), y "Todo" gira mientras falte cualquiera.
 *
 * El fin de la búsqueda entera ([terminoTodo]) apaga todo igual: una fuente que no alcanzó a mandar
 * ninguno de los dos no puede dejar su pestaña girando para siempre.
 *
 * Las fuentes van con el nombre con que viajan en los eventos (`"magis"`, `"ditu"`), igual que en
 * [EstadoDeLasFuentes].
 */
data class FuentesBuscando(
    /** Las fuentes que ya respondieron o se cayeron. */
    val terminaron: Set<String> = emptySet(),
    /** Si la búsqueda entera ya terminó. Por defecto `true`: sin búsqueda en curso, nada gira. */
    val terminada: Boolean = true,
) {
    fun terminoLaFuente(fuente: String) = copy(terminaron = terminaron + fuente)

    fun terminoTodo() = copy(terminada = true)

    /** Si [tab] tiene que mostrar que sigue buscando. */
    fun buscando(tab: SourceTab): Boolean = when (tab) {
        SourceTab.TODO -> SourceTab.entries.any { it != SourceTab.TODO && buscando(it) }
        else -> !terminada && terminaron.none { tabDeFuente(it) == tab }
    }

    /** Si alguna fuente sigue buscando: lo mismo que "Todo". */
    val alguna: Boolean get() = buscando(SourceTab.TODO)

    companion object {
        /** Una búsqueda que arranca: todas buscando. */
        fun empezando() = FuentesBuscando(terminada = false)
    }
}
