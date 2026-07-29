# Blog: endpoints y configuración para la capa de proveedores on-device

Runbook para servir la capa de proveedores de Arkiv desde el servidor blog (`blog.comparadorinternet.co`), con énfasis en endpoints JSON y configuración de indexers latino/anime en Jackett.

---

## Host y arquitectura

- **Instancia principal:** Jackett en `jackett.comparadorinternet.co` (reverse proxy Caddy/nginx ya presente).
- **Servicio systemd:** `abelardo-jackett` (o equivalente, usuario-local).
- **FlareSolverr:** disponible localmente para superar Cloudflare en indexers que lo requieran.

---

## 1. Endpoint `GET /providers.json` (estático)

### Propósito
Servir una lista JSON de definiciones de proveedores (mismo formato que `app/src/main/assets/providers.json`), pero curada y extendida con proveedores latino/anime.

### Contenido
- **Proveedor bundled:** `bitsearch.to` (incluido en la app por defecto).
- **Extensiones:** proveedores de anime y latino adicionales configurables desde blog.
- **Formato:** array JSON de objetos `ProviderDefinition` con campos `id`, `name`, `baseUrl`, `languages`, `parser_rules`, etc.

### Configuración en Caddy

Añadir el siguiente bloque al site `jackett.comparadorinternet.co` en `Caddyfile`:

```caddy
handle /providers.json {
    root * /srv/arkiv
    file_server
}
```

con el archivo en `/srv/arkiv/providers.json`.

### Preparación del archivo `providers.json`

1. Partir del `app/src/main/assets/providers.json` ya presente en el proyecto.
2. Curar la lista: eliminar proveedores obsoletos, añadir latino/anime según § 4.
3. Validar JSON:
   ```bash
   jq empty /srv/arkiv/providers.json && echo "JSON válido"
   ```

---

## 2. Endpoint `GET /health` (verificación de estado)

### Propósito
Verificar el estado de Jackett y FlareSolverr desde la app, evitando errores silenciosos.

### Respuesta esperada
```json
{
  "status": "ok",
  "jackettUp": true,
  "flaresolverrUp": true
}
```

### Configuración en Caddy (opción simple — recomendada)

Respuesta JSON estática (no requiere verificación real de Jackett):

```caddy
handle /health {
    respond `{"status":"ok","jackettUp":true,"flaresolverrUp":true}` 200 {
        header Content-Type application/json
    }
}
```

### Alternativa avanzada (opcional, no recomendada para v1)

Si se desea que `/health` verifique realmente el estado de Jackett:
- Compilar un binario pequeño en el Mac (nunca en blog, que tiene 2 CPU limitados).
- Script: hacer ping a `http://localhost:6969/api/v2.0/server/config` (puerto local de Jackett).
- Devolver `jackettUp: true/false` según el resultado.
- Exponer via systemd `--user` + socket Unix o puerto local.
- Proxy desde Caddy.

**Para esta implementación inicial, usar la opción estática (respuesta fija 200).**

---

## 3. Verificación (manual desde el Mac)

Tras aplicar la configuración en Caddy:

### Verificar `/health`
```bash
curl -s https://jackett.comparadorinternet.co/health
# Esperado: {"status":"ok","jackettUp":true,"flaresolverrUp":true}
```

