package com.arkiv.player.ui.catalog

import com.arkiv.player.data.ditu.DituItem

/**
 * El catálogo de Caracol partido en series y películas, sin duplicados.
 *
 * Compartido por la sección de Caracol del televisor ([com.arkiv.player.ui.tv.TvCaracolScreen]) y
 * la del celular ([CaracolScreen]): las dos necesitan la MISMA regla, para que un título no quede
 * en una sección en el televisor y en otra en el celular por un descuido al portarla dos veces.
 *
 * Sin duplicados por `ref()`: es la clave que usa cada tarjeta en su lista/grilla lazy, y una clave
 * repetida ahí tumba la pantalla.
 */
internal data class CaracolCatalogo(val series: List<DituItem>, val peliculas: List<DituItem>) {
    companion object {
        fun de(titulos: List<DituItem>): CaracolCatalogo {
            val (peliculas, series) = titulos.distinctBy { it.ref() }.partition { it.esPelicula }
            return CaracolCatalogo(series = series, peliculas = peliculas)
        }
    }
}
