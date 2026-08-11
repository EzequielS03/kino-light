# Miniaturas de frame — Plan de implementación (fases 0 y 1)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Capturar un frame de lo que se está reproduciendo y usarlo como miniatura del home y de la biblioteca, para que se vea en qué punto va cada capítulo.

**Architecture:** El render de libVLC pasa de SurfaceView a TextureView para poder leerlo con `getBitmap()`. Un `FrameCapturer` guarda un JPEG de 960×540 por capítulo en `filesDir/frames/`, con una fila en la tabla `episode_frame` como índice. Una función pura decide qué imagen gana en cada superficie. La sincronización entre dispositivos es la fase 2 y NO entra en este plan.

**Tech Stack:** Kotlin, Jetpack Compose, Room, libVLC 3.6.0 (`org.videolan.android:libvlc-all`), Coil, JUnit 4.

**Spec:** [`docs/superpowers/specs/2026-08-11-miniaturas-de-frame-design.md`](../specs/2026-08-11-miniaturas-de-frame-design.md)

## Global Constraints

- Todo el código, los comentarios, los nombres de test y los mensajes de commit van **en español**, como el resto del repo.
- Los mensajes de commit **nunca** llevan pie de coautoría.
- Tamaño del frame: **960×540**, JPEG **calidad 80**.
- Piso de posición para capturar: **60.000 ms**.
- Umbral de frame casi negro: luminancia media **menor a 10 sobre 255** se descarta.
- Cadencia: periódica cada **5 minutos** (solo local) y al **detenerse la reproducción** (pausa o salida del reproductor).
- Una sola imagen viva por capítulo, **sobrescrita en el lugar**.
- La base de datos está hoy en **versión 20**. Las migraciones se registran a mano en `ArkivDatabase.addMigrations(...)`.
- **Nunca** usar `git add -A`: varias sesiones comparten el working tree. Agregar siempre por ruta explícita.
- Los tests unitarios corren con `./gradlew :app:testDebugUnitTest`.

## File Structure

**Se crean:**

| Archivo | Responsabilidad |
|---|---|
| `app/src/main/java/com/arkiv/player/miniaturas/EleccionDeMiniatura.kt` | Función pura: dada la candidata de frame y sus respaldos, cuál gana. |
| `app/src/main/java/com/arkiv/player/miniaturas/GuardasDeFrame.kt` | Funciones puras: piso de posición y rechazo de frame casi negro. |
| `app/src/main/java/com/arkiv/player/miniaturas/AlmacenDeFrames.kt` | Escribe, borra y localiza el JPEG en disco. |
| `app/src/main/java/com/arkiv/player/miniaturas/FrameCapturer.kt` | Une TextureView + guardas + almacén + DAO. |
| `app/src/test/java/com/arkiv/player/miniaturas/EleccionDeMiniaturaTest.kt` | Tests de la regla de qué imagen gana. |
| `app/src/test/java/com/arkiv/player/miniaturas/GuardasDeFrameTest.kt` | Tests de las dos guardas. |
| `app/src/test/java/com/arkiv/player/miniaturas/AlmacenDeFramesTest.kt` | Tests del almacén sobre un directorio temporal. |

**Se modifican:**

| Archivo | Cambio |
|---|---|
| `app/src/main/java/com/arkiv/player/data/db/Entities.kt` | Agrega `EpisodeFrameEntity`. |
| `app/src/main/java/com/arkiv/player/data/db/Daos.kt` | Agrega `EpisodeFrameDao`; `ContinueRow` gana `framePath`. |
| `app/src/main/java/com/arkiv/player/data/db/ArkivDatabase.kt` | Versión 21, entidad, DAO y `MIGRATION_20_21`. |
| `app/src/main/java/com/arkiv/player/playback/VlcPlayer.kt` | `attachVideo` pasa a TextureView; expone el TextureView. |
| `app/src/main/java/com/arkiv/player/ui/player/PlayerViewModel.kt` | Dispara la captura (periódica y al detenerse). |
| `app/src/main/java/com/arkiv/player/ui/home/HomeScreen.kt` | Usa `EleccionDeMiniatura` en hero y "Continuar viendo". |
| `app/src/main/java/com/arkiv/player/data/ArkivRepository.kt` | Borra el frame al marcar visto. |

---

### Task 1: Medir TextureView en los dos dispositivos (COMPUERTA)

Esta tarea **no construye la funcionalidad**: decide si el resto del plan es viable. No hay tests unitarios porque lo que se mide es comportamiento de hardware.

**Files:**
- Modify (temporal, se revierte al final): `app/src/main/java/com/arkiv/player/playback/VlcPlayer.kt:832`

