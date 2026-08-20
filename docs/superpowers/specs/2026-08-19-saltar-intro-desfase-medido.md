# Desfase de AniSkip medido contra un capítulo real (Tarea 1)

**Fecha:** 2026-08-19
**Aparato:** Fire TV Stick, `192.168.1.22:5555` (ADB de red), app `com.arkiv.player` (Kino)
**De qué spec depende:** [2026-08-19-saltar-intro-automatico-design.md](./2026-08-19-saltar-intro-automatico-design.md),
apartado "Riesgos"

## Punto de partida

Death Note (MAL 1535, sugerido en el brief de la Tarea 1) **no está en la biblioteca**. La
biblioteca del aparato tiene 45 títulos, 12 series; de las series con cobertura confirmada por el
brief solo hay dos disponibles: **Neon Genesis Evangelion** (MAL 30) y **Dragon Ball** (MAL 223).

Se probaron **las dos**, porque la primera (Evangelion) dio un resultado anómalo y hacía falta un
segundo caso para saber si el problema era de AniSkip/Magis en general o algo propio de esa serie.

Medición hecha con el heartbeat del propio player por logcat (dato que pasó el usuario a mitad de
tarea, más preciso que leer la barra en pantalla):

```
adb -s 192.168.1.22:5555 shell "logcat -d --pid=$(adb -s 192.168.1.22:5555 shell pidof com.arkiv.player | tr -d '\r') -t 40" | grep "HB player"
```

Cada línea trae `pos=<ms>` — la posición real de reproducción al milisegundo. Capturas de pantalla
tomadas exactamente cuando `pos` cruzaba el umbral de interés (script en background que sondea el
heartbeat y dispara `screencap` al cruce).

Fuente usada en ambos casos: **magis** (la fuente con más episodios y la más "encode consistente"
según el spec).

---

## Caso 1: Dragon Ball, T1·E1 — "El secreto de la esfera del dragón" (fuente magis)

AniSkip (`GET /v2/skip-times/223/1?types=op&types=ed&episodeLength=0`):

```
op: startTime=2.489s  endTime=92.489s     episodeLength=1452.4s (24:12.4)
```

Video real (heartbeat): duración total `1481422ms` (24:41.4) — 29s más largo que lo que asume
AniSkip, pero **el frente del video (el opening) no está corrido**:

| pos (heartbeat) | qué se ve en pantalla |
|---|---|
| 81067ms (1:21) | Opening en curso: Goku sobre la nube Kinto'un junto a un avión, letra en pantalla ("そうさ いまこそ アドベンチャー!") |
| 90027ms (1:30) | Última carta del opening: créditos de producción "制作 フジテレビ / 東映" (Fuji TV / Toei) |
| 93099ms (1:33) | Opening ya terminado: arte de transición (paisaje de montañas estilo tinta), sin canción ni letra |
| 102059ms (1:42) | Sigue el arte de transición (previo a que arranque la acción del capítulo) |

**Lectura:** medí en dos instantes, no en uno — a los 90,0s el opening todavía estaba en curso
(carta de créditos de producción), a los 93,1s ya había terminado (arte de transición, sin canción).
El opening real termina en algún punto **dentro** de esa ventana de 3,1s; esa ventana es la
**resolución de mi propio muestreo**, no el desfase de AniSkip. El valor de AniSkip, 92,489s, cae
**dentro** de la ventana, no fuera de ella — no hay evidencia de que el tiempo esté corrido. El
desfase real es, como mucho, la mitad del ancho de la ventana (**±1,5s**), y probablemente menor:
92,489s queda a solo 0,61s del instante en que confirmé que el opening YA había terminado (93,099s),
y a 2,46s del instante en que confirmé que TODAVÍA estaba en curso (90,027s) — está pegado al borde
donde el opening termina, no a la mitad de la ventana.

**Veredicto: CALZA.** El valor de AniSkip cae dentro de la ventana muestreada; el desfase real es de
±1,5s como mucho, no "hasta 3s" (eso mezclaba el desfase con la resolución de mi medición). Un botón
"Saltar intro" calculado con el tiempo de AniSkip dejaría el video justo donde termina el opening,
no a mitad de una escena.

---

## Caso 2: Neon Genesis Evangelion, T1·E1 y T1·E2 (fuente magis) — anomalía

AniSkip:

