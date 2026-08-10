package com.arkiv.player.data.biblioteca

import com.arkiv.player.data.LibraryGroup

/**
 * Las secciones del menú lateral de "Mi biblioteca", en el orden en que se dibujan.
 *
 * Esto ES el filtro "Todas / Películas / Series" del teléfono (`LibraryScreen.LibFilter`): en el TV
 * no se duplica como chips arriba porque con el control remoto subir hasta una fila de chips y
 * volver a bajar en cada cambio de sección es un viaje largo, y dos controles para lo mismo se
 * pelean por el foco.
 *
 * `TODO_LO_GUARDADO` se llama así y no `TODO` para no chocar de lectura con `kotlin.TODO()`.
 */
enum class SeccionDeBiblioteca(val etiqueta: String) {
    TODO_LO_GUARDADO("Todo"),
    SERIES("Series"),
    PELICULAS("Películas"),
    VISTOS("Ya visto"),
    DESCARGAS("Descargas"),
}

object FiltroDeBiblioteca {

    /**
     * Los grupos que le tocan a [seccion], o **null** si esa sección no sale de la biblioteca
     * guardada.
     *
     * Null y no lista vacía a propósito: `VISTOS` y `DESCARGAS` tienen su propia fuente de datos, y
     * devolver vacío haría que un llamador equivocado dibujara "no guardaste nada" sobre una
     * sección que en realidad está llena.
     *
     * Se filtra por `primary.isMovie` y no por el grupo entero porque `LibraryGrouping.groupKeyOf`
     * ya garantiza que una película es siempre un grupo de una sola fila (nunca se agrupan: el
     * tmdbId del artwork se equivoca en películas).
     */
    fun grupos(seccion: SeccionDeBiblioteca, grupos: List<LibraryGroup>): List<LibraryGroup>? =
        when (seccion) {
            SeccionDeBiblioteca.TODO_LO_GUARDADO -> grupos
            SeccionDeBiblioteca.SERIES -> grupos.filter { !it.primary.isMovie }
            SeccionDeBiblioteca.PELICULAS -> grupos.filter { it.primary.isMovie }
            SeccionDeBiblioteca.VISTOS, SeccionDeBiblioteca.DESCARGAS -> null
        }
}