**Interfaces:**
- Consumes: nada.
- Produces: un veredicto escrito. Si es negativo, el plan se detiene acá.

- [ ] **Step 1: Pasar el render a TextureView**

En `VlcPlayer.attachVideo`, cambiar el último booleano (`useTextureView`) de `false` a `true`:

```kotlin
runCatching { mediaPlayer.attachViews(layout, null, true, true) }
```

- [ ] **Step 2: Compilar e instalar en el celular**

```bash
./gradlew :app:assembleDebug && adb -s R5CX7251VRM install -r app/build/outputs/apk/debug/app-debug.apk
```

- [ ] **Step 3: Reproducir y observar**

Reproducir un torrent 1080p y un título de Magis (TS). Observar durante 2 minutos: ¿hay tirones, frames saltados o audio desincronizado que antes no había? Anotar el resultado.

- [ ] **Step 4: Probar `getBitmap()` en el celular**

Agregar temporalmente en `PlayerScreen`, donde se crea el `VLCVideoLayout` (línea ~1393), un volcado a disco tras 90 segundos de reproducción:

```kotlin
// TEMPORAL — medición de la fase 0, se borra después.
fun primerTextureView(v: android.view.View): android.view.TextureView? = when (v) {
    is android.view.TextureView -> v
    is android.view.ViewGroup -> (0 until v.childCount).firstNotNullOfOrNull { primerTextureView(v.getChildAt(it)) }
    else -> null
}
val tv = primerTextureView(layout)
val bmp = tv?.getBitmap(960, 540)
android.util.Log.i("ArkivFrame", "bitmap=$bmp luminancia=${bmp?.let { b ->
    val px = IntArray(b.width * b.height); b.getPixels(px, 0, b.width, 0, 0, b.width, b.height)
    px.sumOf { p -> ((p shr 16 and 0xFF) * 77 + (p shr 8 and 0xFF) * 150 + (p and 0xFF) * 29) shr 8 } / px.size
}}")
```

Expected: `bitmap` no nulo y `luminancia` claramente mayor a 10. Si sale nulo o la luminancia es ~0, el decodificador usa buffers opacos y **el plan se cae**.

- [ ] **Step 5: Repetir los pasos 2 a 4 en el Fire TV Stick**

```bash
adb -s 192.168.1.22:5555 install -r app/build/outputs/apk/debug/app-debug.apk
```

El Fire Stick es el dispositivo más débil: si TextureView degrada en algún lado, es acá.

- [ ] **Step 6: Escribir el veredicto y revertir la instrumentación**

Borrar el bloque temporal del paso 4. Dejar el cambio de `attachViews` si el veredicto es positivo; revertirlo si es negativo.

**Si el veredicto es negativo: detener el plan** y volver al spec para adoptar el enfoque del MediaPlayer efímero (se pierde la captura periódica).

- [ ] **Step 7: Commit (solo si el veredicto es positivo)**

```bash
git add app/src/main/java/com/arkiv/player/playback/VlcPlayer.kt
git commit -m "feat(frames): el render va por TextureView para poder leer el frame

Medido en el celular y en el Fire TV Stick: la reproducción no se degrada y getBitmap()
devuelve imagen (no negro). Es la compuerta de la que dependía todo el diseño de miniaturas."
```

---

### Task 2: La regla de qué imagen gana

**Files:**
- Create: `app/src/main/java/com/arkiv/player/miniaturas/EleccionDeMiniatura.kt`
- Test: `app/src/test/java/com/arkiv/player/miniaturas/EleccionDeMiniaturaTest.kt`

**Interfaces:**
- Consumes: nada.
- Produces: `EleccionDeMiniatura.elegir(frame: String?, vararg respaldos: String?): String?`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.arkiv.player.miniaturas

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Qué imagen se muestra en una tarjeta. El frame capturado gana SOLO donde existe, y existe solo
 * si el capítulo tuvo progreso: por eso "gana solo en lo empezado" no necesita un parámetro de
 * progreso, sale de que el frame sea o no null.
 */
class EleccionDeMiniaturaTest {

    @Test
    fun `el frame le gana a todos los respaldos`() {
        assertEquals(
            "/data/frames/a.jpg",
            EleccionDeMiniatura.elegir("/data/frames/a.jpg", "https://tmdb/still.jpg", "https://cdn/caratula.jpg"),
        )
    }

    @Test
    fun `sin frame gana el primer respaldo con contenido`() {
        assertEquals(
            "https://tmdb/still.jpg",
            EleccionDeMiniatura.elegir(null, "https://tmdb/still.jpg", "https://cdn/caratula.jpg"),
        )
    }