```
E1 → op: 0s   → 90s     (episodeLength=1402s, 23:22)
E2 → op: 0s   → 91s     (episodeLength=1402s, 23:22)
```

Video real: E1 dura `1417749ms` (23:37.7), E2 dura `1417621ms` (23:37.6) — ambos **+15-16s** más
largos que lo que asume AniSkip. Se muestreó repetidamente entre pos=0 y pos=200000ms (0 a 3:20) en
los dos episodios:

| Episodio | pos (heartbeat) | qué se ve en pantalla |
|---|---|---|
| E1 | 0ms | Pájaro parado sobre un cañón militar (contenido del capítulo) |
| E1 | 52000ms | Mismo tipo de escena — artillería, ciudad devastada |
| E1 | 87000-97000ms | Primer plano dramático de Shinji (contenido del capítulo) |
| E1 | 105000ms | Plano aéreo de ciudad inundada/en ruinas (contenido del capítulo) |
| E1 | 200000ms (3:20) | Calle con autos evacuando Tokio-3 (contenido del capítulo) |
| E2 | 81067ms | Shinji en la cápsula del Eva, tenso (contenido del capítulo) |
| E2 | 93099ms | Mano gigante del Eva sobre una cabina telefónica (contenido del capítulo) |
| E2 | 100000-108000ms | Mismo tipo de escena de acción |

**En ningún punto muestreado (0 a 200s+, dos episodios distintos) apareció la canción del opening**
(sin letra en pantalla, sin créditos de staff, sin la secuencia de imágenes fijas típica del OP).
Todo lo que se ve es contenido narrativo del capítulo.

**Lectura:** en esta fuente (Magis) el opening de Evangelion **no está donde AniSkip dice que
está**. No es un corrimiento de unos segundos corregible con un offset — es una ausencia total del
opening en la ventana 0-90/91s. Esto coincide con algo conocido de esta serie en particular: el
capítulo 1 (y por diseño original, varios más) de Evangelion NO abre con el opening — hay un
cold-open largo antes. Puede ser eso, o que esta copia de Magis para Evangelion recorta/reordena el
opening de otra forma. No se investigó la causa raíz porque no es necesario para la decisión de
esta tarea.

**Esto NO es "desfase errático"** en el sentido que usa el brief (saltos impredecibles capítulo a
capítulo del mismo show) — al contrario, **falla del mismo modo en los dos episodios probados**: el
opening simplemente no está en la ventana que reporta AniSkip. Es un caso reconocible y consistente,
distinto del caso general.

---

## Decisión (Paso 3 del brief de la Tarea 1)

- **Dragon Ball (caso representativo del general):** desfase real de ±1,5s como mucho → **sigue el
  plan tal cual** para series con esta estructura (opening al inicio del capítulo, sin cold-open
  largo).
- **Evangelion:** AniSkip no sirve para esta serie con la fuente Magis actual — el opening que
  reporta no corresponde a nada reproducido en esa ventana. **No bloquea las Tareas 2-7** (el
  criterio de aceptación #3 del spec — "capítulo sin datos no muestra botón, sin error" — ya cubre
  el caso de que el gateway no tenga datos; lo que hace falta es que alguien decida, más adelante,
  si Evangelion necesita quedar excluido a mano o si el gateway debería poder detectar que el
  tiempo resuelto no calza). Se anota como hallazgo, no se intenta arreglar acá.

**Veredicto global: GO.** Se sigue con la Tarea 2 tal cual el plan. Evangelion queda como caso
conocido y documentado, no como bloqueante — el riesgo real (desfase de release) se confirmó bajo
en el caso general (Magis, Dragon Ball) y la falla de Evangelion es de otra naturaleza (ausencia de
opening en la ventana esperada, no corrimiento de release).

## Dudas / lo que alguien debería mirar más adelante

- No se pudo probar con Death Note (no está en biblioteca) ni con una tercera serie normal para
  confirmar más allá de Dragon Ball que el caso general calza — con una sola serie "limpia" alcanza
  para el go/no-go de esta tarea, pero un tercer dato no vendría mal antes de dar por buena la
  cobertura completa.
- Evangelion: valdría la pena, en algún momento, decidir si el gateway (Tarea 2/3) debe intentar
  detectar automáticamente que un tiempo resuelto "no calza" (p. ej. comparando contra la duración
  real) o si simplemente se excluye esa serie a mano de la lista curada. No se resolvió acá a
  propósito — el encargo de esta tarea era medir, no arreglar.
