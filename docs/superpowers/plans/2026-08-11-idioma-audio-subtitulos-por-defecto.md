# Idioma de audio y subtítulos por defecto — Plan de implementación

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Que el usuario configure en Ajustes una lista ordenada de idiomas para audio y para
subtítulos, y que el reproductor la respete — cayendo al siguiente de la lista cuando el preferido no
está en el video — en vez de quedarse con la primera pista del archivo.

**Architecture:** Toda la lógica de decisión vive en objetos **puros y testeables**
(`LangTokens`, `TrackSelector`, `SubtitleDecision`, `LangPromotion`, `LangOrderEdits`), porque
`VlcPlayer` depende de libVLC y no se puede testear en JVM. `VlcPlayer` queda como una capa fina que
consulta esos objetos. Las preferencias viajan dentro del JSON que `SubtitlePrefs` ya persiste y
sincroniza al TV, así que el sync no requiere tocar el protocolo remoto.

**Tech Stack:** Kotlin, Jetpack Compose (Material3 en celular, `androidx.tv.material3` en TV),
libVLC vía `SimpleBasePlayer`, JUnit4, Gradle.

**Spec:** `docs/superpowers/specs/2026-08-11-idioma-audio-subtitulos-por-defecto-design.md`

## Global Constraints

- **Comentarios y textos de UI en español**, igual que el resto del repo. Los KDoc explican el *por
  qué*, no el *qué* (mirá el estilo de `AudioLanguage.kt`).
- **Nunca `git add -A`**: otras sesiones de Claude comparten este working tree y hay 4 archivos
  modificados que no son de este trabajo (`TmdbApi.kt`, `CineDetailScreen.kt`, `SearchPlayback.kt`,
  `TvSearchScreen.kt`). Agregá **archivo por archivo**, con las rutas exactas de cada tarea.
- **Commits sin coautoría.** Nada de `Co-Authored-By`. Identidad ya configurada: `lordmacu`.
- **Tests verdes antes de cada commit:** `./gradlew testDebugUnitTest`.
- **No romper el sync con builds viejas:** `PlaybackPrefs.toJson()` sigue escribiendo el campo legacy
  `language` (`"off"` / `"es"`), y `fromJson` tolera JSON sin los campos nuevos.
- **`SubtitleApi.search()` no cambia de firma.** Sigue recibiendo un string de códigos separados por
  coma.

---

### Task 1: `TrackLang` — clasificador de idioma y selector genérico

Renombra el enum de audio a un nombre honesto (ahora sirve para subtítulos también), agrega la
clasificación por **nombre de archivo** (para los `.srt` inyectados) y la función de "¿este idioma
está en mi lista?" que comparte la regla del comodín del español.

**Files:**
- Create: `app/src/main/java/com/arkiv/player/playback/TrackLanguage.kt` (contenido movido desde `AudioLanguage.kt`)
- Delete: `app/src/main/java/com/arkiv/player/playback/AudioLanguage.kt`
- Modify: `app/src/main/java/com/arkiv/player/playback/VlcPlayer.kt:141,679` (solo los nombres nuevos)
- Create: `app/src/test/java/com/arkiv/player/playback/TrackLanguageTest.kt`
- Delete: `app/src/test/java/com/arkiv/player/playback/AudioLanguageTest.kt`

**Interfaces:**
- Produces:
  - `enum class TrackLang { LATINO, CASTELLANO, SPANISH, DUAL, ENGLISH, JAPANESE, UNKNOWN }`
  - `LangTokens.classify(raw: String): TrackLang`
  - `LangTokens.classifyFileName(raw: String): TrackLang`
  - `LangTokens.satisfies(lang: TrackLang, order: List<TrackLang>): Boolean`
  - `TrackSelector.select(tracks: List<Pair<Int,String>>, order: List<TrackLang>, requireChoice: Boolean = true, classifier: (String) -> TrackLang = LangTokens::classify): Int?`
  - `TrackSelector.DEFAULT_AUDIO: List<TrackLang>`

- [ ] **Step 1: Escribir el test que falla**

Creá `app/src/test/java/com/arkiv/player/playback/TrackLanguageTest.kt` con **todo** el contenido del
viejo `AudioLanguageTest.kt` (cambiando `AudioLang` → `TrackLang` y `AudioTrackSelector` →
`TrackSelector`) más estos casos nuevos:

```kotlin
package com.arkiv.player.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackLanguageTest {

    @Test fun classifyLatino() {
        assertEquals(TrackLang.LATINO, LangTokens.classify("Español Latino"))
        assertEquals(TrackLang.LATINO, LangTokens.classify("Pista 2 - LAT"))
        assertEquals(TrackLang.LATINO, LangTokens.classify("Audio Latino"))
        assertEquals(TrackLang.LATINO, LangTokens.classify("es-419"))
        assertEquals(TrackLang.LATINO, LangTokens.classify("Español (México)"))
    }

    @Test fun classifyCastellano() {
        assertEquals(TrackLang.CASTELLANO, LangTokens.classify("Castellano"))
        assertEquals(TrackLang.CASTELLANO, LangTokens.classify("Español (España)"))
        assertEquals(TrackLang.CASTELLANO, LangTokens.classify("Track 1 - [Cast]"))
    }

    @Test fun classifyGenericSpanishAndOthers() {
        assertEquals(TrackLang.SPANISH, LangTokens.classify("Track 1 - [Spanish]"))
        assertEquals(TrackLang.DUAL, LangTokens.classify("Dual"))
        assertEquals(TrackLang.ENGLISH, LangTokens.classify("Audio - [English]"))
        assertEquals(TrackLang.JAPANESE, LangTokens.classify("Japanese"))
        assertEquals(TrackLang.UNKNOWN, LangTokens.classify("Track 3"))
    }

    @Test fun selectPrefersLatinoOverCastellano() {
        val tracks = listOf(-1 to "Disable", 0 to "Castellano", 1 to "Español Latino")
        assertEquals(1, TrackSelector.select(tracks, TrackSelector.DEFAULT_AUDIO))
    }

    @Test fun selectFallsBackToCastellanoWhenNoLatino() {
        val tracks = listOf(0 to "Castellano", 1 to "English")
        assertEquals(0, TrackSelector.select(tracks, TrackSelector.DEFAULT_AUDIO))
    }

    @Test fun selectGenericSpanishSatisfiesLatinoPreference() {
        val tracks = listOf(0 to "Track 1 - [Spanish]", 1 to "Track 2 - [English]")
        assertEquals(0, TrackSelector.select(tracks, TrackSelector.DEFAULT_AUDIO))
    }

    @Test fun selectReturnsNullWhenNothingMatches() {
        val tracks = listOf(0 to "English", 1 to "French")
        assertNull(TrackSelector.select(tracks, TrackSelector.DEFAULT_AUDIO))
    }

    @Test fun selectSkipsWhenSingleTrack() {
        val tracks = listOf(-1 to "Disable", 0 to "English")
        assertNull(TrackSelector.select(tracks, TrackSelector.DEFAULT_AUDIO))
    }

    @Test fun selectRespectsCustomPreferenceOrder() {
        val tracks = listOf(0 to "Español Latino", 1 to "Castellano")
        assertEquals(1, TrackSelector.select(tracks, listOf(TrackLang.CASTELLANO, TrackLang.LATINO)))
    }

    // --- NUEVO: recorre la lista completa hasta encontrar algo que exista ---

    @Test fun selectFallsThroughOrderUntilAMatchExists() {
        val tracks = listOf(0 to "Japanese", 1 to "English")
        val order = listOf(TrackLang.LATINO, TrackLang.CASTELLANO, TrackLang.ENGLISH)
        assertEquals(1, TrackSelector.select(tracks, order))
    }

    // --- NUEVO: una sola pista SÍ se puede elegir cuando no se exige que haya opciones ---

    @Test fun selectWithoutRequireChoicePicksTheOnlyTrack() {
        val tracks = listOf(0 to "Spanish")
        assertNull(TrackSelector.select(tracks, listOf(TrackLang.SPANISH)))
        assertEquals(0, TrackSelector.select(tracks, listOf(TrackLang.SPANISH), requireChoice = false))
    }

    // --- NUEVO: clasificación por nombre de archivo (los .srt inyectados) ---

    @Test fun classifyFileNameReadsTheLanguageSuffix() {
        assertEquals(TrackLang.SPANISH, LangTokens.classifyFileName("movie.es.srt"))
        assertEquals(TrackLang.LATINO, LangTokens.classifyFileName("movie.lat.srt"))
        assertEquals(TrackLang.CASTELLANO, LangTokens.classifyFileName("movie.cast.srt"))
        assertEquals(TrackLang.JAPANESE, LangTokens.classifyFileName("movie.jpn.ass"))
    }

    /** El agujero que motivó este clasificador: `classify()` NO reconoce "en" suelto. */
    @Test fun classifyFileNameCatchesTwoLetterEnglishThatClassifyMisses() {
        assertEquals(TrackLang.UNKNOWN, LangTokens.classify("movie.en.srt"))
        assertEquals(TrackLang.ENGLISH, LangTokens.classifyFileName("movie.en.srt"))
    }

    /** Y no debe inventar inglés donde solo hay la preposición "en". */
    @Test fun classifyFileNameDoesNotFalsePositiveOnSpanishProse() {
        assertEquals(TrackLang.SPANISH, LangTokens.classifyFileName("Audio en español"))
    }

    /** libVLC decora el nombre de la pista externa; igual hay que encontrar el sufijo. */
    @Test fun classifyFileNameSurvivesVlcDecoration() {
        assertEquals(TrackLang.SPANISH, LangTokens.classifyFileName("Track 1 - [/data/x/movie.es.srt]"))
    }

    @Test fun classifyFileNameFallsBackToFreeTextWhenThereIsNoSuffix() {
        assertEquals(TrackLang.SPANISH, LangTokens.classifyFileName("Spanish.srt"))
        assertEquals(TrackLang.UNKNOWN, LangTokens.classifyFileName("subtitulo1.srt"))
    }

    // --- NUEVO: "¿está en mi lista?" con el español como familia ---

    @Test fun satisfiesTreatsAllSpanishVariantsAsOneFamily() {
        val order = listOf(TrackLang.LATINO, TrackLang.CASTELLANO)
        assertTrue(LangTokens.satisfies(TrackLang.SPANISH, order))
        assertTrue(LangTokens.satisfies(TrackLang.LATINO, order))
        assertTrue(LangTokens.satisfies(TrackLang.CASTELLANO, listOf(TrackLang.SPANISH)))
    }

    @Test fun satisfiesIsFalseForLanguagesOutsideTheList() {
        val order = listOf(TrackLang.LATINO, TrackLang.CASTELLANO)
        assertFalse(LangTokens.satisfies(TrackLang.JAPANESE, order))
        assertFalse(LangTokens.satisfies(TrackLang.ENGLISH, order))
    }

    @Test fun satisfiesMatchesNonSpanishExactly() {
        assertTrue(LangTokens.satisfies(TrackLang.ENGLISH, listOf(TrackLang.LATINO, TrackLang.ENGLISH)))
    }
}
```