    /** Una ruta vacía es "no hay frame", no "hay un frame que es la cadena vacía". */
    @Test
    fun `un frame en blanco se ignora y cae al respaldo`() {
        assertEquals("https://tmdb/still.jpg", EleccionDeMiniatura.elegir("", "https://tmdb/still.jpg"))
        assertEquals("https://tmdb/still.jpg", EleccionDeMiniatura.elegir("   ", "https://tmdb/still.jpg"))
    }

    /** Mismo criterio para los respaldos: se saltan los vacíos en vez de pintar nada. */
    @Test
    fun `los respaldos vacios se saltan`() {
        assertEquals("https://cdn/caratula.jpg", EleccionDeMiniatura.elegir(null, null, "", "https://cdn/caratula.jpg"))
    }

    @Test
    fun `sin nada devuelve null`() {
        assertNull(EleccionDeMiniatura.elegir(null, null, ""))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.arkiv.player.miniaturas.EleccionDeMiniaturaTest"`
Expected: FAIL — no compila, `EleccionDeMiniatura` no existe.

- [ ] **Step 3: Write minimal implementation**

```kotlin
package com.arkiv.player.miniaturas

/**
 * Qué imagen se muestra en una tarjeta: el frame capturado si lo hay, y si no el primer respaldo
 * con contenido.
 *
 * Vive acá y no en cada pantalla porque son CUATRO superficies con la misma regla y respaldos
 * distintos (hero, "Continuar viendo", lista de capítulos y tarjeta de serie). Con la regla
 * repetida en cada una, alcanza con que una quede desalineada para que la misma serie se vea
 * distinta en dos lugares de la misma pantalla.
 *
 * No recibe el progreso a propósito: el frame SOLO existe si el capítulo se empezó, así que
 * "gana solo en lo empezado" ya está implícito en que [frame] sea null o no.
 */
object EleccionDeMiniatura {

    fun elegir(frame: String?, vararg respaldos: String?): String? =
        frame?.takeIf { it.isNotBlank() }
            ?: respaldos.firstOrNull { !it.isNullOrBlank() }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.arkiv.player.miniaturas.EleccionDeMiniaturaTest"`
Expected: PASS (5 tests)

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/arkiv/player/miniaturas/EleccionDeMiniatura.kt app/src/test/java/com/arkiv/player/miniaturas/EleccionDeMiniaturaTest.kt
git commit -m "feat(frames): la regla de que imagen gana en una tarjeta"
```

---

### Task 3: Las guardas de captura

**Files:**
- Create: `app/src/main/java/com/arkiv/player/miniaturas/GuardasDeFrame.kt`
- Test: `app/src/test/java/com/arkiv/player/miniaturas/GuardasDeFrameTest.kt`

**Interfaces:**
- Consumes: nada.
- Produces:
  - `GuardasDeFrame.PISO_DE_POSICION_MS: Long` (60_000)
  - `GuardasDeFrame.LUMINANCIA_MINIMA: Int` (10)
  - `GuardasDeFrame.posicionSirve(positionMs: Long): Boolean`
  - `GuardasDeFrame.luminanciaMedia(pixeles: IntArray): Int`
  - `GuardasDeFrame.noEsCasiNegro(pixeles: IntArray): Boolean`

Las guardas trabajan sobre un `IntArray` de píxeles ARGB (lo que devuelve `Bitmap.getPixels`) y **no** sobre un `Bitmap`, para que se puedan testear con JUnit puro sin Robolectric.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.arkiv.player.miniaturas

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Las dos guardas que evitan guardar un frame inservible. Importan porque la captura SOBRESCRIBE:
 * un frame malo no se suma al bueno, lo reemplaza.
 */
class GuardasDeFrameTest {

    private fun lleno(color: Int, cuantos: Int = 100) = IntArray(cuantos) { color }

    /** Los primeros 60 s son logos de distribuidora y pantallas negras. */
    @Test
    fun `no se captura antes del piso de posicion`() {
        assertFalse(GuardasDeFrame.posicionSirve(0))
        assertFalse(GuardasDeFrame.posicionSirve(59_999))
        assertTrue(GuardasDeFrame.posicionSirve(60_000))
        assertTrue(GuardasDeFrame.posicionSirve(15 * 60_000))
    }

    @Test
    fun `la luminancia de un negro puro es cero y la de un blanco puro es 255`() {
        assertEquals(0, GuardasDeFrame.luminanciaMedia(lleno(0xFF000000.toInt())))
        assertEquals(255, GuardasDeFrame.luminanciaMedia(lleno(0xFFFFFFFF.toInt())))
    }

    /** Pausar en un fundido dejaría la tarjeta en negro, pisando el frame bueno anterior. */
    @Test
    fun `un frame negro se descarta`() {
        assertFalse(GuardasDeFrame.noEsCasiNegro(lleno(0xFF000000.toInt())))
    }

    /** Casi negro pero no del todo: una escena nocturna real tiene que pasar. */
    @Test
    fun `justo en el umbral se acepta y justo debajo se descarta`() {
        assertTrue(GuardasDeFrame.noEsCasiNegro(lleno(0xFF0A0A0A.toInt())))   // luminancia 10
        assertFalse(GuardasDeFrame.noEsCasiNegro(lleno(0xFF080808.toInt())))  // luminancia 8
    }

    /** Una escena oscura con una zona clara (una lámpara, un subtítulo) promedia por encima. */
    @Test
    fun `una escena oscura con algo de luz se acepta`() {
        val pixeles = IntArray(100) { i -> if (i < 90) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() }
        assertTrue(GuardasDeFrame.noEsCasiNegro(pixeles))
    }

    /** Sin píxeles no hay nada que juzgar: se descarta en vez de dividir por cero. */
    @Test
    fun `un arreglo vacio se descarta`() {
        assertFalse(GuardasDeFrame.noEsCasiNegro(IntArray(0)))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.arkiv.player.miniaturas.GuardasDeFrameTest"`
Expected: FAIL — no compila, `GuardasDeFrame` no existe.

- [ ] **Step 3: Write minimal implementation**

```kotlin
package com.arkiv.player.miniaturas

/**
 * Cuándo un frame NO sirve para guardar.
 *
 * Existen porque la captura sobrescribe: un frame malo no se suma al bueno, lo reemplaza. Sin
 * estas dos guardas, pausar en un fundido te deja la tarjeta en negro y encima perdés el frame
 * bueno que ya tenías.
 *
 * Trabajan sobre un IntArray de píxeles ARGB (lo que devuelve `Bitmap.getPixels`) y no sobre un
 * Bitmap para poder testearlas sin Robolectric.
 */
object GuardasDeFrame {

    /** Los primeros 60 s son logos de distribuidora y pantallas negras. Punto de partida ajustable. */
    const val PISO_DE_POSICION_MS = 60_000L

    /** Por debajo de esto (sobre 255) el frame se considera negro y no se guarda. */
    const val LUMINANCIA_MINIMA = 10

    fun posicionSirve(positionMs: Long): Boolean = positionMs >= PISO_DE_POSICION_MS

    /** Luminancia media 0..255, con los pesos enteros de siempre (77/150/29 sobre 256). */
    fun luminanciaMedia(pixeles: IntArray): Int {
        if (pixeles.isEmpty()) return 0
        var suma = 0L
        for (p in pixeles) {
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            suma += ((r * 77 + g * 150 + b * 29) shr 8).toLong()
        }
        return (suma / pixeles.size).toInt()
    }

    fun noEsCasiNegro(pixeles: IntArray): Boolean =
        pixeles.isNotEmpty() && luminanciaMedia(pixeles) >= LUMINANCIA_MINIMA
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.arkiv.player.miniaturas.GuardasDeFrameTest"`
Expected: PASS (6 tests)

Nota: `0xFFFFFFFF` da luminancia `(255*77 + 255*150 + 29*255) shr 8 = (65280) shr 8 = 255`. Si el test del blanco puro fallara por un off-by-one, el problema está en los pesos, no en el test.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/arkiv/player/miniaturas/GuardasDeFrame.kt app/src/test/java/com/arkiv/player/miniaturas/GuardasDeFrameTest.kt
git commit -m "feat(frames): guardas de piso de posicion y de frame casi negro"
```

---

### Task 4: El almacén en disco

**Files:**
- Create: `app/src/main/java/com/arkiv/player/miniaturas/AlmacenDeFrames.kt`
- Test: `app/src/test/java/com/arkiv/player/miniaturas/AlmacenDeFramesTest.kt`

**Interfaces:**
- Consumes: nada.
- Produces:
  - `class AlmacenDeFrames(private val dir: File)`
  - `AlmacenDeFrames.archivoDe(episodeId: String): File`
  - `AlmacenDeFrames.guardar(episodeId: String, jpeg: ByteArray): File`
  - `AlmacenDeFrames.rutaSiExiste(episodeId: String): String?`
  - `AlmacenDeFrames.borrar(episodeId: String)`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.arkiv.player.miniaturas

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
}
```

Agregar el import `org.junit.Assert.assertArrayEquals` junto a los otros.

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.arkiv.player.miniaturas.AlmacenDeFramesTest"`
Expected: FAIL — no compila, `AlmacenDeFrames` no existe.

- [ ] **Step 3: Write minimal implementation**

```kotlin
package com.arkiv.player.miniaturas

import java.io.File
import java.security.MessageDigest

/**
 * Dónde vive el JPEG de cada capítulo.
 *
 * Un archivo por capítulo, con el nombre derivado del episodeId por hash: los identifier traen `:`
 * y `/` (`web:series:tt01/1x03`), que no sirven como nombre de archivo. Derivarlo en vez de
 * guardarlo en una columna evita que la fila y el archivo se desincronicen.
 */
class AlmacenDeFrames(private val dir: File) {

    fun archivoDe(episodeId: String): File = File(dir, "${hash(episodeId)}.jpg")

    /** Sobrescribe: un capítulo tiene UNA imagen viva, nunca dos. */
    fun guardar(episodeId: String, jpeg: ByteArray): File {
        dir.mkdirs()
        val destino = archivoDe(episodeId)
        destino.writeBytes(jpeg)
        return destino
    }

    fun rutaSiExiste(episodeId: String): String? = archivoDe(episodeId).takeIf { it.exists() }?.absolutePath

    fun borrar(episodeId: String) {
        archivoDe(episodeId).delete()
    }

    private fun hash(s: String): String =
        MessageDigest.getInstance("SHA-1").digest(s.toByteArray())
            .joinToString("") { "%02x".format(it) }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.arkiv.player.miniaturas.AlmacenDeFramesTest"`
Expected: PASS (8 tests)

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/arkiv/player/miniaturas/AlmacenDeFrames.kt app/src/test/java/com/arkiv/player/miniaturas/AlmacenDeFramesTest.kt
git commit -m "feat(frames): almacen en disco del jpeg de cada capitulo"
```

---

### Task 5: La tabla `episode_frame`

**Files:**
- Modify: `app/src/main/java/com/arkiv/player/data/db/Entities.kt`
- Modify: `app/src/main/java/com/arkiv/player/data/db/Daos.kt`
- Modify: `app/src/main/java/com/arkiv/player/data/db/ArkivDatabase.kt`

**Interfaces:**
- Consumes: nada.
- Produces:
  - `EpisodeFrameEntity(episodeId: String, positionMs: Long, capturedAt: Long, updatedAt: Long, deleted: Int)`
  - `EpisodeFrameDao.upsert(frame: EpisodeFrameEntity)`
  - `EpisodeFrameDao.get(episodeId: String): EpisodeFrameEntity?`
  - `EpisodeFrameDao.borrar(episodeId: String)`
  - `ArkivDatabase.episodeFrameDao(): EpisodeFrameDao`

- [ ] **Step 1: Agregar la entidad**

En `Entities.kt`:

```kotlin
/**
 * El frame capturado de un capítulo. El JPEG NO está acá: vive en `filesDir/frames/` (ver
 * [com.arkiv.player.miniaturas.AlmacenDeFrames]) y esta fila es el índice.
 *
 * `updatedAt` y `deleted` existen desde el día uno aunque la fase 1 no sincronice: son el reloj y
 * el tombstone que va a usar la fase 2, y agregarlos después obligaría a otra migración.
 */
@Entity(tableName = "episode_frame")
data class EpisodeFrameEntity(
    @PrimaryKey val episodeId: String,
    /** De qué punto del capítulo es el frame. */
    val positionMs: Long,
    val capturedAt: Long,
    val updatedAt: Long = 0,
    val deleted: Int = 0,
)
```

- [ ] **Step 2: Agregar el DAO**

En `Daos.kt`:

```kotlin
@Dao
interface EpisodeFrameDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(frame: EpisodeFrameEntity)

    @Query("SELECT * FROM episode_frame WHERE episodeId = :episodeId AND deleted = 0")
    suspend fun get(episodeId: String): EpisodeFrameEntity?

    @Query("DELETE FROM episode_frame WHERE episodeId = :episodeId")
    suspend fun borrar(episodeId: String)
}
```

- [ ] **Step 3: Registrar entidad, DAO, versión y migración**

En `ArkivDatabase.kt`: agregar `EpisodeFrameEntity::class` a `entities`, subir `version = 20` a `version = 21`, agregar `abstract fun episodeFrameDao(): EpisodeFrameDao`, y agregar la migración:

```kotlin
/** v20 -> v21: miniaturas de frame capturado. El JPEG va a disco; esta tabla es el índice. */
private val MIGRATION_20_21 = object : Migration(20, 21) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS episode_frame (" +
                "episodeId TEXT NOT NULL PRIMARY KEY, " +
                "positionMs INTEGER NOT NULL, " +
                "capturedAt INTEGER NOT NULL, " +
                "updatedAt INTEGER NOT NULL DEFAULT 0, " +
                "deleted INTEGER NOT NULL DEFAULT 0)",
        )
    }
}
```

Y sumarla al final de `addMigrations(...)`, en la línea 369.

- [ ] **Step 4: Verificar que compila y que no rompió nada**

Run: `./gradlew :app:testDebugUnitTest`
Expected: PASS. Room valida el esquema al compilar; si la migración no coincide con la entidad, falla acá.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/arkiv/player/data/db/Entities.kt app/src/main/java/com/arkiv/player/data/db/Daos.kt app/src/main/java/com/arkiv/player/data/db/ArkivDatabase.kt
git commit -m "feat(frames): tabla episode_frame y migracion 20-21"
```

