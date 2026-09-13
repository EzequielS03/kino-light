# Trampas medidas

Cosas que costaron horas o días descubrir, y que **no se deducen leyendo el código**. Cada una está
medida, no supuesta. Si vas a tocar el área, leé la suya primero.

---

## El `.env` falla en silencio

No está en git y nunca estuvo (verificado contra toda la historia y todas las ramas). Sin él, el
build **no falla**: sale un APK que compila, instala y no sirve — catálogo vacío, Magis sin poder
autenticarse, ningún mensaje de error.

`cp .env.example .env` y llenalo. Las diez claves están explicadas ahí.

Corolario: **compilar desde una copia del repo sin `.env` produce un APK roto que parece sano.**

---

## <a name="docs-podrido"></a>`docs/` está mayormente podrido

De sus 153 archivos: **108 mencionan torrent**, 43 PocketBase, 36 el gateway `arkiv-api`, 28
jackett, 25 libVLC. Todos esos subsistemas **se borraron de esta app**. Los documentos se quedaron.

Lo que sí vale:

- `docs/superpowers/specs/2026-09-*-arkiv-light-*.md` — los siete specs de esta app, en orden.
- Todo lo demás: tratalo como arqueología del repo del que salió esto, no como documentación.

Es la misma trampa que leer `lordmacu/arkiv`, pero adentro del repo propio y por eso más fácil de
pisar.

---

## Caracol es Widevine: lo que se puede y lo que no

**Medido el 2026-09-13, con control en la misma corrida** (mismo token, misma conexión):

```
STREAMING licence (control) · GRANTED
OFFLINE  LICENCE REFUSED · HTTP 500  body={...,"X-DRM-Error":"true",...}
```

El servidor de licencias de Caracol **no concede licencias persistentes**. No hay parámetro que lo
habilite: el JWT del `playback_token` trae un claim `isDownload` vacío, y `?isDownload=Y|true`,
`?downloadRequest=Y` devuelven el mismo token; `CONTENT/DOWNLOADURL`, `CONTENT/DOWNLOAD` y
`VIDEOURL/DOWNLOAD` dan 404. El entitlement dice `rights: "watch"` a secas. El MP4 progresivo de
Mediastream (`mdstrm.com/video/<id>.mp4` → `/video/p/…`) existe pero responde **401 desde cualquier
Referer**.

**Entonces "descargar Caracol" significa**: guardar los segmentos **cifrados** en un `SimpleCache` de
media3 y pedir una licencia de streaming fresca al darle play. Nada se descifra en la app; la llave
nunca sale del CDM del aparato. **No funciona en modo avión** — necesita unos KB de red para abrir.

No se puede prometer "lo ves sin internet". Sí se puede prometer "no gastas los datos dos veces".

Si alguien pide descarga offline de verdad: la respuesta es que el servidor la niega, no que la app
no sepa. Descifrar exigiría romper el CDM y eso no se hace.

### Tres cosas que hay que respetar al tocar esa descarga

1. **Las pistas se eligen al bajar y se FIJAN al reproducir.** El manifiesto anuncia las seis
   calidades estén o no en disco; sin declarar los mismos `StreamKey`, el selector elige por ancho
   de banda y pide una que nadie bajó. Es un fallo medido, no un temor: pidió `init-f4-v1-x3` con la
   f1 en disco.
2. **La URL del manifiesto guardada MANDA sobre una recién resuelta.** Un caché se indexa por la URI
   con la que se escribió; abrir con otra —aunque apunte al mismo video— falla todos los bytes y se
   va a la red en silencio.
3. **`LocalLibrary.fileFor` excluye Caracol a propósito.** Lo que su descarga deja en `filePath` es
   el REGISTRO (un JSON), no un video. Entregárselo al reproductor de archivos locales da pantalla
   negra sin ningún error.

Ver `data/caracol/` y `data/local/DituDownloadStrategy.kt`; sus KDoc traen el detalle.

---

## El contenido DRM no se puede fotografiar

`adb exec-out screencap` devuelve **negro** sobre el reproductor de Caracol, y `uiautomator dump`
devuelve una jerarquía **vacía**. No es un fallo: la ventana es segura.

Para revisar la UI del reproductor, abrí un título de **Magis**, que no es DRM y sí se ve.

---

## El host del portal de Magis no existe como string

Se arma en runtime: `"$scheme://$host/api/portalCore/$path"` en `data/magis/MagisPortalClient.kt`, a
partir de `BuildConfig.IPTV_HOSTS`, que sale del `.env`.

O sea que **un barrido de hosts por grep concluye, equivocado, que la app no le habla al portal**.
Lo mismo, en menor grado, con los CDNs de video: la API los devuelve en runtime. Ver
[reglas.md](reglas.md) para el barrido que sí sirve.

---

## Un 401 borra la sesión sola

Cuando el portal responde 401, el aparato se auto-expulsa y la sesión local se borra. Se ve como "no
reproduce Magis" cuando en realidad el aparato se echó a sí mismo. En el TV es terminal: solo vuelve
a entrar por pareo.

---

## La llave de firma es irreemplazable

`~/.android/arkiv-release.jks`, fuera del repo y **sin respaldo**. Si se pierde, Android no deja
actualizar por encima ningún APK firmado con ella: todos los instalados quedan huérfanos.

Las rutas y claves salen del `.env` (`RELEASE_*`). Si están vacías, `assembleRelease` produce un APK
**sin firmar**, a propósito — no cae a la llave de debug.

---

## Chromecast: el receptor por defecto es limitado

El Default Media Receiver de Google (`CC1AD845`) **no reproduce MPEG-TS**: baja unos megas y se va a
idle, y en HLS con segmentos TS la imagen va a tirones. Por eso hay un remux a MP4 fragmentado antes
de castear (ver `playback/TsRemuxer.kt`) y un receptor propio en `receiver/index.html` que usa Shaka
para HLS.

Ese receptor propio necesita un App ID registrado en la Cast Developer Console (pago único). Mientras
`CAST_RECEIVER_ID` esté vacío en el `.env`, se usa el de Google.

---

## El hook `rtk` recorta `cat` y `grep`

Devuelve archivos **incompletos sin avisar**. Cuando el contenido exacto importa, usar la herramienta
Read o `command cat` / `command grep`.