### Verificar `/providers.json`
```bash
curl -s https://jackett.comparadorinternet.co/providers.json | head -c 200
# Esperado: `[{"id":"...","name":"...","baseUrl":"...","languages":[...], ...`
```

### Verificar desde la app
En el emulador/device (con la app apuntando a `jackett.comparadorinternet.co`):
- Iniciar búsqueda de contenido.
- Verificar que la app carga la lista de proveedores desde `/providers.json`.
- Confirmar que no hay errores de conexión (logs: `D/ProviderBackend`).

---

## 4. Indexers latino/anime a añadir en Jackett

### Propósito
Ampliar el catálogo de búsqueda desde Arkiv incluyendo indexers públicos de anime y contenido latino/castellano.

### Indexers recomendados

#### Anime
| Indexer | Disponible en Jackett | Requiere | Estado |
|---------|------------------------|----------|--------|
| Nyaa | Sí | — | ✓ Público, sin Cloudflare |
| AnimeTosho | Sí | — | ✓ Público, sin Cloudflare |
| AniDUB | Sí (según config) | — | Verificar |

#### Latino/Castellano (trackers públicos españoles)
| Indexer | Disponible en Jackett | Requiere | Estado |
|---------|------------------------|----------|--------|
| MejorTorrent | Sí | — | ✓ Público, Cloudflare → FlareSolverr |
| DonTorrent | Sí | — | ✓ Público, Cloudflare → FlareSolverr |
| Wolfmax | Sí | — | Verificar disponibilidad |
| EliteTorrent | Sí | — | ✓ Público, requiere Cloudflare |
| DivxTotal | Sí | — | ✓ Público, requiere Cloudflare |
| Lat-Team | Según Jackett | — | Considerar si Jackett lo soporta |

### Pasos de configuración

1. **Acceder a la UI de Jackett:**
   ```
   https://jackett.comparadorinternet.co (o IP:6969 si está en LAN)
   ```

2. **Para cada indexer de la tabla:**
   - Click en **"Add Indexer"**.
   - Buscar por nombre (ej. "Nyaa", "MejorTorrent").
   - Configurar según requiera:
     - **Sin Cloudflare:** click en "Test" → debe estar verde.
     - **Con Cloudflare:** verificar que FlareSolverr está habilitado en settings; click en "Test" → debe estar verde (FlareSolverr intercede automáticamente).
   - **Guardar.**

3. **Verificar que aparecen en búsquedas:**
   ```bash
   curl -s "https://jackett.comparadorinternet.co/api/v2.0/indexers/all/results?apikey=<API_KEY>&Query=one+piece" \
     | jq '.Results | map(.Tracker) | unique'
   # Esperado: array que incluye ["Nyaa", "AnimeTosho", "MejorTorrent", ...]
   ```

### Consideraciones

- **Cloudflare en Jackett:** FlareSolverr ya está configurado (`blog:8191` o equivalente). Jackett lo detecta automáticamente si está en la misma máquina.
- **APIs de Jackett:** la app consulta `/api/v2.0/indexers/all/results` (agregado) y cada búsqueda devuelve resultados de indexers activos.
- **Curación continua:** revisar mensualmente indexers que fallen o cierren; eliminarlos de Jackett.
- **Idioma:** Jackett etiqueta automáticamente results según el indexer; la app puede filtrar por idioma si lo necesita (no implementado en v1).

---

## Resumen: workflow de implementación

1. **Paso 1 (este documento):** entender la arquitectura y requisitos.
2. **Paso 2 (manual en blog):**
   - Actualizar `Caddyfile` con los dos `handle` (`,/providers.json` y `/health`).
   - Crear/actualizar `/srv/arkiv/providers.json`.
   - Añadir indexers latino/anime en la UI de Jackett.
   - Recargar Caddy: `sudo systemctl reload caddy`.
   - Verificar con curl (ver § 3).
3. **Paso 3 (en la app):**
   - Verificar que la app carga `/providers.json` sin errores.
   - Ejecutar búsquedas y confirmar que indexers latino/anime aparecen en results.
4. **Paso 4 (monitoreo):**
   - Revisar logs de Jackett si hay fallos.
   - Mantener la lista de indexers actualizada (algunos cierran periódicamente).

---

## Referencias y enlaces

- **Jackett:** https://github.com/Jackett/Jackett
- **FlareSolverr:** https://github.com/FlareSolverr/FlareSolverr
- **Spec de capa de proveedores:** `/Users/cristian/archive/.superpowers/sdd/tprov/spec.md`
- **Archivo bundled de proveedores:** `app/src/main/assets/providers.json`