---

### Task 6: El capturador

**Files:**
- Create: `app/src/main/java/com/arkiv/player/miniaturas/FrameCapturer.kt`
- Modify: `app/src/main/java/com/arkiv/player/playback/VlcPlayer.kt`

**Interfaces:**
- Consumes: `GuardasDeFrame`, `AlmacenDeFrames`, `EpisodeFrameDao`.
- Produces:
  - `class FrameCapturer(almacen: AlmacenDeFrames, dao: EpisodeFrameDao, ahora: () -> Long)`
  - `suspend FrameCapturer.capturar(episodeId: String, positionMs: Long, textureView: TextureView?): Boolean` — true si guardó.
  - `VlcPlayer.textureViewActual(): TextureView?`

- [ ] **Step 1: Exponer el TextureView desde VlcPlayer**

En `VlcPlayer.kt`, guardar el layout que llega a `attachVideo` y agregar:

```kotlin
/**
 * El TextureView donde libVLC está pintando, o null si todavía no hay salida de video.
 *
 * Se busca recorriendo el árbol porque VLCVideoLayout no lo expone: sus ids son internos de la
 * librería y no hay API pública para pedírselo.
 */
fun textureViewActual(): android.view.TextureView? {
    val raiz = layoutActual ?: return null
    val pendientes = ArrayDeque<android.view.View>().apply { add(raiz) }
    while (pendientes.isNotEmpty()) {
        when (val v = pendientes.removeFirst()) {
            is android.view.TextureView -> return v
            is android.view.ViewGroup -> for (i in 0 until v.childCount) pendientes.add(v.getChildAt(i))
        }
    }
    return null
}
```

