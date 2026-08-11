package com.arkiv.player.miniaturas

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * El JPEG vive en disco y no en la base: meter ~97 blobs de 100 KB en SQLite la infla 10 MB y la
 * vuelve pesada de leer, cuando lo único que hace falta guardar es dónde está la imagen.
 */
class AlmacenDeFramesTest {

    @get:Rule val temp = TemporaryFolder()

    private fun almacen() = AlmacenDeFrames(temp.newFolder("frames"))

    @Test
    fun `guardar deja el archivo con el contenido`() {
        val a = almacen()
        val f = a.guardar("web:series:tt01/1x03", byteArrayOf(1, 2, 3))
        assertTrue(f.exists())
        assertArrayEquals(byteArrayOf(1, 2, 3), f.readBytes())
    }

    /**
     * Los episodeId traen `:` y `/`, que no son válidos en un nombre de archivo. Por eso el nombre
     * se deriva por hash y no se usa el id crudo.
     */
    @Test
    fun `el nombre de archivo no arrastra los caracteres del episodeId`() {
        val a = almacen()
        val f = a.archivoDe("web:series:tt01/1x03")
        assertFalse(f.name.contains(":"))
        assertFalse(f.name.contains("/"))
        assertTrue(f.name.endsWith(".jpg"))
    }

    /** El mismo capítulo siempre al mismo archivo: es lo que hace que sobrescriba en vez de acumular. */
    @Test
    fun `guardar dos veces el mismo capitulo sobrescribe`() {
        val a = almacen()
        val primero = a.guardar("ep-1", byteArrayOf(1))
        val segundo = a.guardar("ep-1", byteArrayOf(2, 2))
        assertEquals(primero.absolutePath, segundo.absolutePath)
        assertArrayEquals(byteArrayOf(2, 2), segundo.readBytes())
        assertEquals(1, temp.root.walkTopDown().filter { it.extension == "jpg" }.count())
    }

    @Test
    fun `capitulos distintos van a archivos distintos`() {
        val a = almacen()
        assertFalse(a.archivoDe("ep-1").absolutePath == a.archivoDe("ep-2").absolutePath)
    }

    @Test
    fun `rutaSiExiste devuelve null cuando no se guardo nada`() {
        assertNull(almacen().rutaSiExiste("ep-1"))
    }

    @Test
    fun `rutaSiExiste devuelve la ruta despues de guardar`() {
        val a = almacen()
        val f = a.guardar("ep-1", byteArrayOf(9))
        assertEquals(f.absolutePath, a.rutaSiExiste("ep-1"))
    }

    @Test
    fun `borrar saca el archivo`() {
        val a = almacen()
        a.guardar("ep-1", byteArrayOf(9))
        a.borrar("ep-1")
        assertNull(a.rutaSiExiste("ep-1"))
    }

    /** Borrar algo que no está no puede explotar: pasa cada vez que se marca visto un capítulo sin frame. */
    @Test
    fun `borrar lo que no existe no falla`() {
        almacen().borrar("ep-inexistente")
    }

    /**
     * La escritura es a temporal + rename. Lo que no puede pasar es que el temporal quede en el
     * directorio: se acumularían copias del mismo frame que nadie borra nunca.
     */
    @Test
    fun `guardar no deja temporales atras`() {
        val a = almacen()
        a.guardar("ep-1", byteArrayOf(1, 2, 3))
        a.guardar("ep-1", byteArrayOf(4, 5, 6))
        val archivos = temp.root.walkTopDown().filter { it.isFile }.toList()
        assertEquals(1, archivos.size)
        assertTrue(archivos.single().name.endsWith(".jpg"))
    }

    /**
     * El destino nunca se toca hasta tener el JPEG entero: mientras se escribe, lo que sigue en la
     * ruta del capítulo es el frame ANTERIOR completo, no uno a medias. Si `guardar` escribiera
     * directo, un corte a mitad dejaría un archivo truncado que igual existe → la tarjeta quedaría
     * vacía (Coil no lo puede decodificar) en vez de caer al still de TMDB.
     */
    @Test
    fun `mientras se escribe el destino sigue teniendo el frame anterior`() {
        val a = almacen()
        val destino = a.guardar("ep-1", byteArrayOf(1, 1, 1))
        // Un temporal a medio escribir en el directorio no cambia lo que hay en el destino.
        java.io.File(destino.parentFile, "${destino.name}.123.tmp").writeBytes(byteArrayOf(9))
        assertArrayEquals(byteArrayOf(1, 1, 1), java.io.File(a.rutaSiExiste("ep-1")!!).readBytes())
    }

    /**
     * Un temporal que quedó de una escritura interrumpida no vive para siempre: la próxima captura
     * del mismo capítulo se lo lleva. Nadie más lo reclamaría — el borrado por capítulo mira el
     * `.jpg` del destino.
     */
    @Test
    fun `guardar barre los temporales que quedaron colgados`() {
        val a = almacen()
        val destino = a.guardar("ep-1", byteArrayOf(1))
        java.io.File(destino.parentFile, "${destino.name}.123.tmp").writeBytes(byteArrayOf(9))
        a.guardar("ep-1", byteArrayOf(2))
        assertEquals(1, temp.root.walkTopDown().filter { it.isFile }.count())
        assertArrayEquals(byteArrayOf(2), destino.readBytes())
    }

    /** Lo mismo al borrar el frame de un capítulo visto: no puede dejar el temporal atrás. */
    @Test
    fun `borrar tambien se lleva los temporales del capitulo`() {
        val a = almacen()
        val destino = a.guardar("ep-1", byteArrayOf(1))
        java.io.File(destino.parentFile, "${destino.name}.123.tmp").writeBytes(byteArrayOf(9))
        a.borrar("ep-1")
        assertEquals(0, temp.root.walkTopDown().filter { it.isFile }.count())
    }

    /** El wipe de logout: se va TODO, incluidos los temporales de una escritura interrumpida. */
    @Test
    fun `borrarTodo vacia el directorio`() {
        val a = almacen()
        a.guardar("ep-1", byteArrayOf(1))
        val destino = a.guardar("ep-2", byteArrayOf(2))
        java.io.File(destino.parentFile, "${destino.name}.123.tmp").writeBytes(byteArrayOf(9))
        a.borrarTodo()
        assertNull(a.rutaSiExiste("ep-1"))
        assertNull(a.rutaSiExiste("ep-2"))
        assertEquals(0, temp.root.walkTopDown().filter { it.isFile }.count())
    }

    /** Vaciar un directorio que ni siquiera se creó todavía no puede explotar. */
    @Test
    fun `borrarTodo sin directorio no falla`() {
        AlmacenDeFrames(java.io.File(temp.root, "no-existe")).borrarTodo()
    }
}
