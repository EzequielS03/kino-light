package com.arkiv.player.pocketbase

import java.io.IOException
import java.security.GeneralSecurityException

/**
 * Abrir unas prefs cifradas SIN que un archivo indescifrable deje la app sin arrancar.
 *
 * `EncryptedSharedPreferences` cifra el archivo con una llave del Android Keystore, y esa llave
 * NO sale del aparato. Si el archivo sobrevive pero la llave no —restaurar el respaldo en un
 * teléfono nuevo, un Keystore que se reinicializó— cada `create` tira `AEADBadTagException` y no
 * hay forma de volver atrás: ese archivo ya no lo descifra nadie, nunca.
 *
 * Como el store se toca en `ArkivApp.onCreate`, eso no era "un dato que se perdió": era la app
 * abriendo y cerrándose sola en bucle, sin más salida que borrarle los datos a mano.
 *
 * Acá la decisión es explícita: **entre perder la sesión y no poder abrir la app, se pierde la
 * sesión**. Se tira lo indescifrable y se empieza de cero; la persona vuelve a entrar.
 *
 * Es genérico en `T` para poder probarlo en la JVM: lo que se abre no le importa a esta lógica.
 */
internal object PrefsCifradas {
    fun <T> abrirOReparar(
        crear: () -> T,
        tirarLoIndescifrable: () -> Unit,
        sinCifrar: () -> T,
    ): T {
        try {
            return crear()
        } catch (e: Throwable) {
            // Un error que no es de cifrado es un bug nuestro, y un bug nuestro no puede costarle
            // la sesión a nadie: sube tal cual, sin borrar nada.
            if (!esCifradoRoto(e)) throw e
        }
        tirarLoIndescifrable()
        return try {
            crear()
        } catch (e: Throwable) {
            if (!esCifradoRoto(e)) throw e
            // Ni recién tirado abre: el Keystore mismo está mal. Prefs planas antes que un
            // teléfono donde la app no arranca -- es almacenamiento privado de la app.
            sinCifrar()
        }
    }

    /**
     * Tink a veces envuelve el fallo del Keystore, así que no alcanza con mirar la de encima.
     * El tope de saltos es por si alguna cadena de causas se muerde la cola.
     */
    private fun esCifradoRoto(t: Throwable): Boolean {
        var causa: Throwable? = t
        var saltos = 0
        while (causa != null && saltos < 16) {
            if (causa is GeneralSecurityException || causa is IOException) return true
            causa = causa.cause
            saltos++
        }
        return false
    }
}