- [ ] **Step 2: Escribir el capturador**

```kotlin
package com.arkiv.player.miniaturas

import android.graphics.Bitmap
import android.view.TextureView
import com.arkiv.player.data.db.EpisodeFrameDao
import com.arkiv.player.data.db.EpisodeFrameEntity
import java.io.ByteArrayOutputStream

/**
 * Captura el frame que se está viendo y lo deja guardado.
 *
 * Best-effort de punta a punta: si algo falla —no hay TextureView, el decodificador devuelve
 * negro, el disco está lleno— no pasa nada y se conserva el frame anterior. Esto es una mejora
 * visual, jamás un motivo para molestar al que está viendo algo.
 */
class FrameCapturer(
    private val almacen: AlmacenDeFrames,
    private val dao: EpisodeFrameDao,
    private val ahora: () -> Long = { System.currentTimeMillis() },
) {
    suspend fun capturar(episodeId: String, positionMs: Long, textureView: TextureView?): Boolean {
        if (!GuardasDeFrame.posicionSirve(positionMs)) return false
        val vista = textureView ?: return false
        val bitmap = runCatching { vista.getBitmap(ANCHO, ALTO) }.getOrNull() ?: return false
        try {
            val pixeles = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixeles, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            if (!GuardasDeFrame.noEsCasiNegro(pixeles)) return false
            val salida = ByteArrayOutputStream()
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, CALIDAD, salida)) return false
            almacen.guardar(episodeId, salida.toByteArray())
            dao.upsert(
                EpisodeFrameEntity(
                    episodeId = episodeId,
                    positionMs = positionMs,
                    capturedAt = ahora(),
                    updatedAt = ahora(),
                ),
            )
            return true
        } finally {
            bitmap.recycle()
        }
    }

    private companion object {
        const val ANCHO = 960
        const val ALTO = 540
        const val CALIDAD = 80
    }
}
```

