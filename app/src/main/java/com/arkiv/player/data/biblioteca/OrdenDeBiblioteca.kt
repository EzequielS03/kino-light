package com.arkiv.player.data.biblioteca

import com.arkiv.player.data.LibraryGroup
import com.arkiv.player.data.db.LibraryRow

/**
 * El orden de "Mi biblioteca": lo último que viste primero.
 *
 * La regla es `max(última reproducción, addedAt)`, en una sola lista (lo nunca visto NO va a un
 * bloque aparte): lo que acabás de ver sube al tope, y lo que acabás de agregar también, porque su
 * `addedAt` es "ahora". El `max` y no la reproducción sola es lo que evita que algo visto hace un
 * año pero re-agregado hoy quede enterrado justo cuando lo acabás de buscar.
 *
 * La "última reproducción" incluye capítulos TERMINADOS, no solo los que quedaron a medias: terminar
 * el E4 anoche tiene que dejar la serie primera hoy, con el E5 a un toque. Ese es el caso que este
 * objeto existe para resolver, y por eso no se reusa el criterio de "Continuar viendo"
 * (`observeContinueWatching`, que filtra `watched = 0`), que la tiraría del tope justo al terminar
 * el capítulo.
 *
 * Vive acá y no en el repositorio para poder testearlo sin Room, igual que [VistosDeLaBiblioteca].
 */
object OrdenDeBiblioteca {

    /**
     * [ultimas] es `itemId -> última reproducción en epoch ms`. Un ítem ausente nunca se reprodujo,
     * y ahí manda su `addedAt`.
     */
    fun recenciaDe(row: LibraryRow, ultimas: Map<String, Long>): Long =
        maxOf(ultimas[row.identifier] ?: 0L, row.addedAt)

    /**
     * Las filas crudas ordenadas (la grilla del teléfono).
     *
     * `sortedByDescending` es estable, así que dos ítems con la misma recencia conservan el orden
     * entrante, que viene `addedAt DESC` de `ItemDao.observeLibrary`.
     */
    fun filas(rows: List<LibraryRow>, ultimas: Map<String, Long>): List<LibraryRow> =
        rows.sortedByDescending { recenciaDe(it, ultimas) }

    /**
     * Los grupos ordenados (la grilla del TV). La recencia de un grupo es la de su miembro más
     * reciente: una serie guardada desde varias fuentes es UNA tarjeta, y verla por cualquiera de
     * ellas la sube entera (mismo criterio que [VistosDeLaBiblioteca.cruzar]).
     */
    fun grupos(grupos: List<LibraryGroup>, ultimas: Map<String, Long>): List<LibraryGroup> =
        grupos.sortedByDescending { g -> g.members.maxOf { recenciaDe(it, ultimas) } }
}
