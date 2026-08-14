package com.arkiv.player.ui.live

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Qué hace cada flecha cuando el cajón de canales está en juego, mientras el vivo se reproduce.
 *
 * Vive fuera de la Composable a propósito: el proyecto no tiene infraestructura de tests de
 * interfaz (no hay `androidTest`), así que una regla de navegación escrita dentro del
 * `setOnKeyListener` no se puede probar de ninguna manera — y es justo el tipo de regla que se
 * rompe callada al tocar la pantalla meses después. Acá es una función pura.
 *
 * El contrato con el reproductor es la parte delicada: en vivo, arriba y abajo YA zapean
 * ([PlayerScreen] las usa para `zapAnterior`/`zapSiguiente`). Si el cajón se las quedara todas,
 * dejaría de poder zapear; si no se quedara ninguna, no se podría recorrer la lista. Por eso
 * [AccionDelDrawer.PASAR] existe: dice explícitamente "esto no es mío, que lo maneje el de abajo".
 */
class DpadDelDrawerTest {

    private val canales = FocoDelDrawer.CANALES
    private val categorias = FocoDelDrawer.CATEGORIAS

    // ---- Abrir ----

    @Test fun `con el cajon cerrado, izquierda lo abre`() {
        assertEquals(
            AccionDelDrawer.ABRIR,
            DpadDelDrawer.accion(KeyEvent.KEYCODE_DPAD_LEFT, abierto = false, foco = canales),
        )
    }

    /**
     * Y NINGUNA otra tecla lo abre. Importa que arriba/abajo sigan zapeando como hasta ahora:
     * abrir el cajón sin querer, cada vez que se cambia de canal, sería peor que no tenerlo.
     */
    @Test fun `con el cajon cerrado, el resto de las teclas no lo abren`() {
        listOf(
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_MENU,
        ).forEach {
            assertEquals(
                "tecla=$it",
                AccionDelDrawer.PASAR,
                DpadDelDrawer.accion(it, abierto = false, foco = canales),
            )
        }
    }

    // ---- Cerrar ----

    @Test fun `con el cajon abierto y el foco en los canales, derecha lo cierra`() {
        assertEquals(
            AccionDelDrawer.CERRAR,
            DpadDelDrawer.accion(KeyEvent.KEYCODE_DPAD_RIGHT, abierto = true, foco = canales),
        )
    }

    /**
     * Pero NO desde la columna de categorías: ahí a la derecha todavía hay algo -la lista de
     * canales- y saltearlo obligaría a cruzar el cajón entero de vuelta para elegir. Derecha
     * cierra cuando ya no queda nada a la derecha, que es donde el gesto se siente natural.
     */
    @Test fun `con el foco en las categorias, derecha pasa a los canales en vez de cerrar`() {
        assertEquals(
            AccionDelDrawer.A_CANALES,
            DpadDelDrawer.accion(KeyEvent.KEYCODE_DPAD_RIGHT, abierto = true, foco = categorias),
        )
    }

    @Test fun `con el foco en los canales, izquierda va a las categorias`() {
        assertEquals(
            AccionDelDrawer.A_CATEGORIAS,
            DpadDelDrawer.accion(KeyEvent.KEYCODE_DPAD_LEFT, abierto = true, foco = canales),
        )
    }

    /** Desde la columna de más a la izquierda no hay a dónde ir: se cierra, que es la salida. */
    @Test fun `con el foco en las categorias, izquierda cierra`() {
        assertEquals(
            AccionDelDrawer.CERRAR,
            DpadDelDrawer.accion(KeyEvent.KEYCODE_DPAD_LEFT, abierto = true, foco = categorias),
        )
    }

    // ---- Lo que el cajón NO se queda ----

    /**
     * Arriba y abajo con el cajón abierto son de la LISTA (recorrer canales o categorías), y las
     * maneja el foco de Compose -- no este objeto y no el zapping del reproductor. Devolver PASAR
     * acá sería devolvérselas al zapping: la lista no se movería y el canal cambiaría solo.
     */
    @Test fun `con el cajon abierto, arriba y abajo son de la lista y no del zapping`() {
        listOf(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN).forEach {
            assertEquals(
                "tecla=$it",
                AccionDelDrawer.DE_LA_LISTA,
                DpadDelDrawer.accion(it, abierto = true, foco = canales),
            )
        }
    }

    /** OK tampoco: elegir un canal lo resuelve la fila que tiene el foco, no esta regla. */
    @Test fun `con el cajon abierto, OK es de la lista`() {
        assertEquals(
            AccionDelDrawer.DE_LA_LISTA,
            DpadDelDrawer.accion(KeyEvent.KEYCODE_DPAD_CENTER, abierto = true, foco = canales),
        )
    }

    // ---- El teclado del buscador ----
    //
    // Un teclado en pantalla se recorre con las CUATRO flechas: es una grilla de letras. O sea que
    // mientras está abierto, izquierda y derecha no pueden seguir significando "cambiar de columna"
    // ni "cerrar el cajón" -- escribir "RCN" cerraría el cajón en la primera letra.

    @Test fun `con el teclado abierto, las cuatro flechas son suyas`() {
        listOf(
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
        ).forEach {
            assertEquals(
                "tecla=$it",
                AccionDelDrawer.DE_LA_LISTA,
                DpadDelDrawer.accion(it, abierto = true, foco = FocoDelDrawer.TECLADO),
            )
        }
    }

    // ---- Atrás ----

    /** Con el teclado abierto, Atrás sale del teclado -- no del cajón entero. */
    @Test fun `atras con el teclado abierto vuelve a las categorias`() {
        assertEquals(
            AccionDelDrawer.A_CATEGORIAS,
            DpadDelDrawer.accion(KeyEvent.KEYCODE_BACK, abierto = true, foco = FocoDelDrawer.TECLADO),
        )
    }

    @Test fun `atras con el cajon abierto lo cierra`() {
        listOf(canales, categorias).forEach {
            assertEquals(
                "foco=$it",
                AccionDelDrawer.CERRAR,
                DpadDelDrawer.accion(KeyEvent.KEYCODE_BACK, abierto = true, foco = it),
            )
        }
    }

    /**
     * Y con el cajón cerrado, Atrás NO es suyo: tiene que seguir saliendo del reproductor como
     * siempre. Quedárselo dejaría a la persona sin forma de salir del canal.
     */
    @Test fun `atras con el cajon cerrado no es del cajon`() {
        assertEquals(
            AccionDelDrawer.PASAR,
            DpadDelDrawer.accion(KeyEvent.KEYCODE_BACK, abierto = false, foco = canales),
        )
    }
}