- [ ] **Step 3: Verificar que compila**

Run: `./gradlew :app:testDebugUnitTest`
Expected: PASS (los tests existentes siguen verdes).

No hay test unitario del capturador: depende de `TextureView` y `Bitmap`, que son de Android. Lo que sí tiene tests son sus dos piezas de decisión (`GuardasDeFrame`) y su persistencia (`AlmacenDeFrames`). El capturador es el pegamento y se verifica en dispositivo en la Task 8.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/arkiv/player/miniaturas/FrameCapturer.kt app/src/main/java/com/arkiv/player/playback/VlcPlayer.kt
git commit -m "feat(frames): capturador que lee el TextureView y guarda el jpeg"
```

---

### Task 7: Los disparadores en el reproductor

**Files:**
- Modify: `app/src/main/java/com/arkiv/player/AppGraph.kt`
- Modify: `app/src/main/java/com/arkiv/player/ui/player/PlayerViewModel.kt`
- Modify: `app/src/main/java/com/arkiv/player/ui/player/PlayerScreen.kt:983` y `:1220`

**Interfaces:**
- Consumes: `FrameCapturer.capturar(...)`, `VlcPlayer.textureViewActual()`.
- Produces: `PlayerViewModel.capturarFrame(episodeId: String, positionMs: Long)`

**No se inventa un bucle nuevo.** Los dos disparadores ya existen en `PlayerScreen`, con las
guardas correctas (`mediaId == epId`, `pos in 0 until dur`, que evitan escribir la posición del
capítulo viejo bajo el id del nuevo):

- `PlayerScreen.kt:983` — dentro del `LaunchedEffect(activePlayer)` que tickea cada **500 ms**;
  hoy guarda progreso con `tick % 10 == 0` (cada 5 s).
- `PlayerScreen.kt:1220` — al salir de la pantalla, justo después de `controller.pause()`. Cubre
  pausa y salida de una sola vez, que es exactamente lo que pide el spec.

- [ ] **Step 1: Construir el capturador en AppGraph**

```kotlin
val frameCapturer: FrameCapturer by lazy {
    FrameCapturer(
        almacen = AlmacenDeFrames(java.io.File(appContext.filesDir, "frames")),
        dao = database.episodeFrameDao(),
    )
}
```

- [ ] **Step 2: Exponer la captura desde el ViewModel**

En `PlayerViewModel`, al lado de `saveProgress`:

```kotlin
/**
 * Captura el frame que se está viendo. Best-effort y fuera del camino crítico: si no hay
 * TextureView o el frame no pasa las guardas, no pasa nada.
 */