Borrá `app/src/test/java/com/arkiv/player/playback/AudioLanguageTest.kt`.

- [ ] **Step 2: Correr el test para verificar que falla**

Run: `./gradlew testDebugUnitTest --tests "com.arkiv.player.playback.TrackLanguageTest"`
Expected: FAIL de compilación — `Unresolved reference: TrackLang`.

- [ ] **Step 3: Implementación**

Creá `app/src/main/java/com/arkiv/player/playback/TrackLanguage.kt` con el contenido del viejo
`AudioLanguage.kt` renombrado, más lo nuevo:

```kotlin
package com.arkiv.player.playback

/**
 * Buckets de idioma normalizados. Diccionario robado del `set_lang()` de Alfa (unify.py:504): colapsa
 * las decenas de variantes que aparecen en nombres de pista ("Track 1 - [Spanish]", "Español
 * (Latinoamérica)", "Castellano", "Dual", "es-419"…) a unos pocos buckets. Sirve para audio Y para
 * subtítulos, de ahí el nombre genérico.
 */
enum class TrackLang { LATINO, CASTELLANO, SPANISH, DUAL, ENGLISH, JAPANESE, UNKNOWN }

/** Clasificador de idioma por texto libre. Portado del diccionario de Alfa/Balandro. */
object LangTokens {
    // OJO: `\w` de Java/Kotlin NO matchea `ñ`/acentos → se usan clases explícitas [nñ] y se aceptan las
    // formas sin acento (los releases suelen quitarlos: "Espanol", "Castellano").
    private val LATINO = Regex("latinoameric|\\blatino\\b|\\blat\\b|es-?419|espa[nñ]ol\\s*lat|audio\\s*lat|m[eé]xic")
    private val CASTELLANO = Regex("castellan|castilian|espa[nñ]a\\b|\\bspain\\b|es-?es\\b|\\bcast\\b")
    private val DUAL = Regex("\\bdual\\b|multi[- ]?audio|\\bmulti\\b")
    private val ENGLISH = Regex("\\benglish\\b|\\bingl[eé]s\\b|\\beng\\b|en-?us\\b|en-?gb\\b|\\[en\\]")
    private val JAPANESE = Regex("\\bjapanese\\b|\\bjapon[eé]s\\b|\\bjap\\b|\\bjpn?\\b|\\bvose\\b|\\bvo\\b")
    private val SPANISH = Regex("espa[nñ]ol|\\bspanish\\b|\\bspa\\b|\\besp\\b|castellano|latino|\\[es\\]|\\bes-?\\d*\\b")

    /** Variantes que un hispanohablante entiende por igual: si tenés una en la lista, te sirven todas. */
    private val FAMILIA_ESPANOL = setOf(TrackLang.LATINO, TrackLang.CASTELLANO, TrackLang.SPANISH)

    /**
     * Sufijo de idioma justo antes de la extensión de subtítulo (`movie.es.srt` → `es`). Se busca con
     * la extensión pegada a propósito: aísla un token que es INEQUÍVOCAMENTE un código de idioma, y
     * por eso acá sí se pueden aceptar códigos de dos letras como `en` — buscarlos en texto libre le
     * pegaría a cualquier "Audio EN español".
     */
    // 2..4 letras: cubre `es`, `spa`, `jpn` y también `cast`, que está en CODIGOS.
    private val SUFIJO_ARCHIVO = Regex("\\.([a-z]{2,4}(?:-[a-z0-9]{2,4})?)\\.(?:srt|ass|ssa|sub|vtt)\\b")

    private val CODIGOS = mapOf(
        "es" to TrackLang.SPANISH, "spa" to TrackLang.SPANISH, "esp" to TrackLang.SPANISH,
        "lat" to TrackLang.LATINO, "es-419" to TrackLang.LATINO, "es-mx" to TrackLang.LATINO,
        "cast" to TrackLang.CASTELLANO, "es-es" to TrackLang.CASTELLANO,
        "en" to TrackLang.ENGLISH, "eng" to TrackLang.ENGLISH,
        "ja" to TrackLang.JAPANESE, "jp" to TrackLang.JAPANESE, "jpn" to TrackLang.JAPANESE,
    )

    /** Clasifica un nombre de pista / release al bucket más específico posible. */
    fun classify(raw: String): TrackLang {
        val s = raw.lowercase()
        return when {
            LATINO.containsMatchIn(s) -> TrackLang.LATINO
            CASTELLANO.containsMatchIn(s) -> TrackLang.CASTELLANO
            DUAL.containsMatchIn(s) -> TrackLang.DUAL
            SPANISH.containsMatchIn(s) -> TrackLang.SPANISH
            JAPANESE.containsMatchIn(s) -> TrackLang.JAPANESE
            ENGLISH.containsMatchIn(s) -> TrackLang.ENGLISH
            else -> TrackLang.UNKNOWN
        }
    }

    /**
     * Clasifica una pista EXTERNA por el sufijo de idioma de su nombre de archivo. libVLC nombra las
     * pistas `addSlave` con la ruta, así que sirve para los `.srt` sueltos del torrent y para los
     * bajados de OpenSubtitles. Si no hay sufijo reconocible, cae a [classify] sobre el nombre entero.
     */
    fun classifyFileName(raw: String): TrackLang {
        val s = raw.lowercase()
        SUFIJO_ARCHIVO.find(s)?.groupValues?.get(1)?.let { code -> CODIGOS[code]?.let { return it } }
        return classify(s)
    }

    /**
     * ¿[lang] cuenta como "un idioma que entiendo", dada mi lista [order]? Las variantes del español
     * son intercambiables: con `Latino > Castellano` configurado, una pista etiquetada solo "Spanish"
     * tiene que contar como propia — si no, prenderíamos subtítulos sobre un audio que se entiende.
     */
    fun satisfies(lang: TrackLang, order: List<TrackLang>): Boolean =
        lang in order || (lang in FAMILIA_ESPANOL && order.any { it in FAMILIA_ESPANOL })
}

/**
 * Selección de pista por idioma preferido — la ventaja EXCLUSIVA de Arkiv sobre Alfa/Balandro (que
 * sólo eligen idioma a nivel de FUENTE, no de pista dentro del contenedor). Sirve igual para audio y
 * para subtítulos; lo único que cambia es la lista que se le pasa.
 */
object TrackSelector {
    /** Orden por defecto para audiencia es-LatAm (coincide con animeLangPriority). */
    val DEFAULT_AUDIO = listOf(TrackLang.LATINO, TrackLang.CASTELLANO, TrackLang.DUAL)

    /**
     * Id de pista a seleccionar según [order], o null si nada coincide (→ dejar la pista por defecto
     * de VLC). Ignora la pseudo-pista "Disable" (id<0).
     *
     * [requireChoice] = true (audio): con una sola pista real no hay nada que elegir y se devuelve
     * null, para no pelearle a VLC por una decisión que no existe. false (subtítulos): un único
     * subtítulo en japonés SÍ hay que poder prenderlo.
     *
     * [classifier] permite pasar [LangTokens.classifyFileName] para pistas externas.
     */
    fun select(
        tracks: List<Pair<Int, String>>,
        order: List<TrackLang>,
        requireChoice: Boolean = true,
        classifier: (String) -> TrackLang = LangTokens::classify,
    ): Int? {
        val real = tracks.filter { it.first >= 0 }
        if (real.isEmpty()) return null
        if (requireChoice && real.size <= 1) return null
        val classed = real.map { it.first to classifier(it.second) }
        for (pref in order) {
            classed.firstOrNull { it.second == pref }?.let { return it.first }
            // El español genérico es comodín de las preferencias hispanas.
            if (pref == TrackLang.LATINO || pref == TrackLang.CASTELLANO) {
                classed.firstOrNull { it.second == TrackLang.SPANISH }?.let { return it.first }
            }
        }
        return null
    }
}
```

Borrá `app/src/main/java/com/arkiv/player/playback/AudioLanguage.kt`.

