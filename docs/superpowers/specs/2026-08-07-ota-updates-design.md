# OTA Updates — Design Spec

## Objetivo

Que la app (celular y TV) detecte automáticamente cuando hay una versión nueva del APK,
muestre un diálogo con barra de descarga, y al terminar lance la instalación del sistema.

## Servidor

Un archivo JSON estático en el mismo nginx que ya sirve el APK (`apk.comparadorinternet.co`).

**Archivo:** `https://apk.comparadorinternet.co/latest.json`

```json
{
  "versionCode": 2,
  "versionName": "0.2.0",
  "url": "https://apk.comparadorinternet.co/Arkiv-0.2.0.apk",
  "notes": "Fix del seek en TV, modo noche"
}
```

**Publicar una versión nueva (3 pasos):**

1. Bump `versionCode` y `versionName` en `app/build.gradle.kts`.
2. `./gradlew assembleRelease`
3. `scp` del APK + `latest.json` actualizado a `blog:/var/www/arkiv/`.

Nginx ya sirve ambos archivos — no hay que tocar la config.

## Chequeo periódico

- **WorkManager** con `PeriodicWorkRequest` cada 6 horas, con constraint de red disponible.
- El Worker hace `GET /latest.json`, parsea el JSON, compara `versionCode` contra
  `BuildConfig.VERSION_CODE`.
- Si el remoto es mayor, escribe el resultado en un `MutableStateFlow<UpdateInfo?>` expuesto
  desde `AppGraph` (el singleton de dependencias).
- Al abrir la app (`MainActivity.onCreate`) también se ejecuta un chequeo inmediato (one-shot).

## UI — Diálogo bloqueante

Cuando `updateInfo` no es null, se muestra un diálogo modal Compose:

**Estado inicial:**
- Título: "Nueva versión disponible (X.Y.Z)"
- Cuerpo: notas del changelog
- Botones: "Actualizar ahora" / "Cerrar"
- En TV el foco arranca en "Actualizar ahora" (navegable con D-pad)

**Estado descargando:**
- Barra de progreso lineal con porcentaje
- Botón "Cancelar"

**Estado descarga completa:**
- Lanza `ACTION_INSTALL_PACKAGE` con el URI del APK (vía FileProvider)
- Android muestra su prompt de confirmación y reinicia la app al instalar

**Permiso `REQUEST_INSTALL_PACKAGES`:**
- Se declara en el manifest.
- Antes de descargar, se verifica `packageManager.canRequestPackageInstalls()`.
- Si no está habilitado, se abre `ACTION_MANAGE_UNKNOWN_APP_SOURCES` para que el usuario
  lo active. En el Fire Stick esto abre la pantalla de Settings correspondiente.

## Componentes nuevos

| Archivo | Responsabilidad |
|---------|----------------|
| `data/update/UpdateChecker.kt` | GET + parse de `latest.json`, comparación de versión |
| `data/update/UpdateWorker.kt` | Worker de WorkManager, llama a UpdateChecker |
| `data/update/ApkDownloader.kt` | Descarga el APK con progreso (OkHttp streaming) |
| `data/update/UpdateInfo.kt` | Data class con versionCode, versionName, url, notes |
| `ui/update/UpdateDialog.kt` | Diálogo Compose (inicial → descargando → listo) |

## Integración

- `ArkivApp.onCreate()`: registra el Worker periódico (enqueue unique).
- `AppGraph`: expone `val updateInfo: StateFlow<UpdateInfo?>` y referencia a `ApkDownloader`.
- `MainActivity`: observa `updateInfo`; cuando no es null, muestra `UpdateDialog`.
- `AndroidManifest.xml`: agrega `REQUEST_INSTALL_PACKAGES`, registra `FileProvider` para
  compartir el APK descargado con el instalador del sistema.

## TV vs Celular

El mismo código funciona en ambos. El diálogo Compose es navegable con D-pad (botones
enfocables). El intent `ACTION_INSTALL_PACKAGE` del sistema también funciona con el control
del Fire Stick. No se necesita lógica condicional por plataforma.

## Fuera de alcance

- Descarga en background (si la app se cierra, se pierde — el APK solo son ~134 MB, ~2 min).
- Delta updates (patches parciales).
- Rollback automático.
- Actualización forzada (el usuario siempre puede cerrar el diálogo).