fun capturarFrame(episodeId: String, positionMs: Long) {
    viewModelScope.launch {
        frameCapturer.capturar(episodeId, positionMs, vlcPlayer.textureViewActual())
    }
}
```

`vlcPlayer` es el `VlcPlayer` real (el que expone el service), no el `Player` de media3: el
TextureView lo tiene solo él.

- [ ] **Step 3: Enganchar el disparo periódico en el tick que ya existe**

En `PlayerScreen.kt:983`, junto al `saveProgress`. A 500 ms por tick, 5 minutos son **600 ticks**:

```kotlin
if (tick % 10 == 0 && epId != null && mediaId == epId && ready && activePlayer.isPlaying &&
    dur > 0 && pos in 0 until dur
) {
    vm.saveProgress(epId, pos, dur)
    // Cada 600 ticks = 5 min. Solo escribe local: su único trabajo es que un cierre abrupto no
    // pierda el punto. Va acá adentro para heredar las mismas guardas que el progreso — sin
    // `mediaId == epId` se capturaría el frame del capítulo viejo bajo el id del nuevo.
    if (tick % 600 == 0) vm.capturarFrame(epId, pos)
}
```

- [ ] **Step 4: Enganchar el disparo al salir**

En `PlayerScreen.kt:1220`, dentro del mismo `if` que ya valida el episodio:

```kotlin
if (epId != null && mediaId == epId && dur > 0 && pos in 0 until dur) {
    vm.saveProgress(epId, pos, dur)
    vm.capturarFrame(epId, pos)
}
```

Este sitio corre después de `controller.pause()`, así que cubre pausa **y** salida. Si la captura
dependiera solo de un botón de pausa, quien sale con el botón atrás nunca guardaría nada.

- [ ] **Step 5: Verificar que compila y que la suite sigue verde**

Run: `./gradlew :app:testDebugUnitTest`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/arkiv/player/AppGraph.kt app/src/main/java/com/arkiv/player/ui/player/PlayerViewModel.kt app/src/main/java/com/arkiv/player/ui/player/PlayerScreen.kt
git commit -m "feat(frames): dispara la captura cada 5 min y al salir del reproductor"
```

---

### Task 8: Pintar el frame en las cuatro superficies

**Files:**
- Modify: `app/src/main/java/com/arkiv/player/data/db/Daos.kt` (`ContinueRow` + la query de `observeContinueWatching`)
- Modify: `app/src/main/java/com/arkiv/player/ui/home/HomeScreen.kt:112` y `:151`

**Interfaces:**
- Consumes: `EleccionDeMiniatura.elegir(...)`, tabla `episode_frame`.
- Produces: `ContinueRow.framePath: String?`