En `VlcPlayer.kt` actualizá solo los nombres (la lógica cambia en la Task 5):
- línea 141: `@Volatile var audioLangPreference: List<AudioLang> = AudioTrackSelector.DEFAULT_PREFERENCE`
  → `@Volatile var audioLangPreference: List<TrackLang> = TrackSelector.DEFAULT_AUDIO`
- línea 679: `AudioTrackSelector.select(tracks, audioLangPreference)`
  → `TrackSelector.select(tracks, audioLangPreference)`

- [ ] **Step 4: Correr los tests**

Run: `./gradlew testDebugUnitTest --tests "com.arkiv.player.playback.TrackLanguageTest"`
Expected: PASS (19 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/arkiv/player/playback/TrackLanguage.kt app/src/main/java/com/arkiv/player/playback/AudioLanguage.kt app/src/main/java/com/arkiv/player/playback/VlcPlayer.kt app/src/test/java/com/arkiv/player/playback/TrackLanguageTest.kt app/src/test/java/com/arkiv/player/playback/AudioLanguageTest.kt
git commit -m "refactor(idioma): TrackLang sirve para audio y subtitulos

Agrega classifyFileName (sufijo de idioma de los .srt inyectados, donde
'en' suelto SI es ingles) y satisfies (el espanol es una familia)."
```

---

### Task 2: `PlaybackPrefs` — persistencia, migración y códigos de OpenSubtitles

**Files:**
- Modify: `app/src/main/java/com/arkiv/player/data/subtitles/SubtitlePrefs.kt` (reescritura completa)
- Create: `app/src/test/java/com/arkiv/player/data/subtitles/PlaybackPrefsTest.kt`

**Interfaces:**
- Consumes: `TrackLang` (Task 1)
- Produces:
  - `enum class SubtitleMode { AUTO, OFF }`
  - `data class PlaybackPrefs(audioLangs, subtitleLangs, subtitleMode, sizePercent, textColor, backgroundColor, edge)`
  - `PlaybackPrefs.toJson(): String`, `PlaybackPrefs.Companion.fromJson(s: String): PlaybackPrefs?`
  - `PlaybackPrefs.openSubtitlesCodes(): String`
  - `PlaybackPrefs.Companion.EDGE_NONE / EDGE_OUTLINE / EDGE_SHADOW`
  - `class SubtitlePrefs(context)` con `val prefs: StateFlow<PlaybackPrefs>`, `fun update(p: PlaybackPrefs)`, `fun applyFromRemote(json: String)`

- [ ] **Step 1: Escribir el test que falla**

Creá `app/src/test/java/com/arkiv/player/data/subtitles/PlaybackPrefsTest.kt`:

```kotlin
package com.arkiv.player.data.subtitles

import com.arkiv.player.playback.TrackLang
import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackPrefsTest {

    @Test fun roundTripPreservesEverything() {
        val p = PlaybackPrefs(
            audioLangs = listOf(TrackLang.ENGLISH, TrackLang.LATINO),
            subtitleLangs = listOf(TrackLang.LATINO),
            subtitleMode = SubtitleMode.OFF,
            sizePercent = 140, textColor = 0xFFFFEB3B, backgroundColor = 0xCC000000, edge = 2,
        )
        assertEquals(p, PlaybackPrefs.fromJson(p.toJson()))
    }

    /** Una build vieja manda un JSON sin los campos nuevos: hay que caer a los defaults. */
    @Test fun oldJsonWithoutNewFieldsFallsBackToDefaults() {
        val viejo = """{"language":"es","sizePercent":120,"textColor":4294967295,"backgroundColor":2147483648,"edge":1}"""
        val p = PlaybackPrefs.fromJson(viejo)!!
        assertEquals(PlaybackPrefs().audioLangs, p.audioLangs)
        assertEquals(PlaybackPrefs().subtitleLangs, p.subtitleLangs)
        assertEquals(SubtitleMode.AUTO, p.subtitleMode)
        assertEquals(120, p.sizePercent)
    }

    @Test fun legacyLanguageOffMigratesToSubtitleModeOff() {
        val p = PlaybackPrefs.fromJson("""{"language":"off"}""")!!
        assertEquals(SubtitleMode.OFF, p.subtitleMode)
    }

    /** Y al revés: una build vieja tiene que seguir entendiendo lo que escribimos. */
    @Test fun toJsonStillWritesTheLegacyLanguageField() {
        assertEquals("off", org.json.JSONObject(PlaybackPrefs(subtitleMode = SubtitleMode.OFF).toJson()).getString("language"))
        assertEquals("es", org.json.JSONObject(PlaybackPrefs(subtitleMode = SubtitleMode.AUTO).toJson()).getString("language"))
    }

    @Test fun fromJsonReturnsNullOnGarbage() {
        assertEquals(null, PlaybackPrefs.fromJson("no soy json"))
    }

    // --- códigos para OpenSubtitles ---

    @Test fun spanishVariantsCollapseToASingleEsCode() {
        val p = PlaybackPrefs(subtitleLangs = listOf(TrackLang.LATINO, TrackLang.CASTELLANO, TrackLang.SPANISH))
        assertEquals("es", p.openSubtitlesCodes())
    }

    @Test fun openSubtitlesCodesKeepTheUserOrder() {
        val p = PlaybackPrefs(subtitleLangs = listOf(TrackLang.ENGLISH, TrackLang.LATINO))
        assertEquals("en,es", p.openSubtitlesCodes())
    }

    @Test fun dualIsIgnoredBecauseItIsNotATextLanguage() {
        val p = PlaybackPrefs(subtitleLangs = listOf(TrackLang.DUAL, TrackLang.JAPANESE))
        assertEquals("ja", p.openSubtitlesCodes())
    }

    /** OFF no apaga la búsqueda online: el menú CC tiene que seguir teniendo opciones. */
    @Test fun emptyOrOffStillSearchesInSpanish() {
        assertEquals("es", PlaybackPrefs(subtitleLangs = emptyList()).openSubtitlesCodes())
        assertEquals("es", PlaybackPrefs(subtitleLangs = listOf(TrackLang.DUAL)).openSubtitlesCodes())
    }
}
```

- [ ] **Step 2: Correr el test para verificar que falla**

Run: `./gradlew testDebugUnitTest --tests "com.arkiv.player.data.subtitles.PlaybackPrefsTest"`
Expected: FAIL de compilación — `Unresolved reference: PlaybackPrefs`.

- [ ] **Step 3: Implementación**

Reescribí `app/src/main/java/com/arkiv/player/data/subtitles/SubtitlePrefs.kt`:

```kotlin
package com.arkiv.player.data.subtitles

import android.content.Context
import com.arkiv.player.playback.TrackLang
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/** Qué hacer con los subtítulos al arrancar. Ver [PlaybackPrefs.subtitleLangs]. */
enum class SubtitleMode { AUTO, OFF }

/**
 * Preferencias de reproducción: idioma de audio, idioma y estilo de subtítulos. Se persiste local y
 * se sincroniza al TV. Los idiomas son LISTAS ORDENADAS: el reproductor las recorre y toma la primera
 * pista que exista, en vez de quedarse con la primera del archivo.
 */
data class PlaybackPrefs(
    val audioLangs: List<TrackLang> = listOf(TrackLang.LATINO, TrackLang.CASTELLANO, TrackLang.SPANISH, TrackLang.DUAL),
    val subtitleLangs: List<TrackLang> = listOf(TrackLang.LATINO, TrackLang.CASTELLANO, TrackLang.SPANISH),
    /** AUTO = prenderlos solo si el audio quedó FUERA de [audioLangs]. OFF = nunca solos. */
    val subtitleMode: SubtitleMode = SubtitleMode.AUTO,
    val sizePercent: Int = 100,        // 60..200
    val textColor: Long = 0xFFFFFFFF,  // ARGB
    val backgroundColor: Long = 0x80000000, // ARGB (fondo de la caja)
    val edge: Int = EDGE_OUTLINE,      // 0 none, 1 outline, 2 drop shadow
) {
    /**
     * Códigos para `SubtitleApi.search(languages=…)`, en el orden del usuario y sin repetir. Los tres
     * buckets del español colapsan a `es` a propósito: es el único código español verificado contra el
     * gateway. Nunca devuelve vacío — OFF significa "no prenderlos solos", no "no buscar".
     */
    fun openSubtitlesCodes(): String = subtitleLangs.mapNotNull {
        when (it) {
            TrackLang.LATINO, TrackLang.CASTELLANO, TrackLang.SPANISH -> "es"
            TrackLang.ENGLISH -> "en"
            TrackLang.JAPANESE -> "ja"
            TrackLang.DUAL, TrackLang.UNKNOWN -> null
        }
    }.distinct().joinToString(",").ifEmpty { "es" }

    fun toJson(): String = JSONObject()
        // Campo legacy: una build vieja lee SOLO esto y tiene que seguir funcionando.
        .put("language", if (subtitleMode == SubtitleMode.OFF) "off" else "es")
        .put("audioLangs", JSONArray(audioLangs.map { it.name }))
        .put("subtitleLangs", JSONArray(subtitleLangs.map { it.name }))
        .put("subtitleMode", subtitleMode.name)
        .put("sizePercent", sizePercent)
        .put("textColor", textColor).put("backgroundColor", backgroundColor)
        .put("edge", edge).toString()

    companion object {
        const val EDGE_NONE = 0
        const val EDGE_OUTLINE = 1
        const val EDGE_SHADOW = 2

        fun fromJson(s: String): PlaybackPrefs? = runCatching {
            val o = JSONObject(s)
            val default = PlaybackPrefs()
            PlaybackPrefs(
                audioLangs = langs(o, "audioLangs") ?: default.audioLangs,
                subtitleLangs = langs(o, "subtitleLangs") ?: default.subtitleLangs,
                // Sin campo nuevo, se migra el legacy: "off" → OFF, cualquier otra cosa → AUTO.
                subtitleMode = o.optString("subtitleMode").takeIf { it.isNotBlank() }
                    ?.let { name -> runCatching { SubtitleMode.valueOf(name) }.getOrNull() }
                    ?: if (o.optString("language") == "off") SubtitleMode.OFF else SubtitleMode.AUTO,
                sizePercent = o.optInt("sizePercent", default.sizePercent),
                textColor = o.optLong("textColor", default.textColor),
                backgroundColor = o.optLong("backgroundColor", default.backgroundColor),
                edge = o.optInt("edge", default.edge),
            )
        }.getOrNull()

        /** null si el campo no está (→ usar el default), lista si está aunque venga vacía. */
        private fun langs(o: JSONObject, key: String): List<TrackLang>? {
            val arr = o.optJSONArray(key) ?: return null
            return (0 until arr.length()).mapNotNull { i ->
                runCatching { TrackLang.valueOf(arr.getString(i)) }.getOrNull()
            }
        }
    }
}

/** Preferencias persistidas localmente (SharedPreferences), observables. */
class SubtitlePrefs(context: Context) {
    private val store = context.applicationContext.getSharedPreferences("arkiv_subs", Context.MODE_PRIVATE)

    private val _prefs = MutableStateFlow(read())
    val prefs: StateFlow<PlaybackPrefs> = _prefs.asStateFlow()

    fun update(p: PlaybackPrefs) {
        _prefs.value = p
        store.edit().putString(KEY, p.toJson()).apply()
    }

    /** Aplica preferencias recibidas del otro dispositivo (sync) sin re-emitir hacia afuera. */
    fun applyFromRemote(json: String) {
        PlaybackPrefs.fromJson(json)?.let { update(it) }
    }

    private fun read(): PlaybackPrefs =
        store.getString(KEY, null)?.let { PlaybackPrefs.fromJson(it) } ?: PlaybackPrefs()

    private companion object {
        const val KEY = "subtitle_style"
    }
}
```

> El proyecto ya no compila hasta la Task 6 (quedan referencias a `SubtitleStyle` y a `.style` en
> `SettingsScreen.kt`). Es esperado: los tests unitarios de esta tarea sí corren, porque el módulo de
> test compila el `main` completo… **si falla la compilación**, seguí igual a la Task 3 y volvé a
> correr estos tests al final de la Task 6. Para no dejar el árbol roto, hacé el cambio mínimo en
> `SettingsScreen.kt` ahora: `SubtitleStyle` → `PlaybackPrefs`, `graph.subtitlePrefs.style` →
> `graph.subtitlePrefs.prefs`, y en `SubtitleSection` cambiá la firma a `PlaybackPrefs` y reemplazá
> los dos chips de idioma por:
> ```kotlin
> Chip("Automático", style.subtitleMode == SubtitleMode.AUTO) { onChange(style.copy(subtitleMode = SubtitleMode.AUTO)) }
> Chip("Desactivado", style.subtitleMode == SubtitleMode.OFF) { onChange(style.copy(subtitleMode = SubtitleMode.OFF)) }
> ```
> La UI completa de listas llega en la Task 6. Lo mismo en `PlayerScreen.kt:1339`: cambiá
> `graph.subtitlePrefs.style.value.language` por `graph.subtitlePrefs.prefs.value.openSubtitlesCodes()`
> y borrá la línea `val langs = if (prefLang.isBlank() …) "es" else prefLang`, pasando ese valor
> directo a `languages =`.

- [ ] **Step 4: Correr los tests**

Run: `./gradlew testDebugUnitTest`
Expected: PASS — los 9 de `PlaybackPrefsTest` y los 19 de `TrackLanguageTest`.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/arkiv/player/data/subtitles/SubtitlePrefs.kt app/src/test/java/com/arkiv/player/data/subtitles/PlaybackPrefsTest.kt app/src/main/java/com/arkiv/player/ui/settings/SettingsScreen.kt app/src/main/java/com/arkiv/player/ui/player/PlayerScreen.kt
git commit -m "feat(prefs): PlaybackPrefs con listas ordenadas de idioma

Migra el campo legacy language y lo sigue escribiendo, para que el sync
con una build vieja del TV no se rompa en ninguna direccion."
```

---

### Task 3: `SubtitleDecision` — la regla de cuándo se prenden

**Files:**
- Create: `app/src/main/java/com/arkiv/player/playback/SubtitleDecision.kt`
- Create: `app/src/test/java/com/arkiv/player/playback/SubtitleDecisionTest.kt`

**Interfaces:**
- Consumes: `TrackLang`, `LangTokens`, `TrackSelector` (Task 1); `PlaybackPrefs`, `SubtitleMode` (Task 2)
- Produces: `SubtitleDecision.decide(audioTrackName: String?, spuTracks: List<Pair<Int,String>>, prefs: PlaybackPrefs): Int` — id de pista SPU a activar; `-1` = apagados.

- [ ] **Step 1: Escribir el test que falla**

```kotlin
package com.arkiv.player.playback

import com.arkiv.player.data.subtitles.PlaybackPrefs
import com.arkiv.player.data.subtitles.SubtitleMode
import org.junit.Assert.assertEquals
import org.junit.Test

class SubtitleDecisionTest {

    private val prefs = PlaybackPrefs(
        audioLangs = listOf(TrackLang.LATINO, TrackLang.CASTELLANO),
        subtitleLangs = listOf(TrackLang.LATINO, TrackLang.CASTELLANO, TrackLang.SPANISH),
    )
    private val subs = listOf(-1 to "Disable", 0 to "English", 1 to "Spanish")

    @Test fun foreignAudioTurnsSubtitlesOnInThePreferredLanguage() {
        assertEquals(1, SubtitleDecision.decide("Japanese", subs, prefs))
    }

    @Test fun audioInMyLanguageLeavesSubtitlesOff() {
        assertEquals(-1, SubtitleDecision.decide("Español Latino", subs, prefs))
    }

    /** El comodín del español: "Spanish" a secas cuenta como propio con Latino>Castellano. */
    @Test fun genericSpanishAudioCountsAsMine() {
        assertEquals(-1, SubtitleDecision.decide("Track 1 - [Spanish]", subs, prefs))
    }

    /** Si agregaste inglés a tu lista de audio, el inglés deja de prender subs. */
    @Test fun audioInAnAddedLanguageAlsoCountsAsMine() {
        val conIngles = prefs.copy(audioLangs = prefs.audioLangs + TrackLang.ENGLISH)
        assertEquals(-1, SubtitleDecision.decide("English", subs, conIngles))
    }

    /** Pista sin etiqueta: se asume que es tu idioma. Prender subs porque sí sería peor. */
    @Test fun unknownAudioLeavesSubtitlesOff() {
        assertEquals(-1, SubtitleDecision.decide("Track 1", subs, prefs))
        assertEquals(-1, SubtitleDecision.decide(null, subs, prefs))
    }

    @Test fun offModeNeverTurnsThemOn() {
        val off = prefs.copy(subtitleMode = SubtitleMode.OFF)
        assertEquals(-1, SubtitleDecision.decide("Japanese", subs, off))
    }

    @Test fun foreignAudioWithNoSubtitleInMyLanguagesStaysOff() {
        val soloFrances = listOf(-1 to "Disable", 0 to "French")
        assertEquals(-1, SubtitleDecision.decide("Japanese", soloFrances, prefs))
    }

    /** Una única pista de subtítulo SÍ se prende (a diferencia del audio, acá no se exige elección). */
    @Test fun aSingleMatchingSubtitleIsSelected() {
        val unaSola = listOf(0 to "Spanish")
        assertEquals(0, SubtitleDecision.decide("Japanese", unaSola, prefs))
    }

    /** Los .srt inyectados se clasifican por el sufijo del nombre de archivo. */
    @Test fun injectedSrtIsPickedByItsFileNameSuffix() {
        val externos = listOf(0 to "/data/x/movie.en.srt", 1 to "/data/x/movie.es.srt")
        assertEquals(1, SubtitleDecision.decide("Japanese", externos, prefs))
    }

    @Test fun noSubtitleTracksAtAllStaysOff() {
        assertEquals(-1, SubtitleDecision.decide("Japanese", listOf(-1 to "Disable"), prefs))
    }
}
```

- [ ] **Step 2: Correr el test para verificar que falla**

Run: `./gradlew testDebugUnitTest --tests "com.arkiv.player.playback.SubtitleDecisionTest"`
Expected: FAIL de compilación — `Unresolved reference: SubtitleDecision`.

- [ ] **Step 3: Implementación**

```kotlin
package com.arkiv.player.playback

import com.arkiv.player.data.subtitles.PlaybackPrefs
import com.arkiv.player.data.subtitles.SubtitleMode

/**
 * Decide qué pista de subtítulo activar, en una función PURA para poder testearla — `VlcPlayer`
 * depende de libVLC y no corre en la JVM.
 *
 * La regla: los subtítulos se prenden solo si el idioma que quedó SONANDO no está en tu lista de
 * audio. Si el audio ya está en un idioma que entendés, subtitularlo sobra.
 */
object SubtitleDecision {

    /** Id de pista SPU a activar. `-1` = apagados. */
    fun decide(
        audioTrackName: String?,
        spuTracks: List<Pair<Int, String>>,
        prefs: PlaybackPrefs,
    ): Int {
        if (prefs.subtitleMode == SubtitleMode.OFF) return APAGADO
        val audioLang = audioTrackName?.let { LangTokens.classify(it) } ?: TrackLang.UNKNOWN
        // Pista sin etiqueta ("Track 1"): asumir que es tu idioma. Lo contrario haría aparecer
        // subtítulos en cualquier película normal cuyo MKV no etiquete el audio.
        if (audioLang == TrackLang.UNKNOWN) return APAGADO
        if (LangTokens.satisfies(audioLang, prefs.audioLangs)) return APAGADO
        // Audio extranjero → buscar subtítulo. requireChoice=false: un único subtítulo en tu idioma
        // hay que prenderlo igual, aunque no haya nada más entre qué elegir.
        return TrackSelector.select(
            tracks = spuTracks,
            order = prefs.subtitleLangs,
            requireChoice = false,
            classifier = LangTokens::classifyFileName,
        ) ?: APAGADO
    }

    const val APAGADO = -1
}
```

- [ ] **Step 4: Correr los tests**

Run: `./gradlew testDebugUnitTest --tests "com.arkiv.player.playback.SubtitleDecisionTest"`
Expected: PASS (10 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/arkiv/player/playback/SubtitleDecision.kt app/src/test/java/com/arkiv/player/playback/SubtitleDecisionTest.kt
git commit -m "feat(subs): regla pura de cuando prender los subtitulos

Se prenden solo si el audio que quedo no esta en tu lista de idiomas."
```

---

### Task 4: `LangPromotion` y `LangOrderEdits` — promoción y reordenamiento

`LangPromotion` decide si una elección manual en el player merece volverse el nuevo defecto.
`LangOrderEdits` es la lógica de las flechas ▲▼ y el check, compartida por la UI del celular y la
del TV (que no pueden compartir composables porque usan librerías de Material distintas).

**Files:**
- Create: `app/src/main/java/com/arkiv/player/playback/LangPromotion.kt`
- Create: `app/src/test/java/com/arkiv/player/playback/LangPromotionTest.kt`

**Interfaces:**
- Consumes: `TrackLang`, `LangTokens` (Task 1)
- Produces:
  - `LangPromotion.promote(order: List<TrackLang>, pickedName: String, allNames: List<String>, classifier: (String) -> TrackLang = LangTokens::classify): List<TrackLang>?`
  - `LangOrderEdits.toggle(order: List<TrackLang>, lang: TrackLang): List<TrackLang>`
  - `LangOrderEdits.moveUp(order: List<TrackLang>, lang: TrackLang): List<TrackLang>`
  - `LangOrderEdits.moveDown(order: List<TrackLang>, lang: TrackLang): List<TrackLang>`

- [ ] **Step 1: Escribir el test que falla**

```kotlin
package com.arkiv.player.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LangPromotionTest {

    private val orden = listOf(TrackLang.LATINO, TrackLang.CASTELLANO, TrackLang.ENGLISH)

    @Test fun pickingAnotherLanguageMovesItToTheTop() {
        val todas = listOf("Español Latino", "English")
        assertEquals(
            listOf(TrackLang.ENGLISH, TrackLang.LATINO, TrackLang.CASTELLANO),
            LangPromotion.promote(orden, "English", todas),
        )
    }

    /** La mitigación clave: sin alternativa no hubo elección, así que no dice nada de tu gusto. */
    @Test fun aFileWithASingleLanguageNeverPromotes() {
        assertNull(LangPromotion.promote(orden, "English", listOf("English")))
        assertNull(LangPromotion.promote(orden, "English", listOf("English", "Audio - [English]")))
    }

    @Test fun anUnknownBucketNeverPromotes() {
        assertNull(LangPromotion.promote(orden, "Track 3", listOf("Track 3", "English")))
    }

    @Test fun pickingWhatIsAlreadyOnTopChangesNothing() {
        assertNull(LangPromotion.promote(orden, "Español Latino", listOf("Español Latino", "English")))
    }

    @Test fun promotingALanguageNotInTheListAddsIt() {
        val todas = listOf("Japanese", "English")
        assertEquals(
            listOf(TrackLang.JAPANESE, TrackLang.LATINO, TrackLang.CASTELLANO, TrackLang.ENGLISH),
            LangPromotion.promote(orden, "Japanese", todas),
        )
    }

    @Test fun subtitlesUseTheFileNameClassifier() {
        val todas = listOf("/x/movie.es.srt", "/x/movie.en.srt")
        assertEquals(
            listOf(TrackLang.ENGLISH, TrackLang.LATINO, TrackLang.CASTELLANO),
            LangPromotion.promote(orden, "/x/movie.en.srt", todas, LangTokens::classifyFileName),
        )
    }
}

class LangOrderEditsTest {

    private val orden = listOf(TrackLang.LATINO, TrackLang.CASTELLANO, TrackLang.ENGLISH)

    @Test fun toggleRemovesWhenPresentAndAppendsWhenNot() {
        assertEquals(listOf(TrackLang.LATINO, TrackLang.ENGLISH), LangOrderEdits.toggle(orden, TrackLang.CASTELLANO))
        assertEquals(orden + TrackLang.JAPANESE, LangOrderEdits.toggle(orden, TrackLang.JAPANESE))
    }

    /** No se puede quedar sin idiomas: el último no se saca. */
    @Test fun toggleRefusesToEmptyTheList() {
        val uno = listOf(TrackLang.LATINO)
        assertEquals(uno, LangOrderEdits.toggle(uno, TrackLang.LATINO))
    }

    @Test fun moveUpAndDownSwapNeighbours() {
        assertEquals(
            listOf(TrackLang.CASTELLANO, TrackLang.LATINO, TrackLang.ENGLISH),
            LangOrderEdits.moveUp(orden, TrackLang.CASTELLANO),
        )
        assertEquals(
            listOf(TrackLang.CASTELLANO, TrackLang.LATINO, TrackLang.ENGLISH),
            LangOrderEdits.moveDown(orden, TrackLang.LATINO),
        )
    }

    @Test fun movingPastTheEdgesOrMovingAnAbsentLanguageIsANoOp() {
        assertEquals(orden, LangOrderEdits.moveUp(orden, TrackLang.LATINO))
        assertEquals(orden, LangOrderEdits.moveDown(orden, TrackLang.ENGLISH))
        assertEquals(orden, LangOrderEdits.moveUp(orden, TrackLang.JAPANESE))
    }
}
```

- [ ] **Step 2: Correr el test para verificar que falla**

Run: `./gradlew testDebugUnitTest --tests "com.arkiv.player.playback.LangPromotionTest" --tests "com.arkiv.player.playback.LangOrderEditsTest"`
Expected: FAIL de compilación — `Unresolved reference: LangPromotion`.

- [ ] **Step 3: Implementación**

```kotlin
package com.arkiv.player.playback

/**
 * Elegir una pista a mano en el player sube ese idioma al tope de la preferencia — pero SOLO si hubo
 * una elección real. Sin esta mitigación, una película que venía únicamente en inglés te dejaría el
 * inglés arriba para siempre y la próxima película dual arrancaría en inglés sin que lo pidieras.
 */
object LangPromotion {

    /** Nuevo orden con el idioma elegido al tope, o null si no corresponde promover. */
    fun promote(
        order: List<TrackLang>,
        pickedName: String,
        allNames: List<String>,
        classifier: (String) -> TrackLang = LangTokens::classify,
    ): List<TrackLang>? {
        val picked = classifier(pickedName)
        if (picked == TrackLang.UNKNOWN) return null
        // ¿Había alternativa? Con un solo idioma en el archivo, elegirlo no es una preferencia.
        if (allNames.map(classifier).distinct().size < 2) return null
        if (order.firstOrNull() == picked) return null
        return listOf(picked) + order.filter { it != picked }
    }
}

/**
 * Ediciones de la lista ordenada de idiomas. Vive acá, puro y testeado, porque el celular
 * (`material3`) y el TV (`androidx.tv.material3`) no pueden compartir composables pero sí tienen que
 * comportarse igual.
 */
object LangOrderEdits {

    /** Agrega al final si no está; lo saca si está. Nunca deja la lista vacía. */
    fun toggle(order: List<TrackLang>, lang: TrackLang): List<TrackLang> = when {
        lang !in order -> order + lang
        order.size <= 1 -> order
        else -> order - lang
    }

    fun moveUp(order: List<TrackLang>, lang: TrackLang): List<TrackLang> = swap(order, lang, -1)

    fun moveDown(order: List<TrackLang>, lang: TrackLang): List<TrackLang> = swap(order, lang, +1)

    private fun swap(order: List<TrackLang>, lang: TrackLang, delta: Int): List<TrackLang> {
        val i = order.indexOf(lang)
        val j = i + delta
        if (i < 0 || j !in order.indices) return order
        return order.toMutableList().apply { this[i] = this[j].also { this[j] = this[i] } }
    }
}
```

- [ ] **Step 4: Correr los tests**

Run: `./gradlew testDebugUnitTest --tests "com.arkiv.player.playback.LangPromotionTest" --tests "com.arkiv.player.playback.LangOrderEditsTest"`
Expected: PASS (10 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/arkiv/player/playback/LangPromotion.kt app/src/test/java/com/arkiv/player/playback/LangPromotionTest.kt
git commit -m "feat(idioma): promocion a defecto solo si hubo eleccion real

Un archivo con un solo idioma no promociona nada: sin alternativa, elegir
no expresa preferencia."
```

---

### Task 5: Cablear `VlcPlayer` — aplicar las preferencias de verdad

Acá se arregla el bug del subtítulo colado y se conecta la preferencia del usuario al reproductor.

**Files:**
- Modify: `app/src/main/java/com/arkiv/player/playback/VlcPlayer.kt:130-142, 189-200, 661-684, 921-929`
- Modify: `app/src/main/java/com/arkiv/player/playback/PlaybackService.kt:61`

**Interfaces:**
- Consumes: `SubtitleDecision.decide(...)` (Task 3), `TrackSelector.select(...)` (Task 1), `PlaybackPrefs` (Task 2)
- Produces:
  - `VlcPlayer.langPrefs: PlaybackPrefs` (`@Volatile var`, reemplaza a `audioLangPreference`)
  - `VlcPlayer.addSubtitleSlave(uri: Uri, byUser: Boolean = true)`

No lleva test unitario: libVLC no corre en la JVM. La verificación es en device (Step 5).

- [ ] **Step 1: Reemplazar la preferencia hardcodeada**

En `VlcPlayer.kt`, sustituí la línea 141 y su comentario (130-142) por:

```kotlin
    // Subtítulos: NO se fuerzan en OFF a ciegas. Se decide por idioma (ver SubtitleDecision) apenas
    // las pistas pueblan — que es DESPUÉS de Playing, no en Playing. El apagado anterior reintentaba
    // 4 veces en ~1,5 s y, cuando VLC poblaba más tarde, ya se había rendido y se colaba la primera
    // pista. Ahora se re-afirma la DECISIÓN durante ~3 s. `userTouchedSpu` corta todo si el usuario
    // eligió un subtítulo a mano. Ambos flags se resetean al cargar otro ítem.
    private var defaultSpuApplied = false
    private var userTouchedSpu = false
    // Preferencias de idioma del usuario (audio y subtítulos). Las mantiene al día PlaybackService
    // observando el StateFlow de SubtitlePrefs, así un cambio en Ajustes (o sincronizado desde el
    // celular) pega en la próxima reproducción sin reiniciar nada.
    @Volatile var langPrefs: PlaybackPrefs = PlaybackPrefs()
    private var defaultAudioApplied = false
```

Agregá el import `import com.arkiv.player.data.subtitles.PlaybackPrefs`.

- [ ] **Step 2: Reemplazar el apagado ciego por la decisión por idioma**

Sustituí `applyDefaultSpuOff` (líneas 661-671) y ajustá `applyPreferredAudio`:

```kotlin
    /**
     * Aplica la decisión de subtítulos de [SubtitleDecision] (idioma preferido, o apagado si el audio
     * ya quedó en un idioma tuyo). Se re-afirma con reintentos porque libVLC auto-activa la primera
     * pista embebida en cuanto puebla — poco DESPUÉS de Playing — y hay que ganarle esa carrera.
     * Se detiene apenas el usuario elige un subtítulo a mano ([userTouchedSpu]).
     */
    private fun applyPreferredSpu(retries: Int) {
        if (userTouchedSpu) return
        val spu = vlcSpuTracks()
        val audioName = vlcAudioTracks().firstOrNull { it.first == currentAudioTrack() }?.second
        val target = SubtitleDecision.decide(audioName, spu, langPrefs)
        if (currentSpuTrack() != target) {
            runCatching { android.util.Log.w("ArkivVlc", "auto-spu -> id=$target de ${spu.map { it.second }}") }
            runCatching { mediaPlayer.spuTrack = target }
        }
        if (retries > 0) handler.postDelayed({ applyPreferredSpu(retries - 1) }, 350)
    }

    private fun applyPreferredAudio(retries: Int) {
        val tracks = vlcAudioTracks()
        if (tracks.count { it.first >= 0 } <= 1) {
            if (retries > 0) handler.postDelayed({ applyPreferredAudio(retries - 1) }, 400)
            return
        }
        val id = TrackSelector.select(tracks, langPrefs.audioLangs) ?: return
        if (id != currentAudioTrack()) {
            runCatching { android.util.Log.w("ArkivVlc", "auto-audio -> id=$id de ${tracks.map { it.second }}") }
            setVlcAudioTrack(id)
        }
    }
```

En el evento `Playing` (líneas 189-194) cambiá la llamada, y **subí el retraso a 400 ms** para que el
audio ya esté elegido cuando se decida el subtítulo (la decisión depende de qué audio quedó):

```kotlin
                    if (!defaultSpuApplied) {
                        defaultSpuApplied = true
                        // Después del audio (200 ms): la decisión depende de qué pista quedó sonando.
                        // 8 reintentos × 350 ms ≈ 3 s, que es lo que tarda VLC en poblar en el peor caso.
                        handler.postDelayed({ applyPreferredSpu(retries = 8) }, 400)
                    }
```

- [ ] **Step 3: Que los `.srt` automáticos no se prendan solos**

Sustituí `addSubtitleSlave` (líneas 921-929) por:

```kotlin
    /**
     * Agrega una pista de subtítulo externa. [byUser] distingue la elección del usuario (OpenSubtitles)
     * de la carga automática de los `.srt` sueltos del torrent: la automática NO debe activarse sola
     * (`select = false`) ni cortar la selección por idioma — al contrario, la pista nueva entra como
     * candidata y `applyPreferredSpu` la elige si está en tu idioma.
     *
     * OJO con el MPEG-TS: esto solo es seguro porque magis se demuxea con avformat (ver loadMedia).
     * Con el demuxer `ts` nativo, adjuntar un subtítulo externo le cambia a libVLC el programa activo
     * y se lleva puestas TODAS las pistas del stream.
     */
    fun addSubtitleSlave(uri: Uri, byUser: Boolean = true) {
        if (byUser) userTouchedSpu = true
        runCatching { mediaPlayer.addSlave(IMedia.Slave.Type.Subtitle, uri, byUser) }
        // Recién cargada, la pista todavía no figura: se re-decide un instante después.
        if (!byUser) handler.postDelayed({ applyPreferredSpu(retries = 2) }, 300)
        // NO corregir acá el desfase de la ventana con `spuDelay`. Se probó y congela la
        // reproducción: con un desfase de −29 min VLC se queda clavado en `pos=0` con el buffer
        // subiendo de a gotas hasta que salta el rescate de "estancado en 0" (medido en device,
        // dos veces seguidas). Pedirle un subtítulo casi media hora antes de su marca le rompe el
        // reloj de entrada. El desfase hay que sacarlo de raíz: que magis no abra por ventana.
    }
```

- [ ] **Step 4: Mantener el player al día con los Ajustes**

En `PlaybackService.kt`, justo después de `PlaybackEngine.vlc = player` (línea 62):

```kotlin
        // El player vive en el servicio, así que se suscribe él mismo a las preferencias: un cambio
        // en Ajustes —o sincronizado desde el celular— llega sin tener que reiniciar la reproducción.
        val graph = com.arkiv.player.AppGraph.from(this)
        graph.applicationScope.launch {
            graph.subtitlePrefs.prefs.collect { player.langPrefs = it }
        }
```

Agregá el import `import kotlinx.coroutines.launch` si no está.

- [ ] **Step 5: Compilar y verificar en device**

Run: `./gradlew testDebugUnitTest assembleDebug`
Expected: BUILD SUCCESSFUL.

Instalá en el celular (ver la nota de ADB WiFi) y verificá con `adb logcat -s ArkivVlc`:
1. Una película **dual latino/inglés** → log `auto-audio -> id=…` con la pista latina, subtítulos apagados.
2. Un **anime en japonés con subs en español** → `auto-spu -> id=…` con la pista española y los subs visibles.
3. Una película **en español** → `auto-spu -> id=-1`, sin subtítulos colados en ningún momento del
   primer minuto (este es el bug viejo: miralo unos segundos, no solo al arrancar).

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/arkiv/player/playback/VlcPlayer.kt app/src/main/java/com/arkiv/player/playback/PlaybackService.kt
git commit -m "feat(player): aplicar el idioma preferido de audio y subtitulos

Reemplaza el apagado ciego de subtitulos por una decision por idioma que
se re-afirma ~3 s: antes se rendia a los 1,5 s y se colaba la primera
pista cuando VLC poblaba tarde. Los .srt del torrent ya no se autoactivan."
```

---

### Task 6: Ajustes del celular — editor de listas de idioma

**Files:**
- Create: `app/src/main/java/com/arkiv/player/ui/settings/LanguageOrderEditor.kt`
- Modify: `app/src/main/java/com/arkiv/player/ui/settings/SettingsScreen.kt:208-241` (`SubtitleSection`)

**Interfaces:**
- Consumes: `TrackLang` (Task 1), `LangOrderEdits` (Task 4), `PlaybackPrefs`/`SubtitleMode` (Task 2)
- Produces:
  - `@Composable fun LanguageOrderEditor(title: String, options: List<TrackLang>, order: List<TrackLang>, onChange: (List<TrackLang>) -> Unit)`
  - `fun TrackLang.etiqueta(): String` (público, lo reusa el TV en la Task 7)
  - `val IDIOMAS_AUDIO: List<TrackLang>`, `val IDIOMAS_SUBTITULO: List<TrackLang>`

- [ ] **Step 1: Crear el editor**

```kotlin
package com.arkiv.player.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.arkiv.player.playback.LangOrderEdits
import com.arkiv.player.playback.TrackLang
import com.arkiv.player.ui.theme.ArkivRed
import com.arkiv.player.ui.theme.ArkivTextSecondary

/** Idiomas ofrecidos para audio. `DUAL` aplica a pistas multi-audio de los releases. */
val IDIOMAS_AUDIO = listOf(
    TrackLang.LATINO, TrackLang.CASTELLANO, TrackLang.SPANISH,
    TrackLang.DUAL, TrackLang.ENGLISH, TrackLang.JAPANESE,
)

/** Para subtítulos no se ofrece `DUAL`: no existe una pista de texto "dual". */
val IDIOMAS_SUBTITULO = listOf(
    TrackLang.LATINO, TrackLang.CASTELLANO, TrackLang.SPANISH,
    TrackLang.ENGLISH, TrackLang.JAPANESE,
)

fun TrackLang.etiqueta(): String = when (this) {
    TrackLang.LATINO -> "Español latino"
    TrackLang.CASTELLANO -> "Castellano"
    TrackLang.SPANISH -> "Español (genérico)"
    TrackLang.DUAL -> "Dual / multi-audio"
    TrackLang.ENGLISH -> "Inglés"
    TrackLang.JAPANESE -> "Japonés"
    TrackLang.UNKNOWN -> "Desconocido"
}

/**
 * Lista ordenada de idiomas: los elegidos arriba y numerados (el reproductor los recorre en ese
 * orden), los no elegidos abajo en gris. Se edita con check + flechas en vez de arrastrar, porque el
 * mismo patrón tiene que funcionar con el control remoto del TV.
 */
@Composable
fun LanguageOrderEditor(
    title: String,
    options: List<TrackLang>,
    order: List<TrackLang>,
    onChange: (List<TrackLang>) -> Unit,
) {
    Text(
        title,
        style = MaterialTheme.typography.bodyMedium,
        color = ArkivTextSecondary,
        modifier = Modifier.padding(top = 16.dp, bottom = 6.dp),
    )
    Column {
        val sinElegir = options.filterNot { it in order }
        order.forEachIndexed { i, lang ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = true,
                    onCheckedChange = { onChange(LangOrderEdits.toggle(order, lang)) },
                    colors = CheckboxDefaults.colors(checkedColor = ArkivRed),
                )
                Text("${i + 1}. ${lang.etiqueta()}", color = Color.White, modifier = Modifier.weight(1f))
                TextButton(onClick = { onChange(LangOrderEdits.moveUp(order, lang)) }, enabled = i > 0) {
                    Text("▲", color = if (i > 0) Color.White else ArkivTextSecondary)
                }
                TextButton(
                    onClick = { onChange(LangOrderEdits.moveDown(order, lang)) },
                    enabled = i < order.lastIndex,
                ) {
                    Text("▼", color = if (i < order.lastIndex) Color.White else ArkivTextSecondary)
                }
            }
        }
        sinElegir.forEach { lang ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = false,
                    onCheckedChange = { onChange(LangOrderEdits.toggle(order, lang)) },
                    colors = CheckboxDefaults.colors(checkedColor = ArkivRed),
                )
                Text(lang.etiqueta(), color = ArkivTextSecondary, modifier = Modifier.weight(1f))
            }
        }
    }
}
```

- [ ] **Step 2: Usarlo en `SubtitleSection`**

En `SettingsScreen.kt`, dentro de `SubtitleSection` (cuya firma ya quedó como
`(style: PlaybackPrefs, onChange: (PlaybackPrefs) -> Unit)` en la Task 2), reemplazá el bloque
"Idioma preferido (auto-carga)" — el `Label("Idioma preferido")` y su `Row` de dos chips — por:

```kotlin
    LanguageOrderEditor(
        title = "Idioma del audio (en orden de preferencia)",
        options = IDIOMAS_AUDIO,
        order = style.audioLangs,
        onChange = { onChange(style.copy(audioLangs = it)) },
    )

    LanguageOrderEditor(
        title = "Idioma de los subtítulos (en orden de preferencia)",
        options = IDIOMAS_SUBTITULO,
        order = style.subtitleLangs,
        onChange = { onChange(style.copy(subtitleLangs = it)) },
    )

    Label("Cuándo mostrarlos")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Chip("Automático", style.subtitleMode == SubtitleMode.AUTO) {
            onChange(style.copy(subtitleMode = SubtitleMode.AUTO))
        }
        Chip("Desactivado", style.subtitleMode == SubtitleMode.OFF) {
            onChange(style.copy(subtitleMode = SubtitleMode.OFF))
        }
    }
    Text(
        "Automático: se prenden solo si el audio quedó en un idioma que no está en tu lista.",
        style = MaterialTheme.typography.bodySmall,
        color = ArkivTextSecondary,
        modifier = Modifier.padding(top = 4.dp),
    )
