# Hot-update de definiciones de trackers (arkiv-providers)

Arkiv resuelve búsquedas de torrents contra una lista de "proveedores" (trackers:
BitSearch, etc.), definida en `providers.json`. Esa lista puede actualizarse
**sin recompilar ni republicar la app**: basta con editar un archivo en un
repo público de GitHub y los dispositivos lo recogen solos.

## Cómo funciona (ya implementado)

- La app trae un `providers.json` **bundled** dentro del APK, en
  `app/src/main/assets/providers.json`. Esta es la línea base: siempre
  disponible, incluso sin red.
- En cada arranque, `SettingsStore` lee la URL configurada en
  `DEFAULT_PROVIDERS_URL` (constante en
  `app/src/main/java/com/arkiv/player/data/SettingsStore.kt`):

  ```
  https://raw.githubusercontent.com/lordmacu/arkiv-providers/main/providers.json
  ```

  y descarga ese JSON remoto.
- `ProviderRegistry.merge(bundled, remote)` (en
  `app/src/main/java/com/arkiv/player/data/catalog/providers/ProviderRegistry.kt`)
  combina ambas listas **por `id`**: si un proveedor remoto tiene el mismo
  `id` que uno bundled, **el remoto gana** (sobrescribe selector, dominio,
  `priority`, etc.). Los proveedores remotos con `id` nuevo se agregan. El
  resultado final se ordena por `priority` descendente.
- Si el dispositivo no tiene red hacia GitHub (o el fetch falla), la app
  usa exclusivamente el `providers.json` bundled — no hay pantalla en blanco
  ni crash, solo se pierde el hot-update de esa sesión.

En otras palabras: **el bundled es el suelo garantizado; el remoto es un
parche opcional que se aplica por encima, campo por campo, usando el `id`
como llave.**

## Setup inicial (una sola vez, acción manual del usuario)

Este repo (`arkiv-android` / este código) **no** crea el repo externo por
automatización — es una acción manual porque implica crear un recurso
público en GitHub bajo la cuenta del usuario.

```bash
# En una carpeta aparte, fuera de este repo:
gh repo create lordmacu/arkiv-providers --public --clone
cp /Users/cristian/archive/app/src/main/assets/providers.json arkiv-providers/providers.json
cd arkiv-providers && git add providers.json && git commit -m "seed providers.json" && git push
```

Con esto, `raw.githubusercontent.com/lordmacu/arkiv-providers/main/providers.json`
queda sirviendo exactamente la misma lista que trae el APK — cero cambio de
comportamiento hasta que se edite.

## Flujo de arreglo (cuando un tracker se rompe)

Cuando un tracker cambie su HTML/dominio y deje de dar resultados:

1. Edita `providers.json` en `github.com/lordmacu/arkiv-providers` (web o
   móvil, directamente en la UI de GitHub).
2. Ajusta el campo roto: el selector de scraping, `baseUrl`/`hostAlt` (si
   cambió de dominio), `searchPath`, `enabled` (para desactivarlo del todo
   mientras se investiga), etc. Cada objeto del array se identifica por su
   campo `id` (p. ej. `"bitsearch"`) — ese `id` es la llave del merge, no lo
   cambies salvo que quieras que se trate como un proveedor nuevo.
3. Commit y push al branch `main` del repo `arkiv-providers`.
4. Los dispositivos con la app instalada recogen el cambio automáticamente
   en su **siguiente arranque** (no hace falta actualizar el APK ni esperar
   a Google Play): fetch del JSON remoto → merge por `id` sobre el bundled
   → remoto gana.

Sin red hacia GitHub en ese arranque, la app sigue funcionando con el
`providers.json` bundled en el APK (la línea base), simplemente sin el
parche más reciente hasta que haya red de nuevo.

## Notas

- El JSON bundled (`app/src/main/assets/providers.json`) sigue siendo la
  fuente de verdad para nuevas instalaciones y para el modo offline; conviene
  mantenerlo razonablemente al día en el propio repo de la app (vía PR
  normal) aunque el hot-update permita parchear en caliente entre releases.
- Un proveedor completamente nuevo (id que no existe en el bundled) puede
  agregarse solo en el remoto — no requiere tocar el APK — pero solo estará
  disponible en dispositivos con acceso al remoto en ese arranque.