- [ ] **Step 1: Que la fila traiga la ruta del frame**

`ContinueRow` gana un campo, con default para que nada más se rompa:

```kotlin
/**
 * Ruta en disco del frame capturado, o null si el capítulo todavía no tiene uno. Gana sobre
 * `stillUrl` y el resto: ver [com.arkiv.player.miniaturas.EleccionDeMiniatura].
 *
 * NO sale de la query: el nombre del archivo se deriva del episodeId por hash, así que la única
 * fuente de verdad es el disco. Lo llena el repositorio al mapear.
 */
val framePath: String? = null,
```

**La query del DAO no se toca.** El campo lo llena `ArkivRepository.observeContinueWatching`, que
ya hace un `.map` sobre las filas:

```kotlin
.map { filas -> filas.map { it.copy(framePath = almacenDeFrames.rutaSiExiste(it.episodeId)) } }
```

Sin JOIN a propósito: el archivo es la fuente de verdad y la Task 9 borra archivo y fila juntos,
así que un JOIN solo agregaría una forma de que las dos se contradigan. Son ~6 filas por emisión,
o sea seis `File.exists()`: nada.

- [ ] **Step 2: Usar la regla en el hero**

En `HomeScreen.kt:112`, reemplazar:

```kotlin
val backdrop = artwork[heroContinue.itemId]?.backdrops?.firstOrNull() ?: heroContinue.itemThumbnailUrl
```

por:

```kotlin
val backdrop = EleccionDeMiniatura.elegir(
    heroContinue.framePath,
    artwork[heroContinue.itemId]?.backdrops?.firstOrNull(),
    heroContinue.itemThumbnailUrl,
)
```

- [ ] **Step 3: Usar la regla en "Continuar viendo"**

En `HomeScreen.kt:151`, reemplazar la cadena manual por:

```kotlin
val thumb = EleccionDeMiniatura.elegir(
    row.framePath,
    row.stillUrl,
    row.thumbPath?.let { ArchiveUrls.download(row.itemId, it) },
    row.itemThumbnailUrl,
)
```

- [ ] **Step 4: Verificar en dispositivo**

Instalar, reproducir un capítulo más de 60 segundos, pausar, volver al home. Expected: la tarjeta de "Continuar viendo" y el hero muestran la escena, no el still de TMDB.

```bash
./gradlew :app:assembleDebug && adb -s R5CX7251VRM install -r app/build/outputs/apk/debug/app-debug.apk
```

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/arkiv/player/data/db/Daos.kt app/src/main/java/com/arkiv/player/ui/home/HomeScreen.kt
git commit -m "feat(frames): el home pinta el frame capturado en el hero y en continuar viendo"
```

---

### Task 9: Destruir el frame al marcar visto

**Files:**
- Modify: `app/src/main/java/com/arkiv/player/data/ArkivRepository.kt:1096` (`setWatched`)

**Interfaces:**
- Consumes: `AlmacenDeFrames.borrar(...)`, `EpisodeFrameDao.borrar(...)`.
- Produces: nada.

- [ ] **Step 1: Borrar fila y archivo al marcar visto**

En `setWatched`, cuando `watched` pasa a `true`:

```kotlin
// El frame se destruye al marcarse visto: sin esto, la carpeta crece para siempre y encima
// mostraría la escena de algo que ya terminaste, que no le sirve a nadie.
if (watched) {
    almacenDeFrames.borrar(episodeId)
    episodeFrameDao.borrar(episodeId)
}
```

- [ ] **Step 2: Verificar en dispositivo**

Marcar visto un capítulo que tenía frame y confirmar que la tarjeta vuelve al still de TMDB y que el archivo desapareció:

```bash
adb -s R5CX7251VRM shell "run-as com.arkiv.player ls files/frames/ | wc -l"
```

- [ ] **Step 3: Verificar que la suite sigue verde**

Run: `./gradlew :app:testDebugUnitTest`
Expected: PASS

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/arkiv/player/data/ArkivRepository.kt
git commit -m "feat(frames): destruye el frame al marcar el capitulo como visto"
```

---

## Lo que este plan NO cubre

- **La fase 2 (sync).** Multipart en `PocketBaseClient`, la colección `episode_frames`, la bajada perezosa y los tombstones. Es aditiva y va en su propio plan.
- **Dos de las cuatro superficies.** La lista de capítulos del detalle y la tarjeta de serie de la biblioteca quedan para un plan siguiente: la Task 8 cubre el hero y "Continuar viendo", que es donde el efecto se ve primero y donde se valida que valga la pena. `EleccionDeMiniatura` ya sirve tal cual para las otras dos.
