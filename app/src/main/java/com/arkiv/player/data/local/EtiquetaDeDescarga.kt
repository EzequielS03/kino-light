package com.arkiv.player.data.local

/**
 * La línea de estado que la fila de un capítulo muestra bajo su nombre, o null si no hay nada que
 * decir.
 *
 * La barra y el ícono dicen lo mismo en colores y formas, pero eso solo se entiende si ya sabés qué
 * significan: el porcentaje en palabras es lo que hace que "está bajando" y "falló por esto" se
 * puedan leer sin traducir nada.
 */
object EtiquetaDeDescarga {

    fun para(estado: EstadoDeDescarga): String? = when (estado) {
        EstadoDeDescarga.SinDescargar -> null
        EstadoDeDescarga.EnCola -> "En cola"
        is EstadoDeDescarga.Bajando ->
            estado.fraccion?.let { "Bajando ${(it * 100).toInt()}%" } ?: "Bajando…"
        EstadoDeDescarga.Lista -> "Descargado"
        // El motivo real, no un "falló" pelado: es lo único que le dice al usuario si esto se
        // arregla reintentando o si no vale la pena.
        is EstadoDeDescarga.Fallida -> estado.motivo?.takeIf { it.isNotBlank() } ?: "Falló la descarga"
        EstadoDeDescarga.PideConfirmacion -> "Pesa mucho: confírmala en Descargas"
    }
}
