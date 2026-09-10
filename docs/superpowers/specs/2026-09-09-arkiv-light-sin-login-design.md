# Arkiv Light — Sub-proyecto 2B: la app abre sin login

Rama `light-magis`. Continúa el sub-proyecto 2A (cliente Magis + TMDB directos, completo y
verificado en dispositivo — ver `2026-09-09-arkiv-light-magis-cliente-directo-design.md`).

## Contexto

Después de 2A el contenido ya no pasa por ningún servidor propio: el portal de Magis y TMDB se
hablan directo desde el aparato. Pero la app **sigue exigiendo login para abrir**: `MainActivity`
mira `SesionDePersona.estado` y, sin sesión, muestra la pantalla de entrada en vez del home. Es la
última dependencia que puede dejar Kino L sin abrir aunque todo lo que reproduce esté vivo.

Y no es solo la puerta. Todo lo que le queda al gateway exige sesión (`require_sesion` en los
routers de `trivia`, `marcadores`, `anime`, `recomendaciones` y `catalog`), así que sacar el login
decide también el destino de esas funciones.

## Decisiones tomadas

**Todo lo que exige sesión se resigna.** No se conserva una identidad de aparato anónima ni se
portan esas fuentes a clientes directos. La app queda hablando solo con: el portal de Magis, su
CDN, TMDB y el OTA.

**El OTA se queda; la subida de crashes se va.** El chequeo de APK nuevo
(`apk.comparadorinternet.co`) no bloquea nada: si se cae, la app abre y reproduce igual, y
actualizar sin cable es lo cómodo en un TV. Los crashes se leen por `adb logcat` —ya se hizo así
para diagnosticar el vivo— así que la colección `crash_logs` de PocketBase deja de tener sentido.

**Tres de las funciones "resignadas" ya estaban muertas** desde la poda del sub-proyecto 1 y no las
llama nadie: `animeMeta`, `SimklApi` y las recomendaciones "Para ti". Sacarlas no cuesta ninguna
función; solo `ArkivApiClient` y `AppGraph` las siguen construyendo.

**Simkl queda afuera a propósito.** Su API es pública (un `client_id` en un header, igual que TMDB),
así que traerla sería fácil — pero hoy no tiene consumidor, y escribir un cliente que nadie llama es
justo lo que la regla de esta rama prohíbe. Si vuelve, vuelve con un uso decidido: títulos
alternativos para rankear la búsqueda del portal, y total de episodios + offset para el guard de
numeración de anime (hoy ese guard apaga el enriquecimiento entero cuando el portal parte la serie
distinto que TMDB).

## Alcance

**Entra:** sacar el gate de sesión del arranque, borrar el subsistema de cuentas y de identidad de
aparato, borrar los clientes del gateway que quedan, sacar de la UI las funciones resignadas, y
dejar el vínculo de Magis parado sobre sus propios pies.

**No entra:** el sub-proyecto 3 (Ditu directo), traer Simkl, ni ninguna fuente nueva.

## Qué se pierde en la UI

- **La trivia** ("dato curioso") del reproductor. Es la única de las tres que el `CLAUDE.md` de la
  rama tenía marcada como excepción permanente: se revierte esa decisión y se va con el resto.
- **El saltar-intro automático**: se va la búsqueda de marcadores contra el servidor
  (`BuscadorDeMarcadores`). La **corrección a mano** se queda entera — los marcadores viven en Room
  (`skipMarkerDao`) y los edita `EditorDeMarcadores`, sin red.
- **Los subtítulos de OpenSubtitles.** Siguen estando los que el propio portal entrega junto al
  stream (`MagisPlayable.subtitulos`, ya cableados en 2A).
- **Ajustes → Cuenta y Mis Aparatos** desaparecen; en su lugar queda una sección de cuenta de Magis.

## Componentes

### `CuentaDeMagis` (nueva, chica)

Lo único vivo que hoy queda de `AccountManager` es sostener "¿hay cuenta de Magis vinculada?" y
avisar cuando cambia. Eso pasa a una clase propia en `data/magis/`, con un
`StateFlow<EstadoDeMagis>` (`Sin` / `Vinculada(email)`) y `vincular(email, clave)` / `desvincular()`
delegando en `MagisSession`, que ya tiene `login`, `logout`, `hasAccountLinked` y `emailVinculado`.

Los errores siguen viajando como una excepción con mensaje listo para mostrar, con la misma
distinción que ya hace 2A: **"credenciales inválidas" y "Magis no disponible" son cosas distintas**,
porque lo que la persona hace después es distinto (corregir la clave vs. reintentar).

Las tres pantallas que hoy hablan con `AccountManager` se reescriben contra ella:
`ui/settings/AccountSection.kt`, `ui/tv/TvSettingsCuenta.kt` y `ui/tv/TvOfertaVincularMagis.kt`.

### El arranque

`MainActivity` pierde la rama de sesión: queda el gate de integridad que ya existe
(`motivosParaNoArrancar`) y entra directo al home. Se borran `ui/entrada/` (celu) y
`ui/tv/TvPantallaDeEntrada.kt`.