```

Y cambiá el título de la sección de `"Subtítulos"` a `"Audio y subtítulos"` (línea 210).

- [ ] **Step 3: Compilar**

Run: `./gradlew testDebugUnitTest assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Verificar en device**

Instalá, abrí Ajustes y comprobá: las dos listas se ven numeradas, ▲▼ reordenan, el check saca y
agrega, el último idioma no se puede destildar, y al volver a entrar a Ajustes el orden persiste.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/arkiv/player/ui/settings/LanguageOrderEditor.kt app/src/main/java/com/arkiv/player/ui/settings/SettingsScreen.kt
git commit -m "feat(ajustes): listas ordenadas de idioma para audio y subtitulos

Check + flechas en vez de arrastrar, porque el mismo patron tiene que
servir con el control remoto del TV."
```

---

### Task 7: Ajustes del TV — la sección que no existía

`TvSettingsScreen` no tiene hoy **ninguna** sección de subtítulos. Usa `androidx.tv.material3`, así
que el editor se reimplementa con `Surface` + `tvBotonColors()`/`tvBotonBorder()`, reusando
`LangOrderEdits`, `etiqueta()`, `IDIOMAS_AUDIO` e `IDIOMAS_SUBTITULO`.

**Files:**
- Create: `app/src/main/java/com/arkiv/player/ui/tv/TvLanguageOrderEditor.kt`
- Modify: `app/src/main/java/com/arkiv/player/ui/tv/TvSettingsScreen.kt` (agregar la sección en la `Column` principal)

**Interfaces:**
- Consumes: `LangOrderEdits` (Task 4), `etiqueta()`, `IDIOMAS_AUDIO`, `IDIOMAS_SUBTITULO` (Task 6), `PlaybackPrefs`/`SubtitleMode` (Task 2), `tvBotonColors()`/`tvBotonBorder()` (`TvButtonStyle.kt`)
- Produces: `@Composable fun TvLanguageOrderEditor(title, options, order, onChange)`

- [ ] **Step 1: Crear el editor del TV**

```kotlin
package com.arkiv.player.ui.tv

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.arkiv.player.playback.LangOrderEdits
import com.arkiv.player.playback.TrackLang
import com.arkiv.player.ui.settings.etiqueta
import com.arkiv.player.ui.theme.ArkivTextSecondary

