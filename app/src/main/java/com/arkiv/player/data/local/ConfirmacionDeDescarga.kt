package com.arkiv.player.data.local

/** Lo que se puede hacer con la descarga de un capítulo desde su propia fila. */
enum class AccionDeDescarga {
    /** Todavía no bajó nada: se quita la fila y el capítulo vuelve a ofrecer el botón de bajar. */
    SACAR_DE_LA_COLA,

    /** Está bajando: se detiene conservando lo ya bajado, para poder reanudar desde ahí. */
    CANCELAR,

    /** Ya está en el dispositivo: se borra el archivo. */
    BORRAR,
}

/** Los textos del diálogo que confirma una [AccionDeDescarga]. */
data class TextoDeConfirmacion(
    val titulo: String,
    val cuerpo: String,
    val confirmar: String,
    val descartar: String,
)

/**
 * Qué ofrece la fila de un capítulo según en qué va su descarga, y con qué palabras se pregunta.
 *
 * Se pregunta siempre: el control es chiquito (un slot de 48dp que comparte fila con reproducir y
 * con "marcar visto") y las tres acciones cuestan caro si se tocan sin querer — cancelar tira
 * minutos de descarga, borrar tira el archivo entero.
 */
object ConfirmacionDeDescarga {

    fun accionPara(estado: EstadoDeDescarga): AccionDeDescarga? = when (estado) {
        EstadoDeDescarga.EnCola -> AccionDeDescarga.SACAR_DE_LA_COLA
        is EstadoDeDescarga.Bajando -> AccionDeDescarga.CANCELAR
        EstadoDeDescarga.Lista -> AccionDeDescarga.BORRAR
        // Reintentar no destruye nada, así que no pregunta. Y lo que nadie encoló no tiene qué deshacer.
        is EstadoDeDescarga.Fallida, EstadoDeDescarga.SinDescargar, EstadoDeDescarga.PideConfirmacion -> null
    }

    fun texto(accion: AccionDeDescarga, nombreDelCapitulo: String?): TextoDeConfirmacion {
        val cual = if (nombreDelCapitulo.isNullOrBlank()) "El capítulo" else "«$nombreDelCapitulo»"
        return when (accion) {
            AccionDeDescarga.SACAR_DE_LA_COLA -> TextoDeConfirmacion(
                titulo = "¿Sacarla de la cola?",
                cuerpo = "$cual todavía no empezó a bajar, así que no se pierde nada.",
                confirmar = "Sacar de la cola",
                descartar = "Dejarla",
            )
            AccionDeDescarga.CANCELAR -> TextoDeConfirmacion(
                titulo = "¿Cancelar la descarga?",
                cuerpo = "Se conserva lo que ya bajó de $cual: al reintentar sigue desde ahí, no " +
                    "empieza de cero.",
                confirmar = "Cancelar descarga",
                // No puede decir "Cancelar": al lado de "Cancelar descarga" nadie sabría cuál es cuál.
                descartar = "Seguir bajando",
            )
            AccionDeDescarga.BORRAR -> TextoDeConfirmacion(
                titulo = "¿Borrar la descarga?",
                cuerpo = "$cual se borra del dispositivo. Sigue en tu biblioteca y se puede ver por " +
                    "internet.",
                confirmar = "Borrar",
                descartar = "No borrar",
            )
        }
    }
}
