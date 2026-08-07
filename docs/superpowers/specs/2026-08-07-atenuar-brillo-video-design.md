# Atenuar el brillo del video (modo noche) — diseño

**Fecha:** 2026-08-07
**Estado:** aprobado, sin implementar

## Problema

Viendo películas y series de noche, la pantalla encandila. Se quiere un botón en el reproductor
que, al oprimirlo, baje el brillo del video, y al volver a oprimirlo lo devuelva a normal.

Aplica a los dos dispositivos en uso: el Samsung S24+ y el Fire TV Stick.

## Restricción técnica que define la solución

Hay tres formas concebibles de atenuar, y dos quedan descartadas por el entorno:

1. **`WindowManager.LayoutParams.screenBrightness`** — baja el backlight real del panel. Ya está en
   uso en el celular (gesto vertical en el borde izquierdo, `PlayerScreen.kt:1415`). **No sirve en
   el Fire TV Stick**: el Stick no tiene panel propio, el brillo lo controla el televisor y Android
   no puede tocarlo.
2. **Filtro `adjust` de libVLC** — sería el atenuado de mejor calidad (actúa sobre el video real).
   **No existe binding en libVLC 3.x.** Verificado con `javap` sobre
   `org.videolan.android:libvlc-all:3.6.0` (`app/build.gradle.kts:148`): `MediaPlayer` solo expone
   `setScale`/`getScale`, `setVideoScale` y `setEqualizer`. Las opciones de VLC solo se pueden pasar
   al construir el `LibVLC` (proceso completo) o por media al cargar — ninguna es conmutable en vivo.
3. **Velo semitransparente encima del video** — funciona en ambos dispositivos. **Es la elegida.**

**Limitación aceptada explícitamente:** el velo reduce brillo *y contraste* (los negros no se
vuelven más negros, se superpone un gris). No equivale a bajarle el backlight al televisor. Para el
caso de uso —que no encandile de noche— es suficiente.

## Decisiones tomadas

| Decisión | Elección |
|---|---|
| Cantidad de atenuado | Un solo nivel fijo, ~35% |
| Persistencia | Se recuerda siempre, incluso tras cerrar la app |
| Alcance | TV y celular |

## Diseño

### 1. Preferencia persistente

Booleano nuevo en `SettingsStore`
(`app/src/main/java/com/arkiv/player/data/SettingsStore.kt`), copiando el molde exacto de
`cloudflareSolverEnabled` (campo en línea 56, setter en el bloque de setters):

- `private val _dimVideo = MutableStateFlow(prefs.getBoolean(KEY_DIM_VIDEO, false))`
- `val dimVideo: StateFlow<Boolean> = _dimVideo`
- `fun setDimVideo(v: Boolean) { prefs.edit().putBoolean(KEY_DIM_VIDEO, v).apply(); _dimVideo.value = v }`
- `private const val KEY_DIM_VIDEO = "dim_video"` junto a las demás claves

Default `false`: la app se instala sin atenuar.

Se consume en `PlayerContent` vía `graph.settings` (ya disponible, `PlayerScreen.kt:287/307`)
con `collectAsState()`.

### 2. El velo

Un `Box` negro con `alpha = DIM_ALPHA` dentro del contenedor del video, **encima del `AndroidView`
de VLC (`PlayerScreen.kt:1281`) y debajo de la barra de controles (`PlayerScreen.kt:1594`)**.

Ese orden de capas es deliberado y produce dos efectos buscados:

- **Los controles se leen a brillo normal.** Si el velo los cubriera, costaría leerlos de noche,
  que es justo cuando se usan.
- **No intercepta toques.** Un `Box` sin modificador de gestos no es blanco de hit-testing en
  Compose, así que la capa de gestos del celular (`pointerInput`, `PlayerScreen.kt:1377` —
  tap/seek/volumen/brillo) sigue funcionando igual.

Se renderiza solo cuando la preferencia está activa.

El nivel vive como `private const val DIM_ALPHA = 0.35f` junto a los otros consts de nivel superior
del archivo (zona de `SPEED_STEPS`/`ZOOM_STEPS`, líneas 168–179), para que calibrarlo sea una línea.

### 3. El botón

En la fila de transporte (`PlayerScreen.kt:1838`), al lado del de subtítulos, replicando su patrón
visual (ícono distinto + tinte):

- Activo: `Icons.Default.Brightness2` (luna), tinte `ArkivRed`
- Inactivo: `Icons.Default.BrightnessHigh` (sol), tinte `Color.White`

Ambos íconos confirmados presentes en `material-icons-extended` 1.7.6, ya declarado en
`app/build.gradle.kts:109`.

Dos ramas, como el resto de los controles del archivo:

- **TV**: un `TvTransportButton` más (composable en `PlayerScreen.kt:2328`).
- **Celular**: un `IconButton`, en la rama de teléfono (`PlayerScreen.kt:1969`).

### 4. Riesgo principal: el foco del control remoto

Cada botón de TV declara explícitamente sus cuatro direcciones de foco, y el propio código advierte
(`PlayerScreen.kt:1878-1880`) que dejar una dirección sin definir hace que Compose pierda el foco.

Insertar un botón en la fila obliga a **re-encadenar los `left`/`right` de sus vecinos** (ver
`forwardRight`/`nextRight` en 1883–1884 y el `right` del botón de subtítulos en 1937), y a declarar
un `FocusRequester` nuevo para el botón (los 8 existentes están en 347–357).

Es la parte que no se valida compilando: hay que recorrer la fila entera con el remoto real.

## Verificación

- **Test unitario**: round-trip de la preferencia en `SettingsStore` (guardar `true`, releer,
  confirmar que persiste y que el `StateFlow` emite).
- **En dispositivo** (ADB al S24+ y al Fire Stick):
  1. El velo atenúa el video al oprimir, y lo devuelve a normal al volver a oprimir.
  2. La preferencia sobrevive a cerrar y reabrir la app.
  3. Los controles se siguen leyendo bien con el velo activo.
  4. **Fire Stick**: el foco del remoto recorre toda la fila de transporte en ambos sentidos, sin
     perderse, con el botón nuevo incluido.
  5. **Celular**: el gesto de brillo y el de volumen siguen funcionando con el velo activo.

## Fuera de alcance (decidido, no omitido)

- Niveles múltiples de atenuado.
- Ajuste del nivel desde la pantalla de Ajustes.
- HUD de texto al oprimir: el cambio de tinte del ícono y el efecto visible ya son señal suficiente.
- Sincronizar el estado entre celular y TV por el canal de control remoto.