En el TV, la oferta de vincular Magis sigue siendo lo primero que se compone dentro de
`ArkivTvRoot`, pero su condición deja de mirar la cuenta de Kino: pasa a ser "no hay cuenta de Magis
vinculada **y** no dijiste Ahora no". El email deja de venir precargado — lo llenaba la cuenta de
Kino y ya no hay de dónde sacarlo.

### Los datos guardados

`accountId` **no existe** en las entidades ni en los DAOs de Room: solo vive en `CuentaApi`. La
biblioteca local ya está desacoplada de la cuenta desde la poda, así que borrar cuentas no toca un
solo dato de biblioteca, historial ni miniaturas.

Lo que sí hay que rescatar del store cifrado del aparato (`SecureDeviceStore`, que se borra) son dos
cosas que **no son de la cuenta**:

- **`adultosDesbloqueado`** — el candado 18+, que es por aparato. Si se pierde, la sección 18+ se
  esconde sola y parece un bug de otra cosa: va con test de migración.
- **`magisOfertaDescartada`** — hoy vive en `SettingsStore` pero lo resetea `onLocalWipe` de
  `AccountManager`; al no haber logout de persona, ese reset desaparece.

Ambas quedan en `SettingsStore`, leyendo una vez el valor viejo del store cifrado si está.

**`PrefsCifradas.kt` no se borra**: vive en `pocketbase/` pero lo usa
`EncryptedMagisCredentialStore` (2A) para que un archivo que el Keystore ya no descifra no deje la
app sin arrancar. Se muda a `data/magis/`.

### Lo que el arranque hace hoy con la cuenta

`ArkivApp.onCreate` toca el store del aparato en dos lugares, y los dos se van con las cuentas:

- **`DuenoDeLaBase.hayQueAdoptar(...)`**: decidía si la biblioteca local pasaba a ser de la cuenta
  que acababa de entrar. Sin cuentas no hay a quién adoptar — se borra junto con `DuenoDeLaBase.kt`.
- **`recientesPurgados`**: el marcador de una purga única de recientes (2026-08-14), ya aplicada en
  los aparatos que existen. Al borrarse el store se perdería el marcador y la purga volvería a
  correr una vez. Se resuelve moviendo el flag a `SettingsStore` en la misma migración que el
  candado 18+.

**`LibraryWiper` se borra**: su único llamador es el `onLocalWipe` del logout, que deja de existir.
(El `DestructorDeFrames` lo nombra en un KDoc; hay que corregir ese comentario, no el código.)

## Orden de implementación

Por capas, de afuera hacia adentro, para que en **cualquier commit intermedio la app siga abriendo**:

1. **Sacar los consumidores** de lo resignado (trivia en `PlayerViewModel` + su botón,
   `BuscadorDeMarcadores`, los subtítulos externos en `PlayerPistas`, `SimklApi`, `animeMeta`,
   `AvisadorDeRecomendaciones`). Al terminar, `ArkivApiClient`/`SubtitleApi`/`SimklApi` quedan sin
   llamadores.
2. **Borrar esos clientes** y lo que arrastren, incluida la URL del gateway en Ajustes si no queda
   nadie mirándola.
3. **Abrir la puerta**: `MainActivity` sin gate de sesión. Es el commit donde el objetivo del
   sub-proyecto ya se puede comprobar instalando.
4. **`CuentaDeMagis`** y las tres pantallas reescritas contra ella.
5. **Borrar cuentas**: `pocketbase/` (salvo `PrefsCifradas`), `CuentaApi`, `InterceptorDeSesion`,
   `ui/entrada/`, `TvPantallaDeEntrada`, `MisAparatos`, `CrashUploader`. Acá va la migración de las
   dos preferencias.
6. **Limpieza**: tests, KDocs que quedaron mintiendo, el `CLAUDE.md` de la rama (la trivia deja de
   ser excepción permanente) y un barrido de que no quede ninguna llamada a infraestructura propia
   salvo el OTA.

## Riesgos

- **Perder el candado 18+ en la migración.** Falla en silencio y hacia el lado "seguro", que es el
  peor para diagnosticar: la sección simplemente no aparece. Test de migración obligatorio.
- **Que algo del arranque dependa de la sesión sin decirlo.** Los dos casos conocidos están
  arriba (`DuenoDeLaBase` y `recientesPurgados`), pero hay que revisar `ArkivApp.onCreate` entero,
  no solo `MainActivity`: es donde se arman las cosas antes de que haya pantalla que muestre un
  error.
- **`Crash::reportar` lo llaman varios lugares** (incluido el interceptor, que se borra). Se conserva
  el reporte local; solo se saca la subida.

## Verificación

Cada paso: suite completa en verde y `assembleDebug`.

En el KALLEY R3, al final: la app **abre sin pedir login**, la biblioteca sigue entera (los
capítulos de Naruto), Magis sigue vinculado (su store cifrado es otro archivo y no se toca), el
canal en vivo sigue andando, y el candado 18+ sigue desbloqueado si lo estaba.
