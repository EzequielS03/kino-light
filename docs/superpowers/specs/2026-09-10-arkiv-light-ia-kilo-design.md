# Arkiv Light — Sub-proyecto 4: IA en el aparato, con los modelos gratis de Kilo

Rama `light-magis`. Continúa 3A (Caracol directo, `2026-09-10-arkiv-light-ditu-rcn-design.md`).

## Contexto

Dos funciones de la app dependían de un modelo de lenguaje y se perdieron al sacar el servidor:

- **El dato curioso** del reproductor. Se quitó en 2B (commit `e161b231`), y ese mismo commit dejó
  escrito por qué: *"la única forma de conservarla sin servidor sería pegarle a un LLM con llave
  embebida, que es otro sub-proyecto"*.
- **La fila "Para ti"** del home del TV. Se conservó a pedido de Cristian, pero sin quién la llene:
  `RecomendacionDao.upsert` no tiene ningún llamador en todo el árbol, así que la fila solo muestra lo
  último que alcanzó a generar el gateway.

Las dos las generaba el gateway `arkiv-api` a través de `llm-libre`, el gateway propio de LLMs gratis
que corre en `blog`. De sus proveedores, **Kilo es el único que no pide llave**: `llm-libre` lo usa en
su "tier anónimo", con `KILO_API_KEY` vacía a propósito (`client.py`: *"Kilo's anonymous tier depends
on this"*). Eso es lo que hace posible este sub-proyecto: **la app le habla a Kilo directo, sin
servidor y sin ningún secreto embebido**. No es la llave embebida que temía el commit de 2B.

## Lo verificado contra Kilo en vivo (2026-09-10)

- `GET https://api.kilo.ai/api/gateway/models`, **sin cabecera `Authorization`** → HTTP 200, 370
  modelos. **22 tienen `pricing.prompt == 0`**; descartando los clasificadores y los "routers al azar"
  quedan 19, y exigiendo `tools` en `supported_parameters`, **17**.
- `POST https://api.kilo.ai/api/gateway/chat/completions`, **sin llave**, con el prompt real de la
  trivia y `nvidia/nemotron-3-super-120b-a12b:free` → **HTTP 200 en 19,5 s**, con un arreglo JSON limpio
  en español ("La canción 'Remember Me' obtuvo el Premio Oscar a Mejor Canción Original en 2018").
- **Kilo no manda cabeceras de límite** (`x-ratelimit-*`, `retry-after`): no hay forma de saber de
  antemano cuánto queda. Hay que tolerar un 429 cuando llegue.
- Sin llave, **el límite es por aparato** (por IP): cada televisor o celular gasta su propia cuota. Es
  mejor que el gateway, donde las apps compartían una.

## Decisiones tomadas

**Todo junto**: el cliente, el dato curioso y "Para ti" en un solo sub-proyecto (decisión de Cristian).

**Enfoque A: Kilo directo con salto entre modelos.** Se descartó portar el enrutador completo de
`llm-libre` (puntajes, sondas, baterías de calidad: necesitó nueve rondas de revisión y un servidor
vigilando todo el día) y se descartó fijar un solo id de modelo (los gratis cambian de nombre y de
disponibilidad; la regla de `llm-libre` es descubrir el catálogo desde `/models` siempre).

**Solo los modelos gratis de Kilo.** Nada de proveedores de pago, nada de `chatgpt-proxy` ni de los
otros frentes de `llm-libre`: esos viven en el servidor.

**La trivia se restaura desde git.** `TriviaDelPlayer.kt` (277 líneas) y `TriviaDelPlayerTest.kt` se
quitaron enteros en `e161b231`. Se vuelven a traer —el botón en el reproductor, el dato que baja desde
arriba al pulsar, la rotación— y solo cambia de dónde salen los datos.

**"Para ti" sigue solo en el TV**, como hoy. En el celular no hay fila.

## El API de Kilo, tal como se usa

- Base: `https://api.kilo.ai/api/gateway`. Dialecto OpenAI.
- **Nunca se manda `Authorization`**: el tier anónimo depende de que no viaje.
- Catálogo: `GET /models`. Un modelo es candidato si cumple las tres:
  1. **Gratis**: `pricing.prompt` convertido a número es exactamente `0.0` (mismo `_is_free` de
     `llm-libre/src/llm_libre/catalog.py`).
  2. **Acepta `tools`** en `supported_parameters`. No porque se usen: es el mismo filtro que el gateway
     aplica con `x_requires: ["tools"]`, porque descarta los frentes web que empalman texto propio
     dentro del `content`, y la app dibuja ese texto tal cual en la pantalla.
  3. **No se describe como algo que no es un modelo de chat**: su `name` o `description` no calza con
     las expresiones de `catalog.py` (`guardrail`, `content safety`, `moderation`, `classifier`,
     `reranker`, `embeddings`, `speech to text`, `text to speech`, y los meta-routers: `models router`,
     `is a router`, `rotates through`, `router … selects`, `selects … models … at random`). Se descarta
     por lo que el modelo dice de sí mismo, no por su id.
- Pedido: `POST /chat/completions` con `{"model": <id>, "messages": [{"role": "user", "content": …}]}`.
- La respuesta útil está en `choices[0].message.content`.

## Componentes

### 1. El cliente de IA (`data/ia/`)

Recibe una instrucción y devuelve el texto de la respuesta, o dice "no pude". No sabe nada de
películas: es la única pieza que conoce a Kilo.

- **Catálogo**: se pide `/models` y se filtra como arriba. Se guarda en memoria **6 horas**; si al
  renovarlo `/models` falla, sigue con el último que tenía.
- **Orden: lo que funcionó en este aparato.** Por cada modelo se recuerdan éxitos y fallos recientes,
  **persistidos entre sesiones**. Los que respondieron bien van primero; los que fallan bajan. Así se
  ajusta solo, sin sondas ni puntajes de servidor.
- **Espera por modelo**:
  - un **429** lo deja en espera lo que diga `Retry-After`, o **10 minutos** si no dice nada;
  - un **5xx**, un error de red o una demora de más de **45 s** lo deja en espera **5 minutos**;
  - una respuesta que llegó pero **no se puede leer** pasa al siguiente modelo **sin castigarlo**
    (en `llm-libre` costó nueve rondas aprender que un fallo del cliente no puede excluir una ruta).
- **Máximo 4 modelos por pedido** (eran 3; subido a 4 el 2026-09-11 por decisión de Cristian). Si ninguno sirve, "no pude". Nunca lanza hacia arriba.
- **JSON envuelto**: port de `arkiv-api/src/arkiv_api/llm_json.py`. Saca el arreglo (o el objeto) aunque
  el modelo lo envuelva en ```` ```json ```` o le agregue texto alrededor; un arreglo donde se pidió un
  objeto es un error, no un éxito a medias.

### 2. El dato curioso

- **Cuándo**: al empezar a reproducir una película o un capítulo, de Magis o de Caracol, se piden en
  segundo plano. **No** en canales en vivo, **no** en contenido de adultos (la marca que ya usa la app,
  `ContenidoDeAdultos` / `MagisEfimero.Pendiente.adulto`), **no** en archivos descargados.
- **Por cuál obra se pregunta**: por el nombre de TMDB cuando el ítem tiene `tmdbId`; si no, por su
  `tituloCanonico`. Si no hay ninguno de los dos, **no se pregunta**: preguntar a ciegas es la forma más
  rápida de que el modelo invente. Si es un capítulo, se pregunta por ESE capítulo (`"<nombre>, temporada
  T, episodio E"`, o solo el episodio si no se sabe la temporada): el gateway midió que así salen datos
  del capítulo (director, guionista, estreno) y no genéricos de la serie.
- **El prompt**, el mismo del gateway (`trivia/datos.py`), con `cuantos = 8` y `largo = 220`:
  > Dame {cuantos} datos curiosos y verificables sobre {obra}. Cada uno UNA sola frase corta, en español
  > de Colombia, sin voseo, de menos de {largo} caracteres. SIN SPOILERS: nada de lo que pasa en la
  > trama, ni finales, ni giros. Habla de producción, doblaje, música, reparto, rodaje, recepción o
  > contexto histórico. Responde SOLO un arreglo JSON de cadenas, sin texto alrededor.
- **Limpieza**: solo cadenas, recortadas, de 220 caracteres o menos, como máximo 8. El tope de largo va
  en el código y no solo en el prompt: "corto" es algo que un modelo respeta a veces.
- **Caché en el aparato, 30 días por obra** (la clave es tipo + tmdbId o título + temporada +
  capítulo). Un dato sobre una película de 1995 no cambia. **Un fallo pasajero NO se guarda**: sellarlo
  dejaría a la obra sin trivia un mes por una caída de treinta segundos. Es un caché: perderlo solo
  cuesta volver a preguntar, así que vive en archivos del aparato y no en una tabla de Room nueva (sin
  migración de base).
- **Cómo se ve**: la UI restaurada de `e161b231^` — el botón en el reproductor, el dato que baja desde
  arriba al pulsar, la rotación entre los 8. **Sin datos no hay botón.**

### 3. "Para ti"

- **Cuándo**: al **terminar** algo o marcarlo como visto. Los ganchos son `ArkivRepository.setWatched`
  y el momento en que `savePlayback` deja un capítulo como visto (el umbral de `UmbralDeVisto`), que es
  donde el gateway se disparaba antes. **No** en cada `savePlayback`: ese se llama cada pocos segundos
  mientras se ve algo. **Como mucho una vez cada 24 h**; si el que falló fue el modelo, se reintenta a
  los **15 minutos**, no a las 24 h. Corre en segundo plano y nadie espera la respuesta.
- **El prompt conserva la palabra *repetido*** aunque la app no la vaya a mandar (ver el historial,
  abajo): se porta tal cual para no tocar un prompt que el gateway afinó midiendo.
- **El historial sale de la base de la app** (`PlaybackEntity` + `ItemEntity`), no de PocketBase:
  - **terminado**: marcado como visto (`watched`);
  - **abandonado**: menos del **10 %** visto (el mismo `UMBRAL_ABANDONO = 0.10` del gateway);
  - a mitad de camino no dice nada ("lo estás viendo ahora") y se salta;
  - se toman los más recientes por `lastPlayedAt`.

  **Lo que se pierde**: la señal *repetido*. La app guarda solo la última reproducción de cada
  capítulo, no un historial de reproducciones, así que no hay de dónde sacarla. El contenido de adultos
  no aparece en el historial por construcción: la app no escribe su progreso.
- **El prompt**, el del gateway (`recomendaciones/modelo.py`), pidiendo **20**:
  > Eres un recomendador de películas y series para una persona de Colombia. Te doy lo que vio:
  > 'terminado' le gustó, 'abandonado' lo dejó (NO propongas nada parecido), 'repetido' le gustó mucho.
  > Propón {cuantos} títulos que NO estén en la lista. Responde SOLO un arreglo JSON, sin texto
  > alrededor, con objetos {"titulo","anio","tipo","porque"}. "tipo" es "movie" o "tv". "porque" es UNA
  > frase corta en español de Colombia, sin voseo, que explique la relación con lo que vio. Nada de
  > contenido para adultos.

  Se porta **tal cual**, incluidas dos lecciones que el gateway midió y dejó escritas: **no** pasarle la
  biblioteca entera (ahoga el historial y las sugerencias se vuelven clásicos genéricos) y **no** forzar
  la forma del "porque" con un ejemplo literal (el modelo tomó las palabras de estado por títulos).
- **La cascada**, en el orden del gateway (`verificacion.py`), del paso más barato al más caro:
  1. **¿Existe?** Se busca en TMDB por el tipo que propuso el modelo y, si no aparece, por el otro. El
     tipo que se usa de ahí en adelante es el que TMDB confirmó. La búsqueda ya va con
     `include_adult=false`, así que lo adulto no pasa.
  2. **¿Ya lo tienes?** Se descarta si su `tmdbId` o su título normalizado (sin tildes, mayúsculas ni
     signos) está en la biblioteca o en el historial. Se comparan **los dos títulos**, el de TMDB y el del
     modelo. Un título que normaliza a vacío nunca cuenta.
  3. **¿Se puede reproducir?** Se busca en la fuente compuesta (Magis y Caracol) con el título, el tipo y
     el `tmdbId` de TMDB. El año no viaja acá —`GatewaySearchQuery` no tiene ese campo, y el adaptador lo
     descarta—; llega recién al árbitro, en el paso siguiente.
  4. **¿Es esa obra?** El **árbitro** —otra llamada al cliente de IA— recibe la lista numerada de
     resultados y responde cuáles corresponden, con el prompt del gateway (`arbitro.py`):
     > Busco: {titulo}{anio} ({tipo}). Abajo hay una lista numerada de resultados de varias fuentes:
     > nombres de release de torrents, ítems de archivos y entradas de catálogo. Dime cuáles corresponden
     > a ESA obra exacta. Una temporada o un capítulo de la serie buscada sí corresponde; un release con
     > el título dentro del nombre (p. ej. 'Titulo.2022.1080p-dual-lat') sí corresponde. Un podcast,
     > reseña, documental sobre la obra, otra obra del mismo universo o una de nombre parecido NO
     > corresponde. Si busco una película, una serie del mismo nombre NO corresponde, y al revés tampoco.
     > Responde SOLO un arreglo JSON con los números que sí, p. ej. [0,2]. Si ninguno corresponde,
     > responde [].

     Se toma el primer resultado aprobado. Un rechazo total descarta al candidato. **Si el árbitro no
     contesta, se toma el primer resultado**, como hacía el gateway.
  5. Se paran al llegar a **10**.
- **Dónde se guarda**: en la tabla `recomendaciones` que ya existe (`RecomendacionEntity`), con `porque`,
  `ref` (el de Magis o el de Caracol, que la fila ya sabe abrir con `AgregadorDeRecomendaciones`),
  `posterUrl` de TMDB y `orden` 0..9. **Un fallo nunca borra lo de ayer**: si el modelo no contestó, o si
  después de verificar no quedó ninguna, las recomendaciones anteriores se quedan. Una fila vacía por un
  error pasajero se ve igual que "no tengo nada para ti".

## Errores y lo que ve la persona

Nada de esto bloquea la interfaz ni muestra un error. **Sin datos, no hay botón de dato curioso. Sin
recomendaciones nuevas, quedan las de antes.** El detalle de cada fallo va al log.

## Reglas de la rama

- **`api.kilo.ai` se suma al `CLAUDE.md`** como el punto 8 de los hosts permitidos: tercero público, sin
  llave, no es servidor propio.
- **No hay ningún secreto nuevo**: Kilo se usa sin llave. Nada va al `.env` ni a `BuildConfig`.

## Privacidad

"Para ti" le manda a Kilo **los títulos de lo que viste**. El gateway ya lo hacía con sus proveedores;
la diferencia es que ahora sale directo desde el aparato, con su IP. El dato curioso manda el nombre
de la obra que se está reproduciendo y, desde la adenda del 2026-09-10, también la ficha pública de
TMDB con la que se ancla: reparto, fechas de estreno o emisión, director, guionistas, creadores,
cadena y los mismos datos del capítulo si aplica. Son datos públicos de catálogo -los que cualquiera
ve en la página de TMDB de la obra-, no datos personales de quien mira.

## Riesgos

- **Kilo es un tercero gratis sin garantías.** Su tier anónimo puede cambiar de reglas o desaparecer.
  Si pasa, las dos funciones se apagan solas (sin botón, sin filas nuevas) y la app sigue funcionando.
- **La calidad es una ruleta.** `llm-libre` midió que con el mismo prompt, corridas seguidas van de
  excelentes a genéricas. El orden por "lo que funcionó en este aparato" mide que el modelo *contestó*,
  no que contestó *bien*.
- **Tarda ~20 s.** Por eso todo es de fondo: la trivia se pide al empezar a reproducir y "Para ti" nunca
  tiene a nadie esperando.
- **El límite anónimo no se conoce.** Kilo no lo anuncia; un aparato que pida mucho puede recibir 429
  seguidos. La espera por modelo y el tope de 3 intentos por pedido acotan el daño.

## No entra

"Para ti" en el celular, una pantalla de chat, la búsqueda por frase o el canonizador de títulos (que
en `main` también usan un LLM), cualquier proveedor que no sea Kilo, y RCN (3B).

## Verificación

Cada paso: suite completa en verde (`--rerun`) y `assembleDebug`.

- **Tests de JVM** para cada parte pura: el filtro y el orden del catálogo, la espera por 429 y por
  5xx, el JSON envuelto, la limpieza y el caché de la trivia, la rotación (su test se restaura), las
  señales del historial, la cascada con fuentes y árbitro de mentira, y que un fallo no borre las
  recomendaciones. El cliente de Kilo, contra un servidor de mentira (MockWebServer), como Caracol.
- **En el KALLEY R3**: que aparezca el botón del dato curioso al ver una película y un capítulo, que los
  datos sean de esa obra y sin spoilers, que no aparezca en un canal en vivo ni en contenido de adultos,
  y que después de terminar algo aparezca la fila "Para ti" con recomendaciones que se puedan abrir.

## Adenda (2026-09-10): el dato curioso anclado en TMDB

Fuera de plan, sobre lo que ya construyeron las Tasks 5 y 6. Decisión de Cristian: "si mejorarlo", y
después "preguntar por la serie y el capítulo así le damos más contexto, algo como serie + contexto +
otros".

**Qué se midió en el TV**: se revisaron los 24 datos guardados y se contrastaron con la web. En
*Naruto* E2, unos 5 de 8 eran creíbles pero la fecha estaba mal (se emitió el 10-oct-2002, no el 13).
En *Linternas* casi todo estaba inventado: el director real es James Hawes, los creadores
Mundy/Lindelof/King, el estreno fue el 16-ago-2026 y la historia pasa en la Tierra. En *La Selección*
también casi todo inventado — por ejemplo, "La Gozadera" es de 2015 y la serie es de 2013. Pedirle 8
datos de un capítulo sin ningún contexto obliga al modelo gratis de Kilo a inventar con seguridad, sobre
todo en obras poco conocidas o muy nuevas.

**La decisión**: anclar la pregunta en una ficha de hechos verificados de TMDB (nunca la sinopsis, que
trae trama y el dato curioso no puede tener spoilers), nombrando la serie Y el capítulo — no solo el
capítulo a secas — para darle más contexto. El modelo puede devolver menos de 8, o ninguno: es mejor
que diga "no sé" a que invente. Por eso **el prompt deja de ser el del gateway "tal cual"**: la versión
vieja (arriba, sección "2. El dato curioso") no tenía de dónde sacar hechos reales para apoyarse, así
que no tenía cómo pedirle al modelo que no contradijera nada.

**La ficha** (`FichaDeObra`, con `FichaDeCapitulo` para el capítulo puntual) trae, según el caso:
título/nombre, fecha de estreno o primera emisión, directores, guionistas, reparto principal (5),
productoras y duración (película); nombre, primera emisión, creadores, cadenas y reparto principal
(serie); y nombre del capítulo, fecha de emisión, director, guionistas e invitados (capítulo). Los
campos vacíos se omiten, nunca se inventan, y el `overview` de TMDB no entra en ningún campo de la
ficha — no hay forma de que llegue al prompt.

**"Hasta 8, o ninguno"**: el prompt pide como máximo 8 datos, ya no 8 exactos, y le pide al modelo que
se apoye en la ficha, que nunca contradiga sus fechas ni sus nombres, que no incluya un dato si no está
seguro de que es cierto para ESA obra, y que es mejor devolver pocos —o un arreglo vacío— que inventar.

**El `[]` se guarda**: antes cualquier respuesta sin datos se trataba como si no se hubiera podido
preguntar, y no se guardaba. Con el prompt nuevo un `[]` es una respuesta legítima ("no tengo nada
seguro para esta obra"): guardarlo evita repreguntarle a Kilo (~20 s) cada vez que alguien vuelve a
abrir esa obra. Lo que sigue sin guardarse es un fallo del modelo, una respuesta ilegible, o un arreglo
que traía datos y quedó vacío tras la limpieza (eso es un tropiezo del modelo, no un "no sé").

**La clave del caché cambia de versión** (`v2:` al principio): así los datos ya guardados con el prompt
viejo —muchos de ellos inventados, como los de arriba— dejan de mostrarse, sin tener que borrar a mano
los archivos del caché viejo (se quedan hasta que vencen sus 30 días).

**Ajuste (2026-09-10, medido en el TV con *Naruto* E3)**: TMDB guarda el `name` de mucha gente
japonesa en su alfabeto original (kanji), ilegible para quien ve en Colombia. La ficha descarta ahora
el nombre de una persona sin ninguna letra latina, y el prompt le pide lo mismo al modelo; la clave del
caché sube a `v3:` para que los datos ya guardados con nombres en kanji se vuelvan a generar.
