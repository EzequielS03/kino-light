package com.arkiv.player.ui.tv

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reparto de ancho entre el teclado y los campos (Task 11, ver el KDoc de [ANCHO_TECLADO_DP] en
 * `TvFormularioConTeclado.kt`). Este proyecto no tiene infraestructura de tests de Compose para
 * medir el layout real -ver el KDoc de `entrarDesdeTv`-, así que esto es lo único que se puede
 * probar sin eso: que el teclado le gana el ancho a los campos -al revés de como estaba, con una
 * columna de 380.dp para el teclado y los campos quedándose con TODO el resto- y que los campos
 * quedan acotados, no vacíos cruzando media pantalla.
 *
 * Las DOS pantallas que comparten `TvTecladoYCampos` (el login de la TV y la oferta de vincular
 * Magis) leen estas mismas constantes: un número mal puesto acá las rompe a las dos por igual, así
 * que un solo test alcanza para las dos.
 */
class TvFormularioConTecladoTest {

    @Test fun `el teclado es mas ancho que los campos, al reves que antes del fix`() {
        assertTrue(ANCHO_TECLADO_DP > ANCHO_CAMPOS_DP)
    }

    @Test fun `el teclado quedo mas ancho que la columna fija vieja de 380`() {
        assertTrue(ANCHO_TECLADO_DP > 380)
    }

    @Test fun `los campos quedan acotados, no todo el resto de la pantalla`() {
        // El numero exacto de un TV real no importa tanto como el techo: los campos no pueden
        // llegar a cruzar media pantalla vacios, que es exactamente el bug que se esta arreglando.
        assertTrue(ANCHO_CAMPOS_DP < 960)
    }

    @Test fun `el reparto por peso conserva que el teclado se lleve mas`() {
        // El reparto REAL es por peso; los dp de arriba quedaron como referencia de la intencion.
        assertTrue(PESO_TECLADO > PESO_CAMPOS)
    }

    @Test fun `los dos anchos fijos no entraban en un televisor de referencia`() {
        // Este es el numero que se escapo en la Task 11 y que rompio la pantalla: un TV de
        // referencia son 960 dp (1920 px a densidad 320, medido en el Fire TV) y los dos anchos
        // fijos suman 1160. La fila no envuelve, asi que 200 dp de campos -con el boton de crear
        // cuenta adentro- se dibujaban fuera de la pantalla. Queda escrito para que nadie vuelva a
        // usarlos como anchos.
        assertTrue(ANCHO_TECLADO_DP + ANCHO_CAMPOS_DP > 960)
    }
}
