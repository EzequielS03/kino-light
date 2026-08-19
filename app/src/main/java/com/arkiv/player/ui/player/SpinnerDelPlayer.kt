package com.arkiv.player.ui.player

/**
 * Cuándo tapar la pantalla con el spinner de carga.
 *
 * Vive afuera del Composable a propósito —mismo criterio que [TriviaDelPlayer] y `DpadDelDrawer`—
 * porque la condición se evalúa en DOS sitios: el overlay que la dibuja y el log que la diagnostica.
 * Escrita dos veces se desincroniza en el primer cambio, y entonces el log deja de describir lo que
 * se está viendo, que es justo para lo que existe.
 *
 * Las cuatro razones son distintas y todas terminan en pantalla negra:
 *  - [sinPlaylist]: todavía no se resolvió qué reproducir.
 *  - [buffereando]: el player está cargando datos.
 *  - [sinPrimeraImagen]: "arranca negro y con sonido" — libVLC ya suelta el audio pero aún no dio
 *    el primer fotograma, y ahí `playbackState` NO es BUFFERING, así que sin esta bandera la
 *    pantalla se quedaba sin spinner y sin imagen.
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
