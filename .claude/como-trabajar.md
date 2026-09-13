# Cómo trabajar acá

## Compilar e instalar

```bash
./gradlew :app:assembleDebug            # SIEMPRE debug para instalar en un aparato
./gradlew :app:testDebugUnitTest        # la suite entera, rápida
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

`assembleRelease` es solo para distribuir el APK público, y exige las `RELEASE_*` del `.env`.
Instalar un release encima de un debug obliga a desinstalar, y eso **borra los datos de la app**.

**adb:** usar solo el del SDK (`~/Library/Android/sdk/platform-tools/adb`, v37). Mezclarlo con
`/opt/homebrew/bin/adb` (v36) reinicia el server y tumba todas las conexiones.

## Los aparatos de prueba

| aparato | cómo se llega | para qué |
|---|---|---|
| Samsung S24+ | USB, o WiFi (`adb connect <ip>:<puerto>`; el puerto cambia en cada reconexión) | celular |
| KALLEY R3 | ADB de red, puerto 5555 | Android TV |
| Fire TV Stick | ADB de red, puerto 5555 | la otra variante de TV |

La depuración inalámbrica se apaga sola y el puerto cambia: si `adb devices` no lo ve, pedí el
cable en vez de barrer la red.

**Confirmá que Cristian no esté usando el celular antes de mandarle taps por ADB.** Abrirle el
reproductor o tocarle la pantalla mientras lo usa es invasivo.

Cuidado con el aviso de **actualización OTA**: aparece al abrir la app y tapa la pantalla. Hay que
darle *Cerrar*; *Actualizar ahora* instala el APK público **encima del que estás probando**.

## <a name="verificar"></a>Verificar

**En este proyecto los tests verdes y los informes mienten.** Ha pasado varias veces: una suite
completa en verde sobre código que no hacía lo que decía, y resúmenes de subagentes afirmando que
algo quedó instalado cuando `adb` había respondido `device not found`.

Lo que sí vale:

- **Ejecutar y mirar la salida.** Si afirmás que algo quedó instalado, que sea porque leíste
  `Success`.
- **Mutar el código y reproducir.** Si un test no falla cuando rompés a propósito lo que prueba, ese
  test no prueba nada.
- **Leer los logs del aparato**, no el código que los emite. `adb logcat -s <TAG>`. Los tags útiles:
  `ArkivPlay` (ViewModel del reproductor), `DituExo` (Caracol; dice `fromDisk=true/false`),
  `ArkivLocalDl` y `ArkivDituDl` (descargas), `ArkivCast`.
- **Medir en el receptor, no en el emisor.** Los problemas de cast se diagnosticaron leyendo el log
  del TV por ADB, no el del celular.

Cuando algo no se pueda verificar, **decilo**. Un "no pude comprobarlo" es información; un "listo"
sin comprobar cuesta la sesión siguiente.

## Sondas de debug que ya existen

En `app/src/debug/` (nunca viajan a un APK de release):

- `SondaDeCaracolOffline` — vuelve a medir en 30 s si Caracol concede licencias offline. Pide la de
  streaming como **control**, que es lo que hace válida la conclusión.
- `PruebaDeDescargaDeCaracol` — ejercita la descarga real (cola → worker → estrategia → reproducir →
  borrar) sin tocar la pantalla. Pasos: `bajar`, `estado`, `borrar`.

Se disparan con `adb shell am broadcast -n com.arkiv.player.light/<clase>`; el KDoc de cada una trae
el comando exacto. Son el patrón a copiar para probar algo de fondo sin pelear con la UI.

## Antes de dar por terminado

1. `./gradlew :app:assembleDebug :app:testDebugUnitTest` en verde.
2. Probado en un aparato de verdad, o dicho explícitamente que no.
3. Commiteado — ver las reglas de commit en [reglas.md](reglas.md). El trabajo sin commitear se
   pierde entre sesiones, y acá ya pasó.
