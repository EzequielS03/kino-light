package com.arkiv.player.ui.player

/**
 * Cuándo tapar la pantalla con el spinner de carga.
 *
 * Vive afuera del Composable a propósito —mismo criterio que `DpadDelDrawer`—
 * porque la condición se evalúa en DOS sitios: el overlay que la dibuja y el log que la diagnostica.
 * Escrita dos veces se desincroniza en el primer cambio, y entonces el log deja de describir lo que
 * se está viendo, que es justo para lo que existe.
 *
 * Las cuatro razones son distintas y todas terminan en pantalla negra:
 *  - [sinPlaylist]: todavía no se resolvió qué reproducir.
 *  - [buffereando]: el player está cargando datos.
 *  - [sinPrimeraImagen]: "starts black with sound" -- the player already lets the audio
 *    through but hasn't rendered the first frame yet, and there `playbackState` is NOT
 *    BUFFERING, so without this flag the screen was left with no spinner and no picture.
 *  - [perdioLaSalidaDeVideo]: HABÍA imagen y se perdió al volver del fondo (hasta 15 s).
 *
 * [casting] anula la última: la imagen la pone la TV, no nosotros, así que esperar la salida de
 * video local no importa ni va a llegar. Las otras tres siguen aplicando casteando — mientras el
 * receptor carga, la pantalla local también tiene que explicar qué pasa.
 */
internal fun hayQueMostrarElSpinner(
    sinPlaylist: Boolean,
    buffereando: Boolean,
    sinPrimeraImagen: Boolean,
    perdioLaSalidaDeVideo: Boolean,
    casting: Boolean,
): Boolean = sinPlaylist || buffereando || sinPrimeraImagen || (perdioLaSalidaDeVideo && !casting)