/**
 * Misma lógica que el editor del celular ([com.arkiv.player.ui.settings.LanguageOrderEditor]) pero
 * con los componentes de `androidx.tv.material3`: el `Surface` de la librería de TV es el que sabe
 * pintarse al recibir foco del control remoto. La lógica de reordenar es compartida
 * ([LangOrderEdits]), así que las dos pantallas no se pueden desincronizar.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun TvLanguageOrderEditor(
    title: String,
    options: List<TrackLang>,
    order: List<TrackLang>,
    onChange: (List<TrackLang>) -> Unit,
) {
    Text(title, color = ArkivTextSecondary, modifier = Modifier.padding(top = 20.dp, bottom = 8.dp))
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        order.forEachIndexed { i, lang ->
            Row(
                Modifier.fillMaxWidth(0.6f),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TvLangBoton("✓ ${i + 1}. ${lang.etiqueta()}", Modifier.weight(1f)) {
                    onChange(LangOrderEdits.toggle(order, lang))
                }
                TvLangBoton("▲") { onChange(LangOrderEdits.moveUp(order, lang)) }
                TvLangBoton("▼") { onChange(LangOrderEdits.moveDown(order, lang)) }
            }
        }
        options.filterNot { it in order }.forEach { lang ->
            TvLangBoton(lang.etiqueta(), Modifier.fillMaxWidth(0.6f)) {
                onChange(LangOrderEdits.toggle(order, lang))
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun TvLangBoton(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
        colors = tvBotonColors(),
        border = tvBotonBorder(),
    ) {
        Text(label, color = Color.White, modifier = Modifier.padding(12.dp))
    }
}
```

- [ ] **Step 2: Agregar la sección a `TvSettingsScreen`**

Dentro de `TvSettingsScreen`, junto al resto de los `collectAsStateWithLifecycle`:

```kotlin
    val playbackPrefs by graph.subtitlePrefs.prefs.collectAsStateWithLifecycle()
```

Y una función local, al lado de las otras que persisten + sincronizan:

```kotlin
    // Persiste local + sincroniza al celular, igual que hace la pantalla de Ajustes del teléfono.
    fun setPrefs(p: com.arkiv.player.data.subtitles.PlaybackPrefs) {
        graph.subtitlePrefs.update(p)
        graph.applicationScope.launch { runCatching { graph.remoteController.sendSubtitlePrefs(p.toJson()) } }
    }
```

En la `Column` principal, después de la sección de calidad web, agregá:

```kotlin
        Text(
            "Audio y subtítulos",
            style = MaterialTheme.typography.titleMedium,
            color = Color.White,
            modifier = Modifier.padding(top = 24.dp),
        )
        TvLanguageOrderEditor(
            title = "Idioma del audio (en orden de preferencia)",
            options = com.arkiv.player.ui.settings.IDIOMAS_AUDIO,
            order = playbackPrefs.audioLangs,
            onChange = { setPrefs(playbackPrefs.copy(audioLangs = it)) },
        )
        TvLanguageOrderEditor(
            title = "Idioma de los subtítulos (en orden de preferencia)",
            options = com.arkiv.player.ui.settings.IDIOMAS_SUBTITULO,
            order = playbackPrefs.subtitleLangs,
            onChange = { setPrefs(playbackPrefs.copy(subtitleLangs = it)) },
        )
        TvActionOption(
            if (playbackPrefs.subtitleMode == com.arkiv.player.data.subtitles.SubtitleMode.AUTO) {
                "Subtítulos: automáticos (tocá para desactivar)"
            } else {
                "Subtítulos: desactivados (tocá para automáticos)"
            },
        ) {
            val nuevo = if (playbackPrefs.subtitleMode == com.arkiv.player.data.subtitles.SubtitleMode.AUTO) {
                com.arkiv.player.data.subtitles.SubtitleMode.OFF
            } else {
                com.arkiv.player.data.subtitles.SubtitleMode.AUTO
            }
            setPrefs(playbackPrefs.copy(subtitleMode = nuevo))
        }
```

- [ ] **Step 3: Compilar**

Run: `./gradlew testDebugUnitTest assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Verificar en el Fire Stick**

Instalá por ADB de red (puerto 5555) y en Ajustes del TV comprobá: se navega con el D-pad, los
botones se pintan al enfocarse, ▲▼ reordenan, y **el cambio hecho en el TV aparece en el celular**
(el sync usa el mismo canal `sendSubtitlePrefs` de siempre).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/arkiv/player/ui/tv/TvLanguageOrderEditor.kt app/src/main/java/com/arkiv/player/ui/tv/TvSettingsScreen.kt
git commit -m "feat(tv): seccion de audio y subtitulos en Ajustes

El TV no tenia ninguna: las preferencias se sincronizaban pero no habia
donde verlas ni cambiarlas."
```

---

### Task 8: Integrar el player — OpenSubtitles, promoción y `.srt` automáticos

**Files:**
- Modify: `app/src/main/java/com/arkiv/player/data/subtitles/SubtitleApi.kt:93` (`download`)
- Modify: `app/src/main/java/com/arkiv/player/ui/player/PlayerScreen.kt:1318-1349, 1376, 2410, 2425`

**Interfaces:**
- Consumes: `LangPromotion.promote(...)` (Task 4), `PlaybackPrefs.openSubtitlesCodes()` (Task 2), `VlcPlayer.addSubtitleSlave(uri, byUser)` (Task 5)
- Produces: `SubtitleApi.download(fileId: Long, dir: File, lang: String = ""): File?`

- [ ] **Step 1: Que el `.srt` bajado lleve el idioma en el nombre**

En `SubtitleApi.kt`, cambiá la firma y la última línea de `download`:

```kotlin
    /**
     * Baja el .srt de un subtítulo y lo guarda localmente. Devuelve el archivo o null.
     * [lang] va en el NOMBRE del archivo (`sub-123.es.srt`) porque libVLC nombra las pistas externas
     * con su ruta: así el clasificador de idioma también reconoce las bajadas de OpenSubtitles, en
     * vez de que sean la única pista opaca del sistema.
     */
    suspend fun download(fileId: Long, dir: File, lang: String = ""): File? = withContext(Dispatchers.IO) {
```

y

```kotlin
        runCatching {
            dir.mkdirs()
            val sufijo = lang.lowercase().takeIf { it.isNotBlank() }?.let { ".$it" }.orEmpty()
            File(dir, "sub-$fileId$sufijo.srt").apply { writeBytes(bytes) }
        }.getOrNull()
```

- [ ] **Step 2: Pasar el idioma y ordenar por preferencia**

En `PlayerScreen.kt`, en `applySubtitle` (línea 1323):

```kotlin
                    graph.subtitleApi.download(sub.fileId, java.io.File(context.cacheDir, "subs"), sub.language)
```

En el `LaunchedEffect(episodeId)` de la búsqueda online (líneas 1337-1349), el `langs` ya quedó como
`graph.subtitlePrefs.prefs.value.openSubtitlesCodes()` en la Task 2. Ahora ordená los resultados
también por la preferencia del usuario, reemplazando el `.sortedByDescending { it.hashMatch }`:

```kotlin
        val ordenIdiomas = graph.subtitlePrefs.prefs.value.openSubtitlesCodes().split(",")
        suspend fun runSearch(hash: String?) {
            subtitles = if (subCtx == null && hash == null) emptyList() else runCatching {
                graph.subtitleApi.search(
                    imdbId = subCtx?.imdbId, query = subCtx?.title,
                    season = subCtx?.season, episode = subCtx?.episode, languages = langs,
                    moviehash = hash,
                )
            }.getOrDefault(emptyList())
                // release exacto primero; dentro de cada nivel, tu idioma preferido arriba.
                .sortedWith(
                    compareByDescending<com.arkiv.player.data.subtitles.SubtitleTrack> { it.hashMatch }
                        .thenBy { s ->
                            ordenIdiomas.indexOf(s.language.lowercase()).takeIf { it >= 0 } ?: Int.MAX_VALUE
                        },
                )
        }
```

- [ ] **Step 3: Que los `.srt` del torrent no se autoactiven**

Línea 1376:

```kotlin
                if (loaded.add(f.absolutePath)) runCatching { vlc.addSubtitleSlave(Uri.fromFile(f), byUser = false) }
```

- [ ] **Step 4: Promover el idioma elegido a mano**

En `PlayerScreen.kt`, al lado de `refreshTracks()` (línea 1310), agregá:

```kotlin
    /**
     * Elegir una pista a mano sube ese idioma al tope de la preferencia — pero solo si el archivo
     * tenía más de un idioma (ver LangPromotion: sin alternativa, elegir no expresa preferencia).
     */
    fun promoteLang(pickedName: String, allNames: List<String>, esAudio: Boolean) {
        val prefs = graph.subtitlePrefs.prefs.value
        val nuevo = com.arkiv.player.playback.LangPromotion.promote(
            order = if (esAudio) prefs.audioLangs else prefs.subtitleLangs,
            pickedName = pickedName,
            allNames = allNames,
            classifier = if (esAudio) {
                com.arkiv.player.playback.LangTokens::classify
            } else {
                com.arkiv.player.playback.LangTokens::classifyFileName
            },
        ) ?: return
        val actualizado = if (esAudio) prefs.copy(audioLangs = nuevo) else prefs.copy(subtitleLangs = nuevo)
        graph.subtitlePrefs.update(actualizado)
        graph.applicationScope.launch {
            runCatching { graph.remoteController.sendSubtitlePrefs(actualizado.toJson()) }
        }
    }
```

En el diálogo, línea 2410 (audio):

```kotlin
                            TextButton(onClick = {
                                vlc.setVlcAudioTrack(id); curAudio = id
                                promoteLang(name, audioTracks.filter { it.first >= 0 }.map { it.second }, esAudio = true)
                            }) {
```

Y línea 2425 (subtítulos del archivo) — `id < 0` es "Desactivar" y no promociona nada:

```kotlin
                            TextButton(onClick = {
                                vlc.setVlcSpuTrack(id); curSpu = id; if (id < 0) selectedSub = null
                                if (id >= 0) {
                                    promoteLang(name, spuTracks.filter { it.first >= 0 }.map { it.second }, esAudio = false)
                                }
                            }) {
```

- [ ] **Step 5: Compilar y correr todo**

Run: `./gradlew testDebugUnitTest assembleDebug`
Expected: BUILD SUCCESSFUL, todos los tests en verde (48 entre las 5 clases nuevas).

- [ ] **Step 6: Verificar en device**

1. **Promoción**: en una peli dual, elegí inglés en el menú → andá a Ajustes y confirmá que "Inglés"
   quedó primero en la lista de audio.
2. **No-promoción**: en una peli que solo tiene inglés, elegí inglés → Ajustes **no** cambia.
3. **OpenSubtitles**: con Inglés en la lista de subtítulos, la búsqueda online devuelve resultados en
   los dos idiomas y los tuyos aparecen arriba. Elegir uno lo aplica y **no** lo pisa la selección
   automática.
4. **`.srt` del torrent**: un torrent con `movie.es.srt` en una peli en japonés → el subtítulo se
   prende solo; en una peli en español → no se prende.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/arkiv/player/data/subtitles/SubtitleApi.kt app/src/main/java/com/arkiv/player/ui/player/PlayerScreen.kt
git commit -m "feat(player): promocion del idioma elegido y OpenSubtitles por preferencia

Busca en todos los idiomas de tu lista (no solo es), los ordena por tu
preferencia y guarda el .srt con el idioma en el nombre para que el
clasificador tambien lo reconozca."
```

---

## Verificación final

- [ ] `./gradlew testDebugUnitTest` — todo verde
- [ ] `./gradlew assembleDebug` — compila
- [ ] Los 4 archivos de la otra sesión (`TmdbApi.kt`, `CineDetailScreen.kt`, `SearchPlayback.kt`,
      `TvSearchScreen.kt`) siguen **sin commitear**: `git status --short` los muestra como ` M`
- [ ] Celular y TV muestran las mismas preferencias tras cambiar una de los dos lados
