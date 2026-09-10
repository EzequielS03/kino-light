# Idea diferida: las credenciales de la app en un archivo cifrado

Estado: **diferida** por decisión de Cristian (2026-09-10). Queda escrita para retomarla.

## Lo que se pidió

Que todas las credenciales vivan en un archivo que solo la app funcionando pueda descifrar, y que la app
las tome de ahí cuando las necesite.

## Cómo están hoy

- Las credenciales de compilación viven en el `.env` (gitignoreado; nunca se commiteó en ninguna rama,
  verificado con `git log --all -- .env`). `app/build.gradle.kts` lee: `IPTV_3DES_KEY`, `IPTV_HOSTS`,
  `IPTV_APP_ID`, `IPTV_APK_VERSION`, `API_KEY` (TMDB) y las cuatro `RELEASE_*` de firma.
- Las de la app se hornean en `BuildConfig`: `IPTV_*` y `TMDB_API_KEY`. **El `.env` las protege de git,
  no del APK.**
- El APK sale **sin R8** (`isMinifyEnabled = false`, porque libVLC llama por JNI a clases que el
  shrinker borraría), así que con un descompilador se ven el nombre y el valor en segundos.
- Caracol y Kilo no usan ningún secreto.
- La cuenta de Magis de la persona no está en el `.env`: se guarda en el aparato, cifrada con el Keystore
  de Android.

## El límite de fondo

En una app sin servidor propio **no hay forma de que "solo la app" descifre el archivo de verdad**: para
usar una credencial, la app necesita la llave que la descifra, y esa llave tiene que viajar en el APK o
existir en el aparato. Quien tiene el APK la tiene. Es lo mismo que se hizo con la llave 3DES de Magis,
que la app de Magis esconde exactamente así y se sacó igual.

El Keystore de Android no lo resuelve: protege lo que se crea en el aparato, no lo que llega dentro del
APK. La única protección real es que la credencial no esté en el cliente, o sea un servidor que la guarde,
que es justo lo que esta rama eliminó.

## Lo que sí daría

Subir el costo: de "abrir el APK y leer" a "tener que depurar la app mientras corre". Frena la lectura
casual, no a alguien decidido.

## Diseño si se retoma

1. El build toma los valores del `.env` y los cifra en un archivo dentro del APK.
2. La llave no se guarda entera en ningún lado: se arma al ejecutar (idealmente en código nativo).
3. La app descifra una vez al arrancar y guarda los valores en memoria.
4. Solo descifra si el APK sigue firmado con la firma propia (`seguridad/FirmaDelApk.kt` ya lo verifica):
   un APK re-firmado no arranca.

**Nunca** meter en ese archivo las credenciales de firma (`RELEASE_*`): son solo de compilación, y ahí
quedarían publicadas dentro de cada APK.

## Por qué quedó para después

Las candidatas valen poco protegidas: la llave de TMDB es gratis y se puede revocar, y la 3DES de Magis
ya está dentro de la app de Magis. Lo urgente de esta área es otra cosa: **el keystore de firma
(`~/.android/arkiv-release.jks`) no tiene respaldo**, y si se pierde no se puede actualizar ningún APK ya
repartido.
